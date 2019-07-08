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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeMap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;


/**
 * A nexus to a network socket or a component which is capable of I/O
 * operations such as read, write, connect, and bind.
 * <p>
 * A channel provides a user:
 * <ul>
 * <li>the current state of the channel (e.g. is it open? is it connected?),</li>
 * <li>the {@linkplain ChannelConfig configuration parameters} of the channel (e.g. receive buffer size),</li>
 * <li>the I/O operations that the channel supports (e.g. read, write, connect, and bind), and</li>
 * <li>the {@link ChannelPipeline} which handles all I/O events and requests
 *     associated with the channel.</li>
 * </ul>
 *
 * <h3>All I/O operations are asynchronous.</h3>
 * <p>
 * All I/O operations in Netty are asynchronous.  It means any I/O calls will
 * return immediately with no guarantee that the requested I/O operation has
 * been completed at the end of the call.  Instead, you will be returned with
 * a {@link ChannelFuture} instance which will notify you when the requested I/O
 * operation has succeeded, failed, or canceled.
 *
 * <h3>Channels are hierarchical</h3>
 * <p>
 * A {@link Channel} can have a {@linkplain #parent() parent} depending on
 * how it was created.  For instance, a {@link SocketChannel}, that was accepted
 * by {@link ServerSocketChannel}, will return the {@link ServerSocketChannel}
 * as its parent on {@link #parent()}.
 * <p>
 * The semantics of the hierarchical structure depends on the transport
 * implementation where the {@link Channel} belongs to.  For example, you could
 * write a new {@link Channel} implementation that creates the sub-channels that
 * share one socket connection, as <a href="http://beepcore.org/">BEEP</a> and
 * <a href="http://en.wikipedia.org/wiki/Secure_Shell">SSH</a> do.
 *
 * <h3>Downcast to access transport-specific operations</h3>
 * <p>
 * Some transports exposes additional operations that is specific to the
 * transport.  Down-cast the {@link Channel} to sub-type to invoke such
 * operations.  For example, with the old I/O datagram transport, multicast
 * join / leave operations are provided by {@link DatagramChannel}.
 *
 * <h3>Release resources</h3>
 * <p>
 * It is important to call {@link #close()} or {@link #close(ChannelPromise)} to release all
 * resources once you are done with the {@link Channel}. This ensures all resources are
 * released in a proper way, i.e. filehandles.
 *
 * AttributeMap接口为Channel提供了绑定其他属性的能力
 * Comparable接口定义了Channel是可以比较的。
 * ChannelOutboundInvoker接口主要提供了与网络链路相关的一些操作以及读写相关的操作，并统一返回了ChannelFuture对象，便于我们可以监听这些操作是否成功。
 *
 * AbstractChannel
 *      AbstractNioChannel
 *          AbstractNioByteChannel
 *              NioSocketChannel
 *          AbstractNioMessageChannel
 *              NioServerSocketChannel
 * Unsafe
 *      AbstractUnsafe
 *          AbstractNioUnsafe
 *              NioByteUnsafe
 *              NioMessageUnsafe
 *
 * Netty网络操作抽象类，包括但不限于网络的读、写、客户端发起连接、主动关闭连接、链路关闭、获取通信双方的网络地址等。
 * 采用Facade模式进行统一封装，将网络I/O操作、网络I/O相关联的其他操作封装起来，统一对外提供
 */
public interface Channel extends AttributeMap, ChannelOutboundInvoker, Comparable<Channel> {

    /**
     * Returns the globally unique identifier of this {@link Channel}.
     *
     * Channel标识的id，他返回ChannelId对象，ChannelId是Channel的唯一标识
     * @see  DefaultChannelId#DefaultChannelId()
     *    生成策略:
     *      机器的MAC地址(EUI-48或者EUI-64)等可以代表全局唯一的信息；
     *      当前的进程id；
     *      当前系统时间的毫秒--System.currentTimeMillis();
     *      当前系统时间纳秒数--System.nanoTime();
     *      32位的随机整型数
     *      32位自增的序列数
     */
    ChannelId id();

