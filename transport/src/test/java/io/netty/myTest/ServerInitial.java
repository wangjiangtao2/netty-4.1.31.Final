/*
 * 文件名：ServerInitial.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2018年11月12日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.myTest;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * Description:
 * 描述
 * author Taojinsen
 * version 1.0
 * date: 2018-11-12 14:29:29
 * see ServerInitial
 */
@Slf4j
public class ServerInitial extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
        ch.pipeline().addLast(new ChannelOutboundHandlerAdapter());
    }
}
