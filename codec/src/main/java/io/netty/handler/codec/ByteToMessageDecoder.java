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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */

/**
 * Cumulator有两种实现，MERGE_CUMULATOR 和 COMPOSITE_CMUMULATOR。
 *  MERGE_CUMULATOR通过memory copy的方法将in中的数据复制写入到cumulation中。
 *  COMPOSITE_CUMULATOR采取的是类似链表的方式，没有进行memory copy, 通过一种CompositeByteBuf来实现，在某些场景下会更适合。默认采用的是MERGE_CUMULATOR。
 */
/**
 * 将字节消息解码成对象
 * 学习步骤
 *   1. channelRead
 *   2. MERGE_CUMULATOR
 *   3. expandCumulation
 *   4. callDecode
 *   5. decodeRemovalReentryProtection
 *
 * 1.累加数据
 * 2.将累加到的数据传递给业务进行业务拆包
 * 3.清理字节容器
 * 4.传递业务数据包给业务解码器处理
 *
 */
@SuppressWarnings("all")
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //只针对ByteBuf进行解码,否则直接透传
        if (msg instanceof ByteBuf) {
            // 用于保存解码的结果, 从对象池中取出一个List
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                ByteBuf data = (ByteBuf) msg;
                /**
                 * 通过cumulation是否为空判断解码器是否缓存了没有解码完成的半包消息
                 * 如果为空,说明是首次解码或者最近一次已经处理完了半包消息,没有缓存的半包消息需要处理，直接将需要解码的ByteBuf赋值给cumulation；
                 * 如果cumulation缓存有上次没有解码完成的ByteBuf，则进行复制操作，将需要解码的ByteBuf复制到cumulation中。
                 */
                first = cumulation == null;
                if (first) {
                    //是第一次就直接赋值
                    cumulation = data;
                } else {
                    //不是第一次就调用累加器,就将data向cumulation累加，并释放 data
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                /**
                 * 调用解码方法
                 * 解码过程中，调用 fireChannelRead 方法，主要目的是将累积区的内容 decode 到 数组中
                 */
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Exception e) {
                throw new DecoderException(e);
            } finally {
                //如果累计区没有可读字节了 ，则释放cumulation
                if (cumulation != null && !cumulation.isReadable()) {
                    // 将次数归零
                    numReads = 0;
                    // 释放累计区
                    cumulation.release();
                    // 等待 gc
                    cumulation = null;
                }
                //判断反复累加次数是不是大于设置的discardAfterReads 16，当达到了discardAfterReads，就要去丢弃一些数据，防止OOM
                else if (++ numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    numReads = 0;
                    discardSomeReadBytes(); //process // 读取了足够的数据，尝试丢弃一些字节，避免OOM风险
                }

                int size = out.size();
                /**
                 * 该值在 调用 channelReadComplete 方法的时候，会触发 read 方法（不是自动读取的话），
                 * 尝试从 JDK 的通道中读取数据，并将之前的逻辑重来。
                 * 主要应该是怕如果什么数据都没有插入，就执行 channelReadComplete 会遗漏数据。
                 */
                // 如果没有向数组插入过任何数据,这个值就是 ture。
                decodeWasNull = !out.insertSinceRecycled();
                //触发渠道的channelRead事件，size是触发多少次事件
                //循环数组，向后面的 handler 发送数据，如果数组是空，那不会调用
                fireChannelRead(ctx, out, size);
                //将数组中的内容清空，将数组的数组的下标恢复至原来
                out.recycle();
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */
    // 调用子类decode方法进行解析 in=cumulation
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            // 循环读取数据
            while (in.isReadable()) {
                int outSize = out.size(); // 若第一次读取，outSize=0
                //大于0表示已经解码出数据了
                if (outSize > 0) {
                    //调用后面的业务 handler 的  ChannelRead 方法 事件传播
                    fireChannelRead(ctx, out, outSize);
                    // 清空结果集
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    // 用户是否主动删除handler,如果ChannelHandlerContext已移除，直接退出循环，继续操作缓冲区是不安全的
                    if (ctx.isRemoved()) {
                        break;
                    }
                    // 将 size 置为0
                    outSize = 0;
                }
                // 从累加区得到可读字节数
                int oldInputLength = in.readableBytes();
                /**
                 * 调用子类实现的 decode 方法，将成功解码后的数据放入 out 数组中，可能会删除当前节点，
                 * 删除之前会将数据发送到最后的 handler,内部调用了子类重写的 decode 方法  模板模式
                 */
                //调用 解码decode 方法，将成功解码后的数据放入道 out 数组中，可能会删除当前节点，删除之前会将数据发送到最后的 handler
                decodeRemovalReentryProtection(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                //用户是否主动删除handler，删除了就跳出循环
                if (ctx.isRemoved()) {
                    break;
                }
                //没有解码出数据, 通过decode方法，对象没有解析出来 ，如果输出的out列表长度没变化，说明解码没有成功
                // 业务解码器遵循契约： 如果业务解码器认为当前的字节缓冲区无法完成业务层的解码，需要将readIndex复位，告诉netty解码条件不满足应当退出解码，继续读取数据报
                if (outSize == out.size()) { //子类未对out处理
                    //如果用户解码器没有消费ByteBuf，则说明是个半包消息，需要由I/O线程继续读取后续的数据报，要退出循环
                        //oldInputLength 执行该方法传进来累加区的字节数量  in 看子类对 in是否 decode 处理过
                    if (oldInputLength == in.readableBytes()) {   // 没有消费ByteBuf，说明是个半包消息，需要继续读取后续的数据报，退出循环
                        break;
                    } else {
                        // 从当前in里面读取了数据，只不过还没有解析到对象，那就继续进行while循环，
                        //可能下次再调用，子类就可能解析出来。 如果用户解码器消费了ByteBuf，说明可以继续解码
                        continue;
                    }
                }
                // 能运行到这说明outSize >0,即已经解码出数据了, 可读索引不变，说明自定义的decode有问题，所以抛出一个异常
                // 如果用户解码器没有消费ByteBuf，但是却解码出了一个或者多个对象，这种行为被认为是非法的，需要抛出异常。
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                                    ".decode() did not read anything but decoded a message.");
                }
                //判断singleDecode属性是否为true，=true就跳出循环，用于特殊解码.  如果是单条消息解码器，则第一次解码完成之后就退出循环
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Exception cause) {
            throw new DecoderException(cause);
        }
    }


    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */
    //累积器
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        //参数1:具体的内存分配器    参数2:已累计接收的字节缓冲   参数3:本次读取的字节缓冲区
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            try {
                final ByteBuf buffer;
                // 检测当前的累积缓存区是否能够容纳新增加的ByteBuf,如果容量不够，则需要扩展ByteBuf，为了避免内存泄漏，手动去扩展
                if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                    || cumulation.refCnt() > 1 || cumulation.isReadOnly()) { //process 如果累积缓存区引用数超过1，也需要扩展
                    // Expand cumulation (by replace it) when either there is not more room in the buffer
                    // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                    // duplicate().retain() or if its read-only.
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes()); //process 扩充累积缓存区
                } else {
                    buffer = cumulation;
                }
                // 将新输入的字节写入到累积缓存区
                buffer.writeBytes(in);
                return buffer;
            } finally {
                // We must release in in all cases as otherwise it may produce a leak if writeBytes(...) throw
                // for whatever release (for example because of OutOfMemoryError)
                // 释放缓存区
                in.release();
            }
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            try {
                if (cumulation.refCnt() > 1) {
                    // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the
                    // user use slice().retain() or duplicate().retain().
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/2327
                    // - https://github.com/netty/netty/issues/1764
                    buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                    buffer.writeBytes(in);
                } else {
                    CompositeByteBuf composite;
                    if (cumulation instanceof CompositeByteBuf) {
                        composite = (CompositeByteBuf) cumulation;
                    } else {
                        composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                        composite.addComponent(true, cumulation);
                    }
                    composite.addComponent(true, in);
                    in = null;
                    buffer = composite;
                }
                return buffer;
            } finally {
                if (in != null) {
                    // We must release if the ownership was not transfered as otherwise it may produce a leak if
                    // writeBytes(...) throw for whatever release (for example because of OutOfMemoryError).
                    in.release();
                }
            }
        }
    };

    private static final byte STATE_INIT = 0;
    private static final byte STATE_CALLING_CHILD_DECODE = 1;
    private static final byte STATE_HANDLER_REMOVED_PENDING = 2;

    // 用来保存累计读取到的字节
    ByteBuf cumulation;
    private Cumulator cumulator = MERGE_CUMULATOR;
    // 设置为true后，单个解码器只会解码出一个结果
    private boolean singleDecode;
    // 解码结果为空
    private boolean decodeWasNull;
    // 是否是第一次读取数据
    private boolean first;
    /**
     * A bitmask where the bits are defined as
     * <ul>
     *     <li>{@link #STATE_INIT}</li>
     *     <li>{@link #STATE_CALLING_CHILD_DECODE}</li>
     *     <li>{@link #STATE_HANDLER_REMOVED_PENDING}</li>
     * </ul>
     */
    private byte decodeState = STATE_INIT;
    // 多少次读取后，丢弃数据 默认16次
    private int discardAfterReads = 16;
    // 已经累加了多少次数据了
    private int numReads;

    protected ByteToMessageDecoder() {
        ensureNotSharable();
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        if (discardAfterReads <= 0) {
            throw new IllegalArgumentException("discardAfterReads must be > 0");
        }
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        //是否正在解码中
        if (decodeState == STATE_CALLING_CHILD_DECODE) {
            //正在解码中就把状态标志为STATE_HANDLER_REMOVED_PENDING，表示要销毁数据，等解码线程解完码后销毁数据，自己直接返回
            decodeState = STATE_HANDLER_REMOVED_PENDING;
            return;
        }
        ByteBuf buf = cumulation;
        //累加区有数据
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            //GC回收
            cumulation = null;
            //缓冲区还有数据
            int readable = buf.readableBytes();
            if (readable > 0) {
                //把缓冲区的数据全部读取出来，发布channelread事件，释放资源
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                ctx.fireChannelRead(bytes);
            } else {
                buf.release();
            }
            //读取次数置为0，发布channelreadComplete事件
            numReads = 0;
            ctx.fireChannelReadComplete();
        }
        //空方法，子类可以覆盖
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }



    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (decodeWasNull) {
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            decodeLast(ctx, cumulation, out);
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }


    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    //process 这个方法是唯一的一个需要自己实现的抽象方法，作用是将ByteBuf数据解码成其他形式的数据。
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error occurs
     */
    final void decodeRemovalReentryProtection(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        //设置解码器正在解码状态，防止解码过程中另一个线程调用 handlerRemoved(ctx)销毁数据
        decodeState = STATE_CALLING_CHILD_DECODE; //  记录解码状态  decodeState默认是0，赋值为1
        try {
            //抽象方法, 子类具体实现解码逻辑
            decode(ctx, in, out);
        } finally {
            //decodeState == STATE_HANDLER_REMOVED_PENDING 表示在解码过程中，有另外的线程把ctx移除了，这是需要由当前线程调用handlerRemoved(ctx)方法来完成数据销毁
            boolean removePending = decodeState == STATE_HANDLER_REMOVED_PENDING;
            decodeState = STATE_INIT;
            if (removePending) {
                handlerRemoved(ctx);
            }
        }
    }

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    //process 实际上调用的是decode(...)。
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decodeRemovalReentryProtection(ctx, in, out);
        }
    }

    //process 每次扩展的时候，都是产生一个新的累积缓存区，这里主要是确保每一次通道读，所涉及的缓存区不是同一个，这样减少释放跟踪的难度，避免内存泄露。
    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        ByteBuf oldCumulation = cumulation;
        //process 增加容量分配新的缓冲区
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        //process 写入旧数据
        cumulation.writeBytes(oldCumulation);
        //process  写入完成后释放旧的缓冲区
        oldCumulation.release();
        return cumulation;
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