    /**
     * Return the {@link EventLoop} this {@link Channel} was registered to.
     *
     * Channel需要注册到EveneLoop的多路复用器上，用于处理I/O事件，通过eventLoop()方法可以获取到Channel注册的EventLoop
     * EventLoop本质上就是处理网络读写事件的Reactor线程，在Netty中，它不仅用来处理网络事件，也可以用来执行定时任务和用户自定义NioTask等任务
     */
    EventLoop eventLoop();

    /**
     * Returns the parent of this channel.
     * @return the parent channel.
     *         {@code null} if this channel does not have a parent channel.
     *
     * 对于服务端Channel而言，它的父Channel为空；对于客户端Channel，它的父Channel就是创建它的的ServerSocketChannel
     */
    Channel parent();

    /**
     * Returns the configuration of this channel.
     *
     * 获取当前Channel的配置信息，例如 CONNECT_TIMEOUT_MILLIS
     */
    ChannelConfig config();

    /**
     * Returns {@code true} if the {@link Channel} is open and may get active later
     * 判断当前Channel是否已经打开
     */
    boolean isOpen();

    /**
     * Returns {@code true} if the {@link Channel} is registered with an {@link EventLoop}.
     * 判断当前Channel是否已经注册到EventLoop
     */
    boolean isRegistered();

    /**
     * Return {@code true} if the {@link Channel} is active and so connected.
     *  判断channel是否已经处于激活状态。
     *  激活的意义取决于底层的传输类型。例如，一个Socket传输一旦连接到了远程节点便是活动的，而一个Datagram传输一旦被打开便是活动的
     */
    boolean isActive();

    /**
     * Return the {@link ChannelMetadata} of the {@link Channel} which describe the nature of the {@link Channel}.
     *
     * 熟悉TCP协议的读者可能知道，当创建Socket的时候需要指定TCP参数，例如接收和发送的TCP缓冲区大小，TCP的超时时间。是否重用地址等。
     * 在Netty中，每个Channel对应一个物理链接，每个连接都有自己的TCP参数配置。
     * 所以，Channel会聚合一个ChannelMetadata用来对TCP参数提供元数据描述信息，通过metadata()方法就可以获取当前Channel的TCP参数配置。
     */
    ChannelMetadata metadata();

    /**
     * Returns the local address where this channel is bound to.  The returned
     * {@link SocketAddress} is supposed to be down-cast into more concrete
     * type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * @return the local address of this channel.
     *         {@code null} if this channel is not bound.
     * 获取当前Channel的本地绑定地址
     */
    SocketAddress localAddress();

    /**
     * Returns the remote address where this channel is connected to.  The
     * returned {@link SocketAddress} is supposed to be down-cast into more
     * concrete type such as {@link InetSocketAddress} to retrieve the detailed
     * information.
     *
     * @return the remote address of this channel.
     *         {@code null} if this channel is not connected.
     *         If this channel is not connected but it can receive messages
     *         from arbitrary remote addresses (e.g. {@link DatagramChannel},
     *         use {@link DatagramPacket#recipient()} to determine
     *         the origination of the received message as this method will
     *         return {@code null}.
     *
     * 获取当前Channel通信的远程Socket地址
     */
    SocketAddress remoteAddress();

    /**
     * Returns the {@link ChannelFuture} which will be notified when this
     * channel is closed.  This method always returns the same future instance.
     */
    ChannelFuture closeFuture();

    /**
     * Returns {@code true} if and only if the I/O thread will perform the
     * requested write operation immediately.  Any write requests made when
     * this method returns {@code false} are queued until the I/O thread is
     * ready to process the queued write requests.
     */
    boolean isWritable();

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     *
     * 获取剩余可写空间
     */
    long bytesBeforeUnwritable();

    /**
     * Get how many bytes must be drained from underlying buffers until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     *
     * 还需处理多少字节才可写
     */
    long bytesBeforeWritable();

