package io.github.dreamlike.scheduler.uring;

import io.github.dreamlike.AbstractVirtualThreadRuntime;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class IoUringVirtualThreadRuntime extends AbstractVirtualThreadRuntime {
    private static IoUringVirtualThreadRuntime instance;

    public IoUringVirtualThreadRuntime() {
        instance = this;
        VarHandle.storeStoreFence();
    }

    public static IoUringVirtualThreadRuntime getInstance() {
        return instance;
    }

    @Override
    public void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
        IoUringVirtualThreadEventLoop ioUringVirtualThreadEventLoop = IoUringVirtualThreadEventLoop.CURRENT_DISPATCHER.get();
        if (ioUringVirtualThreadEventLoop == null) {
            super.poll(fdVal, event, nanos, isOpen);
            return;
        }
        if (ioUringVirtualThreadEventLoop.eventLoopTask().thread() == Thread.currentThread()) {
            //如果你在eventloop上调用阻塞函数 那么为了防止死锁 那还是挂到jdk上去吧
            super.poll(fdVal, event, nanos, isOpen);
            return;
        }
        boolean readMode = event == POLLIN;
        ioUringVirtualThreadEventLoop.startPoll(fdVal,readMode, nanos);
    }

    @Override
    public void pollSelector(int fdVal, long nanos) throws IOException {
        poll(fdVal, POLLIN, nanos, null);
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        if (task.attachment() instanceof VirtualThreadContext(var virtualThreadEventLoop) && virtualThreadEventLoop != null) {
            virtualThreadEventLoop.carrierExecutor().execute(task);
            return;
        }
        IoUringVirtualThreadEventLoop eventLoop = IoUringVirtualThreadEventLoop.CURRENT_DISPATCHER.get();
        if (eventLoop != null) {
            task.attach(new VirtualThreadContext(eventLoop));
            eventLoop.carrierExecutor().execute(task);
        } else {
            super.onStart(task);
        }
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        if (task.attachment() instanceof VirtualThreadContext(var virtualThreadEventLoop) && virtualThreadEventLoop != null) {
            virtualThreadEventLoop.carrierExecutor().execute(task);
            return;
        }
        super.onContinue(task);
    }

    @Override
    public Future<?> schedule(Runnable task, long delay, TimeUnit unit) {
        IoUringVirtualThreadEventLoop ioUringVirtualThreadEventLoop = IoUringVirtualThreadEventLoop.CURRENT_DISPATCHER.get();
        if (ioUringVirtualThreadEventLoop == null) {
            return jdkScheduler().schedule(task, delay, unit);
        }
        return ioUringVirtualThreadEventLoop.carrierExecutor().schedule(task, delay, unit);
    }

    public void waitJdkPollIn(int fd, long nanos) {
        try {
            jdkVirtualThreadPoller().poll(fd, AbstractVirtualThreadRuntime.POLLIN, nanos, () -> true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Thread.VirtualThreadTask newThread(Thread.Builder.OfVirtual vtBuilder, IoUringVirtualThreadEventLoop ioUringVirtualThreadEventLoop, Runnable task) {
        Thread.VirtualThreadTask virtualThreadTask = this.newThread(vtBuilder, (Thread) null, task);
        virtualThreadTask.attach(new VirtualThreadContext(ioUringVirtualThreadEventLoop));
        return virtualThreadTask;
    }

    @Override
    public Thread.VirtualThreadTask newThread(Thread.Builder.OfVirtual builder, Thread preferredCarrier, Runnable task) {
        Thread.VirtualThreadTask virtualThreadTask = super.newThread(builder, preferredCarrier, task);
        virtualThreadTask.attach(new VirtualThreadContext(IoUringVirtualThreadEventLoop.CURRENT_DISPATCHER.get()));
        return virtualThreadTask;
    }

    private record VirtualThreadContext(IoUringVirtualThreadEventLoop preferredCarrier) {
    }
}
