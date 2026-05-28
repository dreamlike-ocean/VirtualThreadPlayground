package io.github.dreamlike.scheduler.uring;

import io.github.dreamlike.LoomSecretHelper;
import io.github.dreamlike.PollerMode;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

final class VirtualThreadCarrierEventExecutorGroup extends DefaultEventExecutorGroup {


    static {
        if (PollerMode.CURRENT_TYPE != PollerMode.PER_CARRIER_VIRTUAL_THREAD_POLLER) {
            throw new IllegalArgumentException("poller mode is not a valid polling mode,please use -Djdk.pollerMode=3");
        }
    }

    public VirtualThreadCarrierEventExecutorGroup(int nThreads) {
        super(nThreads);
    }

    public VirtualThreadCarrierEventExecutorGroup(int nThreads, ThreadFactory threadFactory) {
        super(nThreads, threadFactory);
    }

    @Override
    protected EventExecutor newChild(Executor executor, Object... args) throws Exception {
        return new VirtualThreadCarrierEventExecutor(this, executor, true, (Integer) args[0], (RejectedExecutionHandler) args[1]);
    }

    public static final class VirtualThreadCarrierEventExecutor extends SingleThreadEventExecutor {

        VirtualThreadCarrierEventExecutor(EventExecutorGroup parent, Executor executor, boolean addTaskWakesUp, int maxPendingTasks, RejectedExecutionHandler rejectedHandler) {
            super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedHandler);
        }

        @Override
        public boolean inEventLoop() {
            IoUringVirtualThreadEventLoop currentIoEventLoop = IoUringVirtualThreadEventLoop.CURRENT_DISPATCHER.get();
            return (currentIoEventLoop != null && currentIoEventLoop.carrierExecutor() == this) || inEventLoop(LoomSecretHelper.getCurrentCarrierThread());
        }

        @Override
        protected void run() {
            for (;;) {
                Runnable task = takeTask();
                if (task != null) {
                    runTask(task);
                    updateLastExecutionTime();
                }

                if (confirmShutdown()) {
                    break;
                }
            }
        }
    }
}