    /**
     * Returns an <em>internal-use-only</em> object that provides unsafe operations.
     *
     * 服务端channel:NioMessageUnsafe
     * 客户端channel:NioByteUnsafe
     */
    Unsafe unsafe();

    /**
     * Return the assigned {@link ChannelPipeline}.
     *
     * 返回channel分配的 ChannelPipeline
     * @see  DefaultChannelPipeline
     */
    ChannelPipeline pipeline();

    /**
     * Return the assigned {@link ByteBufAllocator} which will be used to allocate {@link ByteBuf}s.
     * ByteBuf 分配器
     */
    ByteBufAllocator alloc();

    /**
     * 从当前的Channel中读取数据到第一个inbound缓冲区中，如果数据被成功读取，触发 ChannelHander.channelRead(ChannelHandlerContext,Object)事件,
     * 读取操作API调用完成之后，紧接着会触发channelHandler.channelReadComplete(ChannelHandlerContext)事件,这样业务的ChannelHander可以决定是否需要继续读取数据。
     * 如果已经有读操作请求被挂起，则后续的读操作会被忽略。
     */
    @Override
    Channel read();

    @Override
    Channel flush();




    /**
     * <em>Unsafe</em> operations that should <em>never</em> be called from user-code. These methods
     * are only provided to implement the actual transport, and must be invoked from an I/O thread except for the
     * following methods:
     * <ul>
     *   <li>{@link #localAddress()}</li>
     *   <li>{@link #remoteAddress()}</li>
     *   <li>{@link #closeForcibly()}</li>
     *   <li>{@link #register(EventLoop, ChannelPromise)}</li>
     *   <li>{@link #deregister(ChannelPromise)}</li>
     *   <li>{@link #voidPromise()}</li>
     * </ul>
     *
     * io.netty.channel.AbstractChannel.AbstractUnsafe
     *
     */
    interface Unsafe {

        /**
         * Return the assigned {@link RecvByteBufAllocator.Handle} which will be used to allocate {@link ByteBuf}'s when
         * receiving data.
         */
        RecvByteBufAllocator.Handle recvBufAllocHandle();

        /**
         * Return the {@link SocketAddress} to which is bound local or
         * {@code null} if none.
         * 返回绑定到本地的SocketAddress，没有则返回null
         */
        SocketAddress localAddress();

        /**
         * Return the {@link SocketAddress} to which is bound remote or
         * {@code null} if none is bound yet.
         * 返回绑定到远程的SocketAddress，如果还没有绑定，则返回null。
         */
        SocketAddress remoteAddress();

        /**
         * Register the {@link Channel} of the {@link ChannelPromise} and notify
         * the {@link ChannelFuture} once the registration was complete.
         *
         * 注册Channel到多路复用器，并在注册完成后通知ChannelFuture。一旦ChannelPromise成功，就可以在ChannelHandler内向EventLoop提交新任务。 否则，任务可能被拒绝也可能不会被拒绝。
         */
        void register(EventLoop eventLoop, ChannelPromise promise);

        /**
         * Bind the {@link SocketAddress} to the {@link Channel} of the {@link ChannelPromise} and notify
         * it once its done.
         *
         * 绑定指定的本地地址localAddress到当前channel，完成后通知ChannelFuture
         *
         * 该方法会级联触发ChannelHandler.bind(ChannelHandlerContext, SocketAddress，ChannelPromise)事件
         * ChannelPromise用于写入操作结果
         */
        void bind(SocketAddress localAddress, ChannelPromise promise);

