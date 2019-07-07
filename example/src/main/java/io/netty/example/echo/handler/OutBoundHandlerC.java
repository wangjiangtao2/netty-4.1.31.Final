/*
 * 文件名：InBoundHandlerC.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2019年03月18日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.example.echo.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;

/**
 * Description:
 * 描述
 *
 * @author Taojinsen
 * version 1.0
 * date: 2019-03-18 13:04:04
 * see InBoundHandlerC
 */
public class OutBoundHandlerC extends ChannelOutboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("OutBoundHandlerC.exceptionCaught() " + cause.getMessage());
        ctx.fireExceptionCaught(cause);
    }
}
