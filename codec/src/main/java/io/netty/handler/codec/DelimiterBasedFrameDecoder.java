/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s by one or more
 * delimiters.  It is particularly useful for decoding the frames which ends
 * with a delimiter such as {@link Delimiters#nulDelimiter() NUL} or
 * {@linkplain Delimiters#lineDelimiter() newline characters}.
 *
 * <h3>Predefined delimiters</h3>
 * <p>
 * {@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 * <p>
 * {@link DelimiterBasedFrameDecoder} allows you to specify more than one
 * delimiter.  If more than one delimiter is found in the buffer, it chooses
 * the delimiter which produces the shortest frame.  For example, if you have
 * the following data in the buffer:
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 * a {@link DelimiterBasedFrameDecoder}({@link Delimiters#lineDelimiter() Delimiters.lineDelimiter()})
 * will choose {@code '\n'} as the first delimiter and produce two frames:
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 */
// 基于分隔符解码器,默认去除分隔符
public class DelimiterBasedFrameDecoder extends ByteToMessageDecoder {
    // 多个分割符
    private final ByteBuf[] delimiters;
    // 最大帧的length
    private final int maxFrameLength;
    // 解码时是否去掉分隔符 ，默认剔除
    private final boolean stripDelimiter;
    // 如果设置成true，当发现解析的数据超过maxFrameLength就立马报错，否则当整个帧的数据解析完后才报错 默认 false
    private final boolean failFast;
    //当前的解码器是否处于discardingTooLongFrame状态，这个参数最容易理解为是否丢弃
    private boolean discardingTooLongFrame;
    //这是一个状态属性，就是说出现了超长帧了，哪这个帧的长度到底是多少，就是这个长度，一般来说是在发现当前buffer的可读数据超过最大帧时候进行设置
    private int tooLongFrameLength;
    /** Set only when decoding with "\n" and "\r\n" as the delimiter.  */
    private final LineBasedFrameDecoder lineBasedDecoder;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
        this(maxFrameLength, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast,
            ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, new ByteBuf[] {
                delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
        this(maxFrameLength, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
        this(maxFrameLength, stripDelimiter, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }

        if (isLineBased(delimiters) && !isSubclass()) {
            lineBasedDecoder = new LineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
            this.delimiters = null;
        } else {
            this.delimiters = new ByteBuf[delimiters.length];
            for (int i = 0; i < delimiters.length; i ++) {
                ByteBuf d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    /** Returns true if the delimiters are "\n" and "\r\n".  */
    private static boolean isLineBased(final ByteBuf[] delimiters) {
        if (delimiters.length != 2) {
            return false;
        }
        ByteBuf a = delimiters[0];
        ByteBuf b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        return a.capacity() == 2 && b.capacity() == 1
                && a.getByte(0) == '\r' && a.getByte(1) == '\n'
                && b.getByte(0) == '\n';
    }

    /**
     * Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder
     */
    private boolean isSubclass() {
        return getClass() != DelimiterBasedFrameDecoder.class;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    /**
     * 首先我们看到最开始的代码就发现，最上面实际上是尝试所有的分隔符，然后找出一个可用将帧分割最小的一个分割符出来，下面就是if和else;
     * 1 if的逻辑:
     *     1.1 如果找到了分割符，如果当前的解码器处于discardingTooLongFrame状态，也就是说上次解码的时候发现了超长帧，被抛弃过。
     *         首先将这个状态修改过来，标志为不是抛弃过超长帧，这个时候将整个帧丢弃掉，然后如果不是failfast状态，抛出异常，
     *         这个怎么理解呢，可以理解为上次在读取的时候发现了超长帧，但是由于设置了不立即抛出异常，而是等读完整个帧数据才抛出异常，
     *         这个时候既然发现了分隔符，该到抛出异常的时候了，最后return null表明此次分帧是失败状态。
     *     如果发现此次的帧数据超过最大帧的长度，直接抛出异常
     *     最后就是如果跳过分隔符，就直接跳过，负责就和分隔符和帧的实际数据一块返回
     *
     *2  else的逻辑是当前读到的数据没有发现分隔符的情况下的逻辑，我们来看下：
     *      如果发现当前的解码器不是处于discardingTooLongFrame状态，当前buffer里面的可读数据又比最大帧要大，
     *      我们就将解码器标记为discardingTooLongFrame状态，并设置这个超长帧的大小，如果是failfast状态，
     *      就立马抛出异常，也就是说我们发现了超长帧了，所以我们立马抛出异常
     *      如果发现当前的解码器不是处于discardingTooLongFrame状态，当前buffer里面的可读数据又比最大帧要大，
     *      我们就将解码器标记为discardingTooLongFrame状态，并设置这个超长帧的大小，如果是failfast状态，就立马抛出异常，
     *      也就是说我们发现了超长帧了，所以我们立马抛出异常
     *     如果发现当前的解码器已经处于discardingTooLongFrame状态，我们别无他方，
     *     只能修改下tooLongFrameLength的长度，然后听天由命，等待下次解码操作
     *     这个时候如果一旦发现了超长帧，都return null，含义就是说此次解码是无效的
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        if (lineBasedDecoder != null) {
            return lineBasedDecoder.decode(ctx, buffer);
        }
        // Try all delimiters and choose the delimiter which yields the shortest frame.
        int minFrameLength = Integer.MAX_VALUE;
        ByteBuf minDelim = null;
        //process 找出一个可用将帧分割最小的一个分割符出来
        for (ByteBuf delim: delimiters) {
            int frameLength = indexOf(buffer, delim);
            if (frameLength >= 0 && frameLength < minFrameLength) {
                minFrameLength = frameLength;
                minDelim = delim;
            }
        }

        if (minDelim != null) { //process 已经找到分隔符
            int minDelimLength = minDelim.capacity();
            ByteBuf frame;

            if (discardingTooLongFrame) { //process 当前是否是丢弃模式， 第一次为false
                // We've just finished discarding a very large frame.
                // Go back to the initial state.
                discardingTooLongFrame = false;
                buffer.skipBytes(minFrameLength + minDelimLength);

                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;
                if (!failFast) {
                    fail(tooLongFrameLength);
                }
                return null;
            }

            if (minFrameLength > maxFrameLength) { //process 当前找到的数据包大于 最大允许数据包
                // Discard read frame.
                buffer.skipBytes(minFrameLength + minDelimLength); //process 把不合法的数据包进行丢弃
                fail(minFrameLength);
                return null;
            }

            if (stripDelimiter) { //process 解析出来的数据包是否包含分隔符，默认是true
                frame = buffer.readRetainedSlice(minFrameLength);
                buffer.skipBytes(minDelimLength); //process 跳过已读的数据包
            } else {
                frame = buffer.readRetainedSlice(minFrameLength + minDelimLength);
            }

            return frame;
        } else {
            if (!discardingTooLongFrame) { //process 当前非丢弃模式
                if (buffer.readableBytes() > maxFrameLength) { //process 如果可读字节已经大于最大允许长度
                    // Discard the content of the buffer until a delimiter is found.
                    tooLongFrameLength = buffer.readableBytes();
                    buffer.skipBytes(buffer.readableBytes());
                    discardingTooLongFrame = true;
                    if (failFast) {
                        fail(tooLongFrameLength);
                    }
                }
            } else {
                // Still discarding the buffer since a delimiter is not found.
                tooLongFrameLength += buffer.readableBytes();
                buffer.skipBytes(buffer.readableBytes());
            }
            return null;
        }
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * Returns the number of bytes between the readerIndex of the haystack and
     * the first needle found in the haystack.  -1 is returned if no needle is
     * found in the haystack.
     */
    private static int indexOf(ByteBuf haystack, ByteBuf needle) {
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i ++) {
            int haystackIndex = i;
            int needleIndex;
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex ++) {
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    haystackIndex ++;
                    if (haystackIndex == haystack.writerIndex() &&
                        needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        return -1;
    }

    private static void validateDelimiter(ByteBuf delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.isReadable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException(
                    "maxFrameLength must be a positive integer: " +
                    maxFrameLength);
        }
    }
}
