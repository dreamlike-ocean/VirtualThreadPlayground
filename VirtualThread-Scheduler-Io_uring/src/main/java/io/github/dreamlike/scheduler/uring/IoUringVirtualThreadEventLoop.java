package io.github.dreamlike.scheduler.uring;

import io.github.dreamlike.scheduler.netty.NettyVirtualIoEventLoop;
import io.netty.channel.IoEvent;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoRegistration;
import io.netty.channel.ManualIoEventLoop;
import io.netty.channel.uring.IoUringIoEvent;
import io.netty.channel.uring.IoUringIoHandle;
import io.netty.channel.uring.IoUringIoOps;
import io.netty.channel.uring.NativePeek;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.LockSupport;

class IoUringVirtualThreadEventLoop {

    static final InheritableThreadLocal<IoUringVirtualThreadEventLoop> CURRENT_DISPATCHER = new InheritableThreadLocal<>();
    private static final Thread.Builder.OfVirtual VIRTUAL_BUILDER = Thread.ofVirtual().name("iouring-netty-eventloop", 0);
    private final NettyVirtualIoEventLoop ioEventLoop;
    private final Thread.VirtualThreadTask eventLoopTask;
    private final EventExecutor carrierExecutor;
    private final IntObjectMap<Thread> fdMapping;
    private final IoRegistration ioRegistration;

    public IoUringVirtualThreadEventLoop(IoEventLoopGroup parentGroup, IoHandlerFactory ioHandlerFactory, EventExecutor carrierExecutor) {
        this.carrierExecutor = carrierExecutor;
        this.fdMapping = new IntObjectHashMap<>();
        this.ioEventLoop = new NettyVirtualIoEventLoop(parentGroup, ioHandlerFactory);
        this.eventLoopTask = IoUringVirtualThreadRuntime.getInstance().newThread(VIRTUAL_BUILDER, this, () -> {
            CURRENT_DISPATCHER.set(this);
            FastThreadLocalThread.runWithFastThreadLocal(() -> nettyLoop(ioEventLoop));
        });
        Thread thread = this.eventLoopTask.thread();
        ioEventLoop.setOwningThread(thread);
        thread.start();
        try {
            this.ioRegistration = ioEventLoop.register(new IoUringPoller()).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static void nettyLoop(NettyVirtualIoEventLoop ioEventLoop) {
        while (!ioEventLoop.isShutdown()) {
            ioEventLoop.run(0, 0);
        }
    }

    public IoEventLoop ioEventLoop() {
        return ioEventLoop;
    }

    Thread.VirtualThreadTask  eventLoopTask() {
        return eventLoopTask;
    }

    EventExecutor carrierExecutor() {
        return carrierExecutor;
    }

    final void startPoll(int fd, boolean read, long nanos) {
        Thread currentThread = Thread.currentThread();
        IoUringIoOps pollOps = createPollOps(fd, read);
        ioRegistration.submit(pollOps);
        fdMapping.put(fd, currentThread);
        if (nanos > 0) {
            LockSupport.parkNanos(nanos);
        } else {
            LockSupport.park();
        }
        Thread thread = fdMapping.remove(fd);
        if (thread != null) {
            //todo cancel
            return;
        }
    }

    private IoUringIoOps createPollOps(int fd, boolean read) {
        int mask = read ? NativePeek.POLLIN : NativePeek.POLLOUT;
        return new IoUringIoOps(NativePeek.IORING_OP_POLL_ADD, (byte) 0, (short) 0, fd, 0L, 0L, 0, mask, fd,
                (short) 0, (short) 0, 0, 0);
    }

    private class IoUringPoller implements IoUringIoHandle {

        @Override
        public void handle(IoRegistration registration, IoEvent ioEvent) {
            short fd = ((IoUringIoEvent) ioEvent).data();
            Thread thread = fdMapping.remove(fd);
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }

        @Override
        public void close() throws Exception {

        }
    }
}
