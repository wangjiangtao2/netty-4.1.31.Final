/*
 * 文件名：AuthHandler.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2019年03月18日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.example.echo.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Description:
 * 描述
 *
 * @author Taojinsen
 * version 1.0
 * date: 2019-03-18 11:37:37
 * see AuthHandler
 */
public class AuthHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        if(pass(msg)) {
            ctx.pipeline().remove(this);
        } else {
            ctx.close();
        }
    }

    private boolean pass(ByteBuf password) {
        return true;
    }
}
