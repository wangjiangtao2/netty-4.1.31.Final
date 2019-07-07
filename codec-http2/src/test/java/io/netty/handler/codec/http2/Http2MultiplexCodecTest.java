/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.Http2Exception.StreamException;
import io.netty.handler.codec.http2.LastInboundHandler.Consumer;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.ReferenceCountUtil.release;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link Http2MultiplexCodec}.
 */
public class Http2MultiplexCodecTest {

    private EmbeddedChannel parentChannel;
    private Writer writer;

    private TestChannelInitializer childChannelInitializer;

    private static final Http2Headers request = new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName()).scheme(HttpScheme.HTTPS.name())
            .authority(new AsciiString("example.org")).path(new AsciiString("/foo"));

    private TestableHttp2MultiplexCodec codec;
    private TestableHttp2MultiplexCodec.Stream inboundStream;
    private TestableHttp2MultiplexCodec.Stream outboundStream;

    private static final int initialRemoteStreamWindow = 1024;

    @Before
    public void setUp() {
        childChannelInitializer = new TestChannelInitializer();
        parentChannel = new EmbeddedChannel();
        writer = new Writer();

        parentChannel.connect(new InetSocketAddress(0));
        codec = new TestableHttp2MultiplexCodecBuilder(true, childChannelInitializer).build();
        parentChannel.pipeline().addLast(codec);
        parentChannel.runPendingTasks();

        Http2Settings settings = new Http2Settings().initialWindowSize(initialRemoteStreamWindow);
        codec.onHttp2Frame(new DefaultHttp2SettingsFrame(settings));

        inboundStream = codec.newStream();
        inboundStream.id = 3;
        outboundStream = codec.newStream();
        outboundStream.id = 2;
    }

    @After
    public void tearDown() throws Exception {
        if (childChannelInitializer.handler != null) {
            ((LastInboundHandler) childChannelInitializer.handler).finishAndReleaseAll();
        }
        parentChannel.finishAndReleaseAll();
        codec = null;
    }

    // TODO(buchgr): Flush from child channel
    // TODO(buchgr): ChildChannel.childReadComplete()
    // TODO(buchgr): GOAWAY Logic
    // TODO(buchgr): Test ChannelConfig.setMaxMessagesPerRead

    @Test
    public void writeUnknownFrame() {
        childChannelInitializer.handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.writeAndFlush(new DefaultHttp2UnknownFrame((byte) 99, new Http2Flags()));
                ctx.fireChannelActive();
            }
        };

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        Http2FrameStream stream = readOutboundHeadersAndAssignId();
        parentChannel.runPendingTasks();

        Http2UnknownFrame frame = parentChannel.readOutbound();
        assertEquals(stream, frame.stream());
        assertEquals(99, frame.frameType());
        assertEquals(new Http2Flags(), frame.flags());
        frame.release();
    }

    @Test
    public void readUnkownFrame() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        codec.onHttp2Frame(new DefaultHttp2UnknownFrame((byte) 99, new Http2Flags()).stream(inboundStream));
        codec.onChannelReadComplete();

        // headers and unknown frame
        verifyFramesMultiplexedToCorrectChannel(inboundStream, inboundHandler, 2);

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());
    }

    @Test
    public void headerAndDataFramesShouldBeDelivered() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).stream(inboundStream);
        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("hello")).stream(inboundStream);
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("world")).stream(inboundStream);

        assertFalse(inboundHandler.isChannelActive());
        inboundStream.state = Http2Stream.State.OPEN;
        codec.onHttp2StreamStateChanged(inboundStream);
        codec.onHttp2Frame(headersFrame);
        assertTrue(inboundHandler.isChannelActive());
        codec.onHttp2Frame(dataFrame1);
        codec.onHttp2Frame(dataFrame2);

        assertEquals(headersFrame, inboundHandler.readInbound());
        assertEquals(dataFrame1, inboundHandler.readInbound());
        assertEquals(dataFrame2, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());

        dataFrame1.release();
        dataFrame2.release();
    }

    @Test
    public void framesShouldBeMultiplexed() {

        TestableHttp2MultiplexCodec.Stream stream3 = codec.newStream();
        stream3.id = 3;
        TestableHttp2MultiplexCodec.Stream stream5 = codec.newStream();
        stream5.id = 5;

        TestableHttp2MultiplexCodec.Stream stream11 = codec.newStream();
        stream11.id = 11;

        LastInboundHandler inboundHandler3 = streamActiveAndWriteHeaders(stream3);
        LastInboundHandler inboundHandler5 = streamActiveAndWriteHeaders(stream5);
        LastInboundHandler inboundHandler11 = streamActiveAndWriteHeaders(stream11);

        verifyFramesMultiplexedToCorrectChannel(stream3, inboundHandler3, 1);
        verifyFramesMultiplexedToCorrectChannel(stream5, inboundHandler5, 1);
        verifyFramesMultiplexedToCorrectChannel(stream11, inboundHandler11, 1);

        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("hello"), false).stream(stream5));
        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("foo"), true).stream(stream3));
        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("world"), true).stream(stream5));
        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("bar"), true).stream(stream11));
        verifyFramesMultiplexedToCorrectChannel(stream5, inboundHandler5, 2);
        verifyFramesMultiplexedToCorrectChannel(stream3, inboundHandler3, 1);
        verifyFramesMultiplexedToCorrectChannel(stream11, inboundHandler11, 1);
    }

    @Test
    public void inboundDataFrameShouldEmitWindowUpdateFrame() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        ByteBuf tenBytes = bb("0123456789");
        codec.onHttp2Frame(new DefaultHttp2DataFrame(tenBytes, true).stream(inboundStream));
        codec.onChannelReadComplete();

        Http2WindowUpdateFrame windowUpdate = parentChannel.readOutbound();
        assertNotNull(windowUpdate);

        assertEquals(inboundStream, windowUpdate.stream());
        assertEquals(10, windowUpdate.windowSizeIncrement());

        // headers and data frame
        verifyFramesMultiplexedToCorrectChannel(inboundStream, inboundHandler, 2);
    }

    @Test
    public void unhandledHttp2FramesShouldBePropagated() {
        assertThat(parentChannel.readInbound(), instanceOf(Http2SettingsFrame.class));

        Http2PingFrame pingFrame = new DefaultHttp2PingFrame(0);
        codec.onHttp2Frame(pingFrame);
        assertSame(parentChannel.readInbound(), pingFrame);

        DefaultHttp2GoAwayFrame goAwayFrame =
                new DefaultHttp2GoAwayFrame(1, parentChannel.alloc().buffer().writeLong(8));
        codec.onHttp2Frame(goAwayFrame);

        Http2GoAwayFrame frame = parentChannel.readInbound();
        assertSame(frame, goAwayFrame);
        assertTrue(frame.release());
    }

    @Test
    public void channelReadShouldRespectAutoRead() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Channel childChannel = inboundHandler.channel();
        assertTrue(childChannel.config().isAutoRead());
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.config().setAutoRead(false);
        codec.onHttp2Frame(
                new DefaultHttp2DataFrame(bb("hello world"), false).stream(inboundStream));
        codec.onChannelReadComplete();
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        release(dataFrame0);

        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("foo"), false).stream(inboundStream));
        codec.onHttp2Frame(new DefaultHttp2DataFrame(bb("bar"), true).stream(inboundStream));
        codec.onChannelReadComplete();

        dataFrame0 = inboundHandler.readInbound();
        assertNull(dataFrame0);

        childChannel.config().setAutoRead(true);
        verifyFramesMultiplexedToCorrectChannel(inboundStream, inboundHandler, 2);
    }

    private Http2StreamChannel newOutboundStream() {
        return new Http2StreamChannelBootstrap(parentChannel).handler(childChannelInitializer)
                .open().syncUninterruptibly().getNow();
    }

    /**
     * A child channel for a HTTP/2 stream in IDLE state (that is no headers sent or received),
     * should not emit a RST_STREAM frame on close, as this is a connection error of type protocol error.
     */

    @Test
    public void idleOutboundStreamShouldNotWriteResetFrameOnClose() {
        childChannelInitializer.handler = new LastInboundHandler();

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        childChannel.close();
        parentChannel.runPendingTasks();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertNull(parentChannel.readOutbound());
    }

    @Test
    public void outboundStreamShouldWriteResetFrameOnClose_headersSent() {
        childChannelInitializer.handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.fireChannelActive();
            }
        };

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        Http2FrameStream stream2 = readOutboundHeadersAndAssignId();

        childChannel.close();
        parentChannel.runPendingTasks();

        Http2ResetFrame reset = parentChannel.readOutbound();
        assertEquals(stream2, reset.stream());
        assertEquals(Http2Error.CANCEL.code(), reset.errorCode());
    }

    @Test
    public void outboundStreamShouldNotWriteResetFrameOnClose_IfStreamDidntExist() {
        writer = new Writer() {
            private boolean headersWritten;
            @Override
            void write(Object msg, ChannelPromise promise) {
                // We want to fail to write the first headers frame. This is what happens if the connection
                // refuses to allocate a new stream due to having received a GOAWAY.
                if (!headersWritten && msg instanceof Http2HeadersFrame) {
                    headersWritten = true;
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    final TestableHttp2MultiplexCodec.Stream stream =
                            (TestableHttp2MultiplexCodec.Stream) headersFrame.stream();
                    stream.id = 1;
                    promise.setFailure(new Exception("boom"));
                } else {
                    super.write(msg, promise);
                }
            }
        };

        childChannelInitializer.handler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                ctx.fireChannelActive();
            }
        };

        Channel childChannel = newOutboundStream();
        assertFalse(childChannel.isActive());

        childChannel.close();
        parentChannel.runPendingTasks();
        assertTrue(parentChannel.outboundMessages().isEmpty());
    }

    @Test
    public void inboundRstStreamFireChannelInactive() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        assertTrue(inboundHandler.isChannelActive());
        codec.onHttp2Frame(new DefaultHttp2ResetFrame(Http2Error.INTERNAL_ERROR)
                                                       .stream(inboundStream));
        codec.onChannelReadComplete();

        // This will be called by the frame codec.
        inboundStream.state = Http2Stream.State.CLOSED;
        codec.onHttp2StreamStateChanged(inboundStream);
        parentChannel.runPendingTasks();

        assertFalse(inboundHandler.isChannelActive());
        // A RST_STREAM frame should NOT be emitted, as we received a RST_STREAM.
        assertNull(parentChannel.readOutbound());
    }

    @Test(expected = StreamException.class)
    public void streamExceptionTriggersChildChannelExceptionAndClose() throws Exception {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);

        StreamException cause = new StreamException(inboundStream.id(), Http2Error.PROTOCOL_ERROR, "baaam!");
        Http2FrameStreamException http2Ex = new Http2FrameStreamException(
                inboundStream, Http2Error.PROTOCOL_ERROR, cause);
        codec.onHttp2FrameStreamException(http2Ex);

        inboundHandler.checkException();
    }

    @Test(expected = StreamException.class)
    public void streamExceptionClosesChildChannel() throws Exception {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);

        assertTrue(inboundHandler.isChannelActive());
        StreamException cause = new StreamException(inboundStream.id(), Http2Error.PROTOCOL_ERROR, "baaam!");
        Http2FrameStreamException http2Ex = new Http2FrameStreamException(
                inboundStream, Http2Error.PROTOCOL_ERROR, cause);
        codec.onHttp2FrameStreamException(http2Ex);
        parentChannel.runPendingTasks();

        assertFalse(inboundHandler.isChannelActive());
        inboundHandler.checkException();
    }

    @Test(expected = ClosedChannelException.class)
    public void streamClosedErrorTranslatedToClosedChannelExceptionOnWrites() throws Exception {
        writer = new Writer() {
            @Override
            void write(Object msg, ChannelPromise promise) {
                promise.tryFailure(new StreamException(inboundStream.id(), Http2Error.STREAM_CLOSED, "Stream Closed"));
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        ChannelFuture future = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        inboundHandler.checkException();

        future.syncUninterruptibly();
    }

    @Test
    public void creatingWritingReadingAndClosingOutboundStreamShouldWork() {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Http2StreamChannel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());
        assertTrue(inboundHandler.isChannelActive());

        // Write to the child channel
        Http2Headers headers = new DefaultHttp2Headers().scheme("https").method("GET").path("/foo.txt");
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));

        readOutboundHeadersAndAssignId();

        // Read from the child channel
        headers = new DefaultHttp2Headers().scheme("https").status("200");
        codec.onHttp2Frame(new DefaultHttp2HeadersFrame(headers).stream(childChannel.stream()));
        codec.onChannelReadComplete();

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);
        assertSame(headers, headersFrame.headers());

        // Close the child channel.
        childChannel.close();

        parentChannel.runPendingTasks();
        // An active outbound stream should emit a RST_STREAM frame.
        Http2ResetFrame rstFrame = parentChannel.readOutbound();
        assertNotNull(rstFrame);
        assertEquals(childChannel.stream(), rstFrame.stream());
        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertFalse(inboundHandler.isChannelActive());
    }

    // Test failing the promise of the first headers frame of an outbound stream. In practice this error case would most
    // likely happen due to the max concurrent streams limit being hit or the channel running out of stream identifiers.
    //
    @Test(expected = Http2NoMoreStreamIdsException.class)
    public void failedOutboundStreamCreationThrowsAndClosesChannel() throws Exception {
        writer = new Writer() {
            @Override
            void write(Object msg, ChannelPromise promise) {
                promise.tryFailure(new Http2NoMoreStreamIdsException());
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler();
        childChannelInitializer.handler = inboundHandler;

        Channel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        ChannelFuture future = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        inboundHandler.checkException();

        future.syncUninterruptibly();
    }

    @Test
    public void channelClosedWhenCloseListenerCompletes() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        // Create a promise before actually doing the close, because otherwise we would be adding a listener to a future
        // that is already completed because we are using EmbeddedChannel which executes code in the JUnit thread.
        ChannelPromise p = childChannel.newPromise();
        p.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                channelOpen.set(future.channel().isOpen());
                channelActive.set(future.channel().isActive());
            }
        });
        childChannel.close(p).syncUninterruptibly();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenChannelClosePromiseCompletes() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        childChannel.closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                channelOpen.set(future.channel().isOpen());
                channelActive.set(future.channel().isActive());
            }
        });
        childChannel.close().syncUninterruptibly();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenWriteFutureFails() {
        final Queue<ChannelPromise> writePromises = new ArrayDeque<ChannelPromise>();
        writer = new Writer() {
            @Override
            void write(Object msg, ChannelPromise promise) {
                ReferenceCountUtil.release(msg);
                writePromises.offer(promise);
            }
        };

        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        ChannelFuture f = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        assertFalse(f.isDone());
        f.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                channelOpen.set(future.channel().isOpen());
                channelActive.set(future.channel().isActive());
            }
        });

        ChannelPromise first = writePromises.poll();
        first.setFailure(new ClosedChannelException());
        f.awaitUninterruptibly();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedTwiceMarksPromiseAsSuccessful() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());
        childChannel.close().syncUninterruptibly();
        childChannel.close().syncUninterruptibly();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void settingChannelOptsAndAttrs() {
        AttributeKey<String> key = AttributeKey.newInstance("foo");

        Channel childChannel = newOutboundStream();
        childChannel.config().setAutoRead(false).setWriteSpinCount(1000);
        childChannel.attr(key).set("bar");
        assertFalse(childChannel.config().isAutoRead());
        assertEquals(1000, childChannel.config().getWriteSpinCount());
        assertEquals("bar", childChannel.attr(key).get());
    }

    @Test
    public void outboundFlowControlWritability() {
        Http2StreamChannel childChannel = newOutboundStream();
        assertTrue(childChannel.isActive());

        assertTrue(childChannel.isWritable());
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        parentChannel.flush();

        Http2FrameStream stream = readOutboundHeadersAndAssignId();

        // Test for initial window size
        assertEquals(initialRemoteStreamWindow, childChannel.config().getWriteBufferHighWaterMark());

        codec.onHttp2StreamWritabilityChanged(stream, true);
        assertTrue(childChannel.isWritable());
        codec.onHttp2StreamWritabilityChanged(stream, false);
        assertFalse(childChannel.isWritable());
    }

    @Test
    public void writabilityAndFlowControl() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();
        assertEquals("", inboundHandler.writabilityStates());

        // HEADERS frames are not flow controlled, so they should not affect the flow control window.
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
        codec.onHttp2StreamWritabilityChanged(childChannel.stream(), true);

        assertEquals("true", inboundHandler.writabilityStates());

        codec.onHttp2StreamWritabilityChanged(childChannel.stream(), true);
        assertEquals("true", inboundHandler.writabilityStates());

        codec.onHttp2StreamWritabilityChanged(childChannel.stream(), false);
        assertEquals("true,false", inboundHandler.writabilityStates());

        codec.onHttp2StreamWritabilityChanged(childChannel.stream(), false);
        assertEquals("true,false", inboundHandler.writabilityStates());
    }

    @Test
    public void channelClosedWhenInactiveFired() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();

        final AtomicBoolean channelOpen = new AtomicBoolean(false);
        final AtomicBoolean channelActive = new AtomicBoolean(false);
        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        childChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                channelOpen.set(ctx.channel().isOpen());
                channelActive.set(ctx.channel().isActive());

                super.channelInactive(ctx);
            }
        });

        childChannel.close().syncUninterruptibly();
        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
    }

    @Test
    public void channelInactiveHappensAfterExceptionCaughtEvents() throws Exception {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger exceptionCaught = new AtomicInteger(-1);
        final AtomicInteger channelInactive = new AtomicInteger(-1);
        final AtomicInteger channelUnregistered = new AtomicInteger(-1);
        Http2StreamChannel childChannel = newOutboundStream();

        childChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                ctx.close();
                throw new Exception("exception");
            }
        });

        childChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                channelInactive.set(count.getAndIncrement());
                super.channelInactive(ctx);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                exceptionCaught.set(count.getAndIncrement());
                super.exceptionCaught(ctx, cause);
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                channelUnregistered.set(count.getAndIncrement());
                super.channelUnregistered(ctx);
            }
        });

        childChannel.pipeline().fireUserEventTriggered(new Object());
        parentChannel.runPendingTasks();

        // The events should have happened in this order because the inactive and deregistration events
        // get deferred as they do in the AbstractChannel.
        assertEquals(0, exceptionCaught.get());
        assertEquals(1, channelInactive.get());
        assertEquals(2, channelUnregistered.get());
    }

    @Ignore("not supported anymore atm")
    @Test
    public void cancellingWritesBeforeFlush() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Channel childChannel = inboundHandler.channel();

        Http2HeadersFrame headers1 = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers());
        Http2HeadersFrame headers2 = new DefaultHttp2HeadersFrame(new DefaultHttp2Headers());
        ChannelPromise writePromise = childChannel.newPromise();
        childChannel.write(headers1, writePromise);
        childChannel.write(headers2);
        assertTrue(writePromise.cancel(false));
        childChannel.flush();

        Http2HeadersFrame headers = parentChannel.readOutbound();
        assertSame(headers, headers2);
    }

    @Test
    public void callUnsafeCloseMultipleTimes() {
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();
        childChannel.unsafe().close(childChannel.voidPromise());

        ChannelPromise promise = childChannel.newPromise();
        childChannel.unsafe().close(promise);
        promise.syncUninterruptibly();
        childChannel.closeFuture().syncUninterruptibly();
    }

    @Test
    public void endOfStreamDoesNotDiscardData() {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = new Consumer<ChannelHandlerContext>() {
            @Override
            public void accept(ChannelHandlerContext obj) {
                if (shouldDisableAutoRead.get()) {
                    obj.channel().config().setAutoRead(false);
                }
            }
        };
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream, numReads, ctxConsumer);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(inboundStream);
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(inboundStream);
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(inboundStream);
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(inboundStream);

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(inboundStream), inboundHandler.readInbound());

        // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
        parentChannel.writeOneInbound(new Object());
        codec.onHttp2Frame(dataFrame1);
        assertEquals(dataFrame1, inboundHandler.readInbound());

        // Deliver frames, and then a stream closed while read is inactive.
        codec.onHttp2Frame(dataFrame2);
        codec.onHttp2Frame(dataFrame3);
        codec.onHttp2Frame(dataFrame4);

        shouldDisableAutoRead.set(true);
        childChannel.config().setAutoRead(true);
        numReads.set(1);

        inboundStream.state = Http2Stream.State.CLOSED;
        codec.onHttp2StreamStateChanged(inboundStream);

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEquals(dataFrame2, inboundHandler.readInbound());
        assertEquals(dataFrame3, inboundHandler.readInbound());
        assertEquals(dataFrame4, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.flushInbound();

        childChannel.closeFuture().syncUninterruptibly();

        dataFrame1.release();
        dataFrame2.release();
        dataFrame3.release();
        dataFrame4.release();
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopAutoRead() {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = new Consumer<ChannelHandlerContext>() {
            @Override
            public void accept(ChannelHandlerContext obj) {
                channelReadCompleteCount.incrementAndGet();
                if (shouldDisableAutoRead.get()) {
                    obj.channel().config().setAutoRead(false);
                }
            }
        };
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream, numReads, ctxConsumer);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(inboundStream);
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(inboundStream);
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(inboundStream);
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(inboundStream);

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(inboundStream), inboundHandler.readInbound());

        // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
        parentChannel.writeOneInbound(new Object());
        codec.onHttp2Frame(dataFrame1);
        assertEquals(dataFrame1, inboundHandler.readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        codec.onHttp2Frame(dataFrame2);

        numReads.set(10);
        shouldDisableAutoRead.set(true);
        childChannel.config().setAutoRead(true);

        codec.onHttp2Frame(dataFrame3);
        codec.onHttp2Frame(dataFrame4);

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEquals(dataFrame2, inboundHandler.readInbound());
        assertEquals(dataFrame3, inboundHandler.readInbound());
        assertEquals(dataFrame4, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for read when auto read was off + 1 for when auto read was back on
        assertEquals(3, channelReadCompleteCount.get());

        dataFrame1.release();
        dataFrame2.release();
        dataFrame3.release();
        dataFrame4.release();
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopNoAutoRead() {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = new Consumer<ChannelHandlerContext>() {
            @Override
            public void accept(ChannelHandlerContext obj) {
                channelReadCompleteCount.incrementAndGet();
                if (shouldDisableAutoRead.get()) {
                    obj.channel().config().setAutoRead(false);
                }
            }
        };
        LastInboundHandler inboundHandler = streamActiveAndWriteHeaders(inboundStream, numReads, ctxConsumer);
        Http2StreamChannel childChannel = (Http2StreamChannel) inboundHandler.channel();
        childChannel.config().setAutoRead(false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1")).stream(inboundStream);
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2")).stream(inboundStream);
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3")).stream(inboundStream);
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4")).stream(inboundStream);

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(inboundStream), inboundHandler.readInbound());

        // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
        parentChannel.writeOneInbound(new Object());
        codec.onHttp2Frame(dataFrame1);
        assertEquals(dataFrame1, inboundHandler.readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        codec.onHttp2Frame(dataFrame2);

        numReads.set(2);
        childChannel.read();
        assertEquals(dataFrame2, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());

        // This is the second item that was read, this should be the last until we call read() again. This should also
        // notify of readComplete().
        codec.onHttp2Frame(dataFrame3);
        assertEquals(dataFrame3, inboundHandler.readInbound());

        codec.onHttp2Frame(dataFrame4);
        assertNull(inboundHandler.readInbound());

        childChannel.read();
        assertEquals(dataFrame4, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for first read of 2 items + 1 for second read of 2 items +
        // 1 for parent channel readComplete
        assertEquals(4, channelReadCompleteCount.get());

        dataFrame1.release();
        dataFrame2.release();
        dataFrame3.release();
        dataFrame4.release();
    }

    private LastInboundHandler streamActiveAndWriteHeaders(Http2FrameStream stream) {
        return streamActiveAndWriteHeaders(stream, null, LastInboundHandler.<ChannelHandlerContext>noopConsumer());
    }

    private LastInboundHandler streamActiveAndWriteHeaders(Http2FrameStream stream,
                                                           AtomicInteger maxReads,
                                                           Consumer<ChannelHandlerContext> contextConsumer) {

        LastInboundHandler inboundHandler = new LastInboundHandler(contextConsumer);
        childChannelInitializer.handler = inboundHandler;
        childChannelInitializer.maxReads = maxReads;
        assertFalse(inboundHandler.isChannelActive());
        ((TestableHttp2MultiplexCodec.Stream) stream).state = Http2Stream.State.OPEN;
        codec.onHttp2StreamStateChanged(stream);
        codec.onHttp2Frame(new DefaultHttp2HeadersFrame(request).stream(stream));
        codec.onChannelReadComplete();
        assertTrue(inboundHandler.isChannelActive());

        return inboundHandler;
    }

    private static void verifyFramesMultiplexedToCorrectChannel(Http2FrameStream stream,
                                                                LastInboundHandler inboundHandler,
                                                                int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            Http2StreamFrame frame = inboundHandler.readInbound();
            assertNotNull(frame);
            assertEquals(stream, frame.stream());
            release(frame);
        }
        assertNull(inboundHandler.readInbound());
    }

    private static ByteBuf bb(String s) {
        return ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, s);
    }

    /**
     * Simulates the frame codec, in first assigning an identifier and the completing the write promise.
     */
    private Http2FrameStream readOutboundHeadersAndAssignId() {
        // Only peek at the frame, so to not complete the promise of the write. We need to first
        // assign a stream identifier, as the frame codec would do.
        Http2HeadersFrame headersFrame = (Http2HeadersFrame) parentChannel.outboundMessages().peek();
        assertNotNull(headersFrame);
        assertNotNull(headersFrame.stream());
        assertFalse(Http2CodecUtil.isStreamIdValid(headersFrame.stream().id()));
        TestableHttp2MultiplexCodec.Stream frameStream = (TestableHttp2MultiplexCodec.Stream) headersFrame.stream();
        frameStream.id = outboundStream.id();
        // Create the stream in the Http2Connection.
        try {
            Http2Stream stream = codec.connection().local().createStream(
                    headersFrame.stream().id(), headersFrame.isEndStream());
            frameStream.stream = stream;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create a stream", ex);
        }

        // Now read it and complete the write promise.
        assertSame(headersFrame, parentChannel.readOutbound());

        return headersFrame.stream();
    }

    /**
     * This class removes the bits that would transform the frames to bytes and so make it easier to test the actual
     * special handling of the codec.
     */
    private final class TestableHttp2MultiplexCodec extends Http2MultiplexCodec {

        public TestableHttp2MultiplexCodec(Http2ConnectionEncoder encoder,
                                           Http2ConnectionDecoder decoder,
                                           Http2Settings initialSettings,
                                           ChannelHandler inboundStreamHandler) {
            super(encoder, decoder, initialSettings, inboundStreamHandler, null);
        }

        void onHttp2Frame(Http2Frame frame) {
            onHttp2Frame(ctx, frame);
        }

        void onChannelReadComplete() {
            onChannelReadComplete(ctx);
        }

        void onHttp2StreamStateChanged(Http2FrameStream stream) {
            onHttp2StreamStateChanged(ctx, stream);
        }

        void onHttp2FrameStreamException(Http2FrameStreamException cause) {
            onHttp2FrameStreamException(ctx, cause);
        }

        void onHttp2StreamWritabilityChanged(Http2FrameStream stream, boolean writable) {
            onHttp2StreamWritabilityChanged(ctx, stream, writable);
        }

        @Override
        boolean onBytesConsumed(ChannelHandlerContext ctx, Http2FrameStream stream, int bytes) {
            writer.write(new DefaultHttp2WindowUpdateFrame(bytes).stream(stream), ctx.newPromise());
            return true;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            writer.write(msg, promise);
        }

        @Override
        void flush0(ChannelHandlerContext ctx) {
            // Do nothing
        }

        @Override
        Stream newStream() {
            return new Stream();
        }

        final class Stream extends Http2MultiplexCodecStream {
            Http2Stream.State state = Http2Stream.State.IDLE;
            int id = -1;

            @Override
            public int id() {
                return id;
            }

            @Override
            public Http2Stream.State state() {
                return state;
            }
        }
    }

    private final class TestableHttp2MultiplexCodecBuilder extends Http2MultiplexCodecBuilder {

        TestableHttp2MultiplexCodecBuilder(boolean server, ChannelHandler childHandler) {
            super(server, childHandler);
        }

        @Override
        public TestableHttp2MultiplexCodec build() {
            return (TestableHttp2MultiplexCodec) super.build();
        }

        @Override
        protected Http2MultiplexCodec build(
                Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
            return new TestableHttp2MultiplexCodec(
                    encoder, decoder, initialSettings, childHandler);
        }
    }

    class Writer {

        void write(Object msg, ChannelPromise promise) {
            parentChannel.outboundMessages().add(msg);
            promise.setSuccess();
        }
    }
}
