package io.github.dreamlike.scheduler.uring;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public class IoUringVirtualThreadEventLoopGroup extends MultiThreadIoEventLoopGroup {
    private final VirtualThreadCarrierEventExecutorGroup virtualThreadCarrierEventExecutorGroup;

    public IoUringVirtualThreadEventLoopGroup(int nThreads, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory) {
        super(nThreads, ioHandlerFactory);
        this.virtualThreadCarrierEventExecutorGroup = new VirtualThreadCarrierEventExecutorGroup(nThreads, threadFactory);
    }

    @Override
    protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory, Object... args) {
        EventExecutor carrierEventExecutor = virtualThreadCarrierEventExecutorGroup.next();
        IoUringVirtualThreadEventLoop ioUringVirtualThreadEventLoop = new IoUringVirtualThreadEventLoop(this, ioHandlerFactory, carrierEventExecutor);
        return ioUringVirtualThreadEventLoop.ioEventLoop();
    }

}
