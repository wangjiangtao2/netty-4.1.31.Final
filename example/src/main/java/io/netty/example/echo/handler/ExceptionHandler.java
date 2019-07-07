/*
 * 文件名：ExceptionHandler.java
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
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Description:
 * 描述
 *
 * @author Taojinsen
 * version 1.0
 * date: 2019-03-18 16:10:10
 * see ExceptionHandler
 */
public class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof BusinessException) {
            System.out.println("BusinessException" + cause.getMessage());
        }

    }
}
