/*
 * Copyright 2016 The Netty Project
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

public interface ChannelInboundInvoker {

    /**
     * A {@link Channel} was registered to its {@link EventLoop}.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelRegistered(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 当Channel注册到EventLoop上时
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelRegistered(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelRegistered();

    /**
     * A {@link Channel} was unregistered from its {@link EventLoop}.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelUnregistered(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 当Channel从EventLoop上取消注册,
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelUnregistered(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelUnregistered();

    /**
     * A {@link Channel} is active now, which means it is connected.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelActive(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 当Channel处理激活状态，意味着连接已经建立,
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelActive(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelActive();

    /**
     * A {@link Channel} is inactive now, which means it is closed.
     *
     * This will result in having the  {@link ChannelInboundHandler#channelInactive(ChannelHandlerContext)} method
     * called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 当Channel处理失效状态，意味着连接已经断开 ,
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelInactive(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelInactive();

    /**
     * A {@link Channel} received an {@link Throwable} in one of its inbound operations.
     *
     * This will result in having the  {@link ChannelInboundHandler#exceptionCaught(ChannelHandlerContext, Throwable)}
     * method  called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     *  在pipeline中某个一个入站(inbound)操作出现了异常
     * 	调用ChannelPipeline中下一个ChannelInboundHandler的exceptionCaught(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireExceptionCaught(Throwable cause);

    /**
     * A {@link Channel} received an user defined event.
     *
     * This will result in having the  {@link ChannelInboundHandler#userEventTriggered(ChannelHandlerContext, Object)}
     * method  called of the next  {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * 收到用户自定义的事件
     * 	调用ChannelPipeline中下一个ChannelInboundHandler的userEventTriggered(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireUserEventTriggered(Object event);

    /**
     * A {@link Channel} received a message.
     *
     * This will result in having the {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)}
     * method  called of the next {@link ChannelInboundHandler} contained in the  {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * Channel接收到了消息
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelRead(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelRead(Object msg);

    /**
     * Triggers an {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext)}
     * event to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelReadComplete(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelReadComplete();

    /**
     * Triggers an {@link ChannelInboundHandler#channelWritabilityChanged(ChannelHandlerContext)}
     * event to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     *
     * 调用ChannelPipeline中下一个ChannelInboundHandler的channelWritabilityChanged(ChannelHandlerContext)方法
     */
    ChannelInboundInvoker fireChannelWritabilityChanged();
}