        /**
         * Connect the {@link Channel} of the given {@link ChannelFuture} with the given remote {@link SocketAddress}.
         * If a specific local {@link SocketAddress} should be used it need to be given as argument. Otherwise just
         * pass {@code null} to it.
         *
         * The {@link ChannelPromise} will get notified once the connect operation was complete.
         *
         * 先绑定指定的本地地址localAddress，然后再连接服务端，一旦操作完成，通知ChannelFuture
         *
         *  客户端使用指定的服务端地址remoteAddress发起连接请求，
         *      如果连接因为应答超时而失败，ChannelFuture中的操作结果就是ConnectTimeoutException异常；
         *      如果连接被拒绝，操作结果为ConnectException。
         * 该方法会级联触发CChannelHandler.connect(ChannelHandlerContext, SocketAddress，SocketAddress，ChannelPromise)事件
         * ChannelPromise参数用于写入操作结果
         */
        void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

        /**
         * Disconnect the {@link Channel} of the {@link ChannelFuture} and notify the {@link ChannelPromise} once the
         * operation was complete.
         *
         * 断开Channel的连接，一旦完成，通知ChannelFuture
         * 请求断开与远程通信对端的连接并使用ChannelPromise来获取操作结果的通知消息
         * 该方法会级联触发CChannelHandler.disconnect(ChannelHandlerContext, ChannelPromise)事件
         */
        void disconnect(ChannelPromise promise);

        /**
         * Close the {@link Channel} of the {@link ChannelPromise} and notify the {@link ChannelPromise} once the
         * operation was complete.
         *
         * 关闭Channel的连接，一旦完成，通知ChannelFuture
         *
         * 主动关闭当前连接，通过ChannelPormise设置操作结果并进行结果通知，无论操作是否成功，都可以通过ChannelPromise获取操作结果
         * 该操作会级联触发ChannelPipeline中所有ChannelHandler的ChannelHandler.close(ChannelHandlerContext, ChannelPromise)事件
         */
        void close(ChannelPromise promise);

        /**
         * Closes the {@link Channel} immediately without firing any events.  Probably only useful
         * when registration attempt failed.
         *
         * 强制立即关闭连接
         */
        void closeForcibly();

        /**
         * Deregister the {@link Channel} of the {@link ChannelPromise} from {@link EventLoop} and notify the
         * {@link ChannelPromise} once the operation was complete.
         * 注销channel先前分配的EventLoop，完成后通知ChannelFuture
         */
        void deregister(ChannelPromise promise);

        /**
         * Schedules a read operation that fills the inbound buffer of the first {@link ChannelInboundHandler} in the
         * {@link ChannelPipeline}.  If there's already a pending read operation, this method does nothing.
         *
         * 设置网络操作位为读用于读取消息
         *
         * 调度填充ChannelPipeline中第一个ChannelInboundHandler的入站缓冲区的读取操作。 如果已经有待处理的读取操作，此方法什么都不做。
         */
        void beginRead();

        /**
         * Schedules a write operation.
         *
         * 将当前的msg通过ChannelPipeline写入到目标Channel中。
         * 注意，write操作只是将消息存入到消息发送环形数组中，并没有真正被发送，只有调用flush操作才会被写入到Channel中，发送给对象。
         * promise携带了ChannelPromise参数负责设置写入操作的结果
         *
         * 将消息发送缓冲数组中，完成后通知ChannelFuture
         */
        void write(Object msg, ChannelPromise promise);

        /**
         * Flush out all write operations scheduled via {@link #write(Object, ChannelPromise)}.
         *
         * 将之前写入到发送环形数组中的消息全部写入到目标Channel中，发送给通信对方
         */
        void flush();

        /**
         * Return a special ChannelPromise which can be reused and passed to the operations in {@link Unsafe}.
         * It will never be notified of a success or error and so is only a placeholder for operations
         * that take a {@link ChannelPromise} as argument but for which you not want to get notified.
         * 返回一个特殊的可重用和传递的ChannelPromise，它不用于操作成功或者失败的通知器，仅仅作为一个容器被使用
         */
        ChannelPromise voidPromise();

        /**
         * Returns the {@link ChannelOutboundBuffer} of the {@link Channel} where the pending write requests are stored.
         * 返回消息发送缓冲区
         */
        ChannelOutboundBuffer outboundBuffer();
    }
}
