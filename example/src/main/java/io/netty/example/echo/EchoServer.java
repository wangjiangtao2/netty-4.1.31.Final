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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AttributeKey;

/**
 * Echoes back any received data from a client.
 */
@SuppressWarnings("all")
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8008"
    ));

    public static void main(String[] args) throws Exception {

        // Configure SSL.
        final SslContext sslCtx;
      /*  if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(),
                    ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }*/

        // 创建两个线程组,专门用于网络事件的处理，Reactor线程组
        // 用来接收客户端的连接， bossGroup线程组实际就是Acceptor线程池，
        // 负责处理客户端的TCP连接请求，如果系统只有一个服务端端口需要监听，则建议bossGroup线程组线程数设置为1。
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        // 用来进行SocketChannel的网络读写
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            // 创建辅助启动类ServerBootstrap，并设置相关配置：
            ServerBootstrap b = new ServerBootstrap();
            // 设置处理Accept事件和读写操作的事件循环组
            b.group(bossGroup, workerGroup)
                    // 配置Channel类型
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    // 设置建立连接后的客户端通道的选项
                    .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    // channel属性，便于保存用户自定义数据
                    .attr(AttributeKey.newInstance("userId"), "60293")
                    .handler(new LoggingHandler(LogLevel.INFO))
                    // 设置子处理器，主要是用户的自定义处理器，用于处理IO网络事件
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            /*if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }*/
                            p.addLast(new DelimiterBasedFrameDecoder(5,
                                    Unpooled.copiedBuffer(",".getBytes())));
                            //p.addLast(new StringDecoder());
                    /* p.addLast(new InBoundHandlerA());
                     p.addLast(new InBoundHandlerB());
                     p.addLast(new InBoundHandlerC());
                     p.addLast(new OutBoundHandlerA());
                     p.addLast(new OutBoundHandlerB());
                     p.addLast(new OutBoundHandlerC());
                     p.addLast(new ExceptionHandler());*/
                            //p.addLast(new LoggingHandler(LogLevel.INFO));
                            p.addLast(serverHandler);
                        }
                    });

            // Start the server.
            //绑定端口，同步等待成功, 调用bind()方法绑定端口，sync()会阻塞等待处理请求。这是因为bind()
            // 方法是一个异步过程，会立即返回一个ChannelFuture对象，调用sync()会等待执行完成
            ChannelFuture f = b.bind(PORT).sync();
            //等待服务端监听端口关闭 Wait until the server socket is closed.
            f.channel().closeFuture().sync();
        } finally {
            ////优雅退出，释放线程池资源 Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
