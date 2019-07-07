/*
 * 文件名：ServerHandler.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2018年11月12日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.myTest;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Description:
 * 描述
 * author Taojinsen
 * version 1.0
 * date: 2018-11-12 14:29:29
 * see ServerHandler
 */
@Slf4j
public class ServerHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        log.info("handlerAdded");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("channelRead[{}]", msg.toString());
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        log.info("channelRegistered");
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("channelActive");
    }


}