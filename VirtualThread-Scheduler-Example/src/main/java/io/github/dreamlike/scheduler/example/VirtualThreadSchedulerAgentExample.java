package io.github.dreamlike.scheduler.example;

import io.github.dreamlike.CustomerVirtualThreadScheduler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

public final class VirtualThreadSchedulerAgentExample {

    public static void main(String[] args) throws ClassNotFoundException {
        MultiThreadIoEventLoopGroup eventLoop = new MultiThreadIoEventLoopGroup(1, KQueueIoHandler.newFactory());
        nettyServer(eventLoop);
        Thread thread = CustomerVirtualThreadScheduler.newThread(
                CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop),
                () -> ScopedValue.where(CustomerVirtualThreadPoller.currentIoEventLoop, eventLoop.next())
                        .run(() -> {
                            try {
                                Socket socket = new Socket();
                                socket.connect(new InetSocketAddress("127.0.0.1", 4399));
                                InputStream inputStream = socket.getInputStream();
                                byte[] buffer = new byte[1024];
                                int res = inputStream.read(buffer);
                                String s = new String(buffer, 0, res, Charset.defaultCharset());
                                System.out.println("socket recv:" + s);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
        );
        thread.start();

    }

    public static void nettyServer(MultiThreadIoEventLoopGroup eventLoop) {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(eventLoop, eventLoop)
                .channel(KQueueServerSocketChannel.class)
                .childHandler(new SimpleChannelInboundHandler<>() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                        ctx.writeAndFlush(ByteBufUtil.writeUtf8(ctx.alloc(), "Hello World!"));
                    }

                    @Override
                    protected void channelRead0(ChannelHandlerContext channelHandlerContext, Object o) throws Exception {

                    }
                })
                .bind(4399)
                .awaitUninterruptibly();
    }
}
