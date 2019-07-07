/*
 * 文件名：BusinessException.java
 * 版权：Copyright by www.newlixon.com/
 * 描述：
 * 修改人：Taojinsen
 * 修改时间：2019年03月18日
 * 跟踪单号：
 * 修改单号：
 * 修改内容：
 */
package io.netty.example.echo.handler;

/**
 * Description:
 * 描述
 *
 * @author Taojinsen
 * version 1.0
 * date: 2019-03-18 16:13:13
 * see BusinessException
 */
public class BusinessException extends Exception {

    public BusinessException() {
        super();
    }

    public BusinessException(String msg) {
        super(msg);
    }
}
