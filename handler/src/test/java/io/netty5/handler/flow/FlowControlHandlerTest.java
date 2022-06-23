/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty5.handler.flow;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.buffer.api.Buffer;
import io.netty5.util.Resource;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelConfig;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.channel.nio.NioHandler;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.timeout.IdleStateEvent;
import io.netty5.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty5.buffer.api.DefaultBufferAllocators.preferredAllocator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class FlowControlHandlerTest {
    private static EventLoopGroup eventLoopGroup;

    @BeforeAll
    public static void init() {
        eventLoopGroup = new MultithreadEventLoopGroup(NioHandler.newFactory());
    }

    @AfterAll
    public static void destroy() {
        eventLoopGroup.shutdownGracefully();
    }

    /**
     * The {@link OneByteToThreeStringsDecoder} decodes this {@code byte[]} into three messages.
     */
    private static Buffer newOneMessage() {
        return preferredAllocator().allocate(1).writeByte((byte) 1);
    }

    private static Channel newServer(final boolean autoRead, final ChannelHandler... handlers) throws Exception {
        assertTrue(handlers.length >= 1);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.AUTO_READ, autoRead)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new OneByteToThreeStringsDecoder());
                    pipeline.addLast(handlers);
                }
            });

        return serverBootstrap.bind(0).get();
    }

    private static Channel newClient(SocketAddress server) throws Exception {
        Bootstrap bootstrap = new Bootstrap();

        bootstrap.group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
            .handler(new ChannelHandler() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    fail("In this test the client is never receiving a message from the server.");
                }
            });

        return bootstrap.connect(server).get();
    }

    /**
     * This test demonstrates the default behavior if auto reading
     * is turned on from the get-go and you're trying to turn it off
     * once you've received your first message.
     *
     * NOTE: This test waits for the client to disconnect which is
     * interpreted as the signal that all {@code byte}s have been
     * transferred to the server.
     */
    @Test
    public void testAutoReadingOn() throws Exception {
        final CountDownLatch latch = new CountDownLatch(3);

        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                Resource.dispose(msg);
                // We're turning off auto reading in the hope that no
                // new messages are being sent but that is not true.
                ctx.channel().config().setAutoRead(false);

                latch.countDown();
            }
        };

        Channel server = newServer(true, handler);
        Channel client = newClient(server.localAddress());

        try {
            client.writeAndFlush(newOneMessage())
                .sync();

            // We received three messages even through auto reading
            // was turned off after we received the first message.
            assertTrue(latch.await(1L, SECONDS));
        } finally {
            client.close();
            server.close();
        }
    }

    /**
     * This test demonstrates the default behavior if auto reading
     * is turned off from the get-go and you're calling read() in
     * the hope that only one message will be returned.
     *
     * NOTE: This test waits for the client to disconnect which is
     * interpreted as the signal that all {@code byte}s have been
     * transferred to the server.
     */
    @Test
    public void testAutoReadingOff() throws Exception {
        final Exchanger<Channel> peerRef = new Exchanger<>();
        final CountDownLatch latch = new CountDownLatch(3);

        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                Resource.dispose(msg);
                latch.countDown();
            }
        };

        Channel server = newServer(false, handler);
        Channel client = newClient(server.localAddress());

        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            // Write the message
            client.writeAndFlush(newOneMessage())
                .sync();

            // Read the message
            peer.read();

            // We received all three messages but hoped that only one
            // message was read because auto reading was off and we
            // invoked the read() method only once.
            assertTrue(latch.await(1L, SECONDS));
        } finally {
            client.close();
            server.close();
        }
    }

    /**
     * The {@link FlowControlHandler} will simply pass-through all messages
     * if auto reading is on and remains on.
     */
    @Test
    public void testFlowAutoReadOn() throws Exception {
        final CountDownLatch latch = new CountDownLatch(3);
        final Exchanger<Channel> peerRef = new Exchanger<Channel>();

        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                Resource.dispose(msg);
                latch.countDown();
            }
        };

        final FlowControlHandler flow = new FlowControlHandler();
        Channel server = newServer(true, flow, handler);
        Channel client = newClient(server.localAddress());
        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            // Write the message
            client.writeAndFlush(newOneMessage())
                .sync();

            // We should receive 3 messages
            assertTrue(latch.await(1L, SECONDS));

            assertTrue(peer.executor().submit(flow::isQueueEmpty).get());
        } finally {
            client.close();
            server.close();
        }
    }

    /**
     * The {@link FlowControlHandler} will pass down messages one by one
     * if {@link ChannelConfig#setAutoRead(boolean)} is being toggled.
     */
    @Test
    public void testFlowToggleAutoRead() throws Exception {
        final Exchanger<Channel> peerRef = new Exchanger<>();
        final CountDownLatch msgRcvLatch1 = new CountDownLatch(1);
        final CountDownLatch msgRcvLatch2 = new CountDownLatch(1);
        final CountDownLatch msgRcvLatch3 = new CountDownLatch(1);
        final CountDownLatch setAutoReadLatch1 = new CountDownLatch(1);
        final CountDownLatch setAutoReadLatch2 = new CountDownLatch(1);

        ChannelHandler handler = new ChannelHandler() {
            private int msgRcvCount;
            private int expectedMsgCount;
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws InterruptedException {
                Resource.dispose(msg);

                // Disable auto reading after each message
                ctx.channel().config().setAutoRead(false);

                if (msgRcvCount++ != expectedMsgCount) {
                    return;
                }
                switch (msgRcvCount) {
                    case 1:
                        msgRcvLatch1.countDown();
                        if (setAutoReadLatch1.await(1L, SECONDS)) {
                            ++expectedMsgCount;
                        }
                        break;
                    case 2:
                        msgRcvLatch2.countDown();
                        if (setAutoReadLatch2.await(1L, SECONDS)) {
                            ++expectedMsgCount;
                        }
                        break;
                    default:
                        msgRcvLatch3.countDown();
                        break;
                }
            }
        };

        final FlowControlHandler flow = new FlowControlHandler();
        Channel server = newServer(true, flow, handler);
        Channel client = newClient(server.localAddress());
        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            client.writeAndFlush(newOneMessage())
                .sync();

            // channelRead(1)
            assertTrue(msgRcvLatch1.await(1L, SECONDS));

            // channelRead(2)
            peer.config().setAutoRead(true);
            setAutoReadLatch1.countDown();
            assertTrue(msgRcvLatch1.await(1L, SECONDS));

            // channelRead(3)
            peer.config().setAutoRead(true);
            setAutoReadLatch2.countDown();
            assertTrue(msgRcvLatch3.await(1L, SECONDS));

            assertTrue(peer.executor().submit(flow::isQueueEmpty).get());
        } finally {
            client.close();
            server.close();
        }
    }

    /**
     * The {@link FlowControlHandler} will pass down messages one by one
     * if auto reading is off and the user is calling {@code read()} on
     * their own.
     */
    @Test
    public void testFlowAutoReadOff() throws Exception {
        final Exchanger<Channel> peerRef = new Exchanger<>();
        final CountDownLatch msgRcvLatch1 = new CountDownLatch(1);
        final CountDownLatch msgRcvLatch2 = new CountDownLatch(2);
        final CountDownLatch msgRcvLatch3 = new CountDownLatch(3);

        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.fireChannelActive();
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                msgRcvLatch1.countDown();
                msgRcvLatch2.countDown();
                msgRcvLatch3.countDown();
            }
        };

        final FlowControlHandler flow = new FlowControlHandler();
        Channel server = newServer(false, flow, handler);
        Channel client = newClient(server.localAddress());
        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            // Write the message
            client.writeAndFlush(newOneMessage())
                .sync();

            // channelRead(1)
            peer.read();
            assertTrue(msgRcvLatch1.await(1L, SECONDS));

            // channelRead(2)
            peer.read();
            assertTrue(msgRcvLatch2.await(1L, SECONDS));

            // channelRead(3)
            peer.read();
            assertTrue(msgRcvLatch3.await(1L, SECONDS));

            assertTrue(peer.executor().submit(flow::isQueueEmpty).get());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    public void testReentranceNotCausesNPE() throws Throwable {
        final Exchanger<Channel> peerRef = new Exchanger<Channel>();
        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicReference<Throwable> causeRef = new AtomicReference<Throwable>();
        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.fireChannelActive();
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                latch.countDown();
                ctx.read();
            }

            @Override
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                causeRef.set(cause);
            }
        };

        final FlowControlHandler flow = new FlowControlHandler();
        Channel server = newServer(false, flow, handler);
        Channel client = newClient(server.localAddress());
        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            // Write the message
            client.writeAndFlush(newOneMessage())
                    .sync();

            // channelRead(1)
            peer.read();
            assertTrue(latch.await(1L, SECONDS));

            assertTrue(peer.executor().submit(flow::isQueueEmpty).get());

            Throwable cause = causeRef.get();
            if (cause != null) {
                throw cause;
            }
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    public void testSwallowedReadComplete() throws Exception {
        final long delayMillis = 100;
        final Queue<IdleStateEvent> userEvents = new LinkedBlockingQueue<IdleStateEvent>();
        final EmbeddedChannel channel = new EmbeddedChannel(false, false,
            new FlowControlHandler(),
            new IdleStateHandler(delayMillis, 0, 0, MILLISECONDS),
            new ChannelHandler() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    ctx.fireChannelActive();
                    ctx.read();
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ctx.fireChannelRead(msg);
                    ctx.read();
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    ctx.fireChannelReadComplete();
                    ctx.read();
                }

                @Override
                public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof IdleStateEvent) {
                        userEvents.add((IdleStateEvent) evt);
                    }
                    ctx.fireChannelInboundEvent(evt);
                }
            }
        );

        channel.config().setAutoRead(false);
        assertFalse(channel.config().isAutoRead());

        channel.register();

        // Reset read timeout by some message
        assertTrue(channel.writeInbound(Unpooled.EMPTY_BUFFER));
        channel.flushInbound();
        assertEquals(Unpooled.EMPTY_BUFFER, channel.readInbound());

        // Emulate 'no more messages in NIO channel' on the next read attempt.
        channel.flushInbound();
        assertNull(channel.readInbound());

        Thread.sleep(delayMillis + 20L);
        channel.runPendingTasks();
        assertEquals(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT, userEvents.poll());
        assertFalse(channel.finish());
    }

    @Test
    public void testRemoveFlowControl() throws Exception {
        final Exchanger<Channel> peerRef = new Exchanger<Channel>();

        final CountDownLatch latch = new CountDownLatch(3);

        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                peerRef.exchange(ctx.channel(), 1L, SECONDS);
                //do the first read
                ctx.read();
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                latch.countDown();
                ctx.fireChannelRead(msg);
            }
        };

        final FlowControlHandler flow = new FlowControlHandler() {
            private int num;
            @Override
            public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
                super.channelRead(ctx, msg);
                ++num;
                if (num >= 3) {
                    //We have received 3 messages. Remove myself later
                    final ChannelHandler handler = this;
                    ctx.channel().executor().execute(new Runnable() {
                        @Override
                        public void run() {
                            ctx.pipeline().remove(handler);
                        }
                    });
                }
            }
        };
        ChannelHandler tail = new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                //consume this msg
                Resource.dispose(msg);
            }
        };

        Channel server = newServer(false /* no auto read */, flow, handler, tail);
        Channel client = newClient(server.localAddress());
        try {
            // The client connection on the server side
            Channel peer = peerRef.exchange(null, 1L, SECONDS);

            // Write one message
            client.writeAndFlush(newOneMessage()).sync();

            // We should receive 3 messages
            assertTrue(latch.await(1L, SECONDS));
            assertTrue(peer.executor().submit(flow::isQueueEmpty).get());
        } finally {
            client.close();
            server.close();
        }
    }

    /**
     * This is a fictional message decoder. It decodes each {@code byte}
     * into three strings.
     */
    private static final class OneByteToThreeStringsDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in) {
            for (int i = 0; i < in.readableBytes(); i++) {
                ctx.fireChannelRead("1");
                ctx.fireChannelRead("2");
                ctx.fireChannelRead("3");
            }
            in.readerIndex(in.readableBytes());
        }
    }
}
