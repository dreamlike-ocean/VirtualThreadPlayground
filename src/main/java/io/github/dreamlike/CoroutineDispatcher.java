package io.github.dreamlike;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public record CoroutineDispatcher(Executor defaultExecutor) implements Thread.VirtualThreadScheduler {

    private static final VarHandle VIRTUAL_THREAD_TASK_VAR_HANDLER;

    static {
        try {
            VIRTUAL_THREAD_TASK_VAR_HANDLER = getVirtualThreadTaskVarHandler();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static VarHandle getVirtualThreadTaskVarHandler() throws NoSuchFieldException, IllegalAccessException {
        MethodHandles.Lookup lookup = OpenDoorHelper.LOOKUP;
        Class<? extends Thread> vtClass = Thread.ofVirtual().unstarted(() -> {}).getClass();
        return lookup.findVarHandle(vtClass, "runContinuation", Thread.VirtualThreadTask.class);
    }


    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        DispatcherContext dispatcherContext = new DispatcherContext();
        dispatcherContext.currentExecutor.set(defaultExecutor);
        task.attach(dispatcherContext);
        defaultExecutor.execute(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        DispatcherContext dispatcherContext = (DispatcherContext) task.attachment();
        dispatcherContext.currentExecutor.get()
                .execute(task);
    }

    public static void switchExecutor(Executor executor, Callable<?> task) {
        DispatcherContext dispatcherContext = (DispatcherContext) getCurrentTask().attachment();
        Executor prevExecutor = dispatcherContext.currentExecutor.getAndSet(executor);
        Thread.yield();
        try {
            task.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            dispatcherContext.currentExecutor.set(prevExecutor);
            Thread.yield();
        }
    }

    public static void switchExecutor(Executor executor, Runnable task) {
        DispatcherContext dispatcherContext = (DispatcherContext) getCurrentTask().attachment();
        Executor prevExecutor = dispatcherContext.currentExecutor.getAndSet(executor);
        // yield重新切换投递任务线程池
        Thread.yield();
        try {
            task.run();
        } finally {
            dispatcherContext.currentExecutor.set(prevExecutor);
            // yield重新切换投递任务线程池回去
            Thread.yield();
        }
    }

    private static Thread.VirtualThreadTask getCurrentTask() {
        Thread currentThread = Thread.currentThread();
        if (!currentThread.isVirtual()) {
            throw new IllegalStateException("current thread is not virtual thread");
        }
        return (Thread.VirtualThreadTask) VIRTUAL_THREAD_TASK_VAR_HANDLER.get(currentThread);
    }

    private static class DispatcherContext {
        private final AtomicReference<Executor> currentExecutor = new AtomicReference<>();
    }
}
