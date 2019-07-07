/*
 * 文件名：Test.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2018年11月12日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.myTest;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import lombok.extern.log4j.Log4j;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Description:
 * 描述
 * author Taojinsen
 * version 1.0
 * date: 2018-11-12 14:28:28
 * see Test
 */
@Slf4j
public class Test {

    public static void main(String[] args) {

        EventLoopGroup parentGroup = new NioEventLoopGroup();
        EventLoopGroup childGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(parentGroup, childGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY,true)
                    .option(ChannelOption.SO_BACKLOG,1024)
                    .attr(AttributeKey.newInstance("serverAttr"), "serverAttrValue")
                    .handler(new ServerHandler())
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childAttr(AttributeKey.newInstance("childAttr"),"childAttrValue")
                    .childHandler(new ServerInitial());

            ChannelFuture future = b.bind("localhost", 8088).sync();
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            parentGroup.shutdownGracefully();
            childGroup.shutdownGracefully();
        }
    }
}
