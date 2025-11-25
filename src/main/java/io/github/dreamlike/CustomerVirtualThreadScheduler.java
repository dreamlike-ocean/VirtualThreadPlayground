package io.github.dreamlike;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;


// 默认继承线程
// 1. platform -> Thread.startVirtualThread => jdkScheduler  ✅
// 2. platform(with propagateExecutor)/Thread.startVirtualThread -> Thread.startVirtualThread => executor ✅
// 3. vt(with executor) -> Thread.startVirtualThread => executor ✅
// 4. vt(with executor) -> Thread.startVirtualThread -> Thread.startVirtualThread => executor ✅
// 如果是来自于CustomerVirtualThreadScheduler的那么其DISPATCHER_EXECUTOR_SCOPED_VALUE肯定是有值的
public class CustomerVirtualThreadScheduler implements Thread.VirtualThreadScheduler {

    private static final DispatcherContext DUMMY = new EmptyContext(null);
    private static final ScopedValue<DispatcherContext> DISPATCHER_EXECUTOR_SCOPED_VALUE = ScopedValue.newInstance();
    public static CustomerVirtualThreadScheduler INSTANCE;
    private static Thread.VirtualThreadScheduler jdkBuildInScheduler;

    public CustomerVirtualThreadScheduler(Thread.VirtualThreadScheduler defaultScheduler) {
        jdkBuildInScheduler = defaultScheduler;
        INSTANCE = this;
        VarHandle.storeStoreFence();
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        if (task.attachment() instanceof DispatcherContext dispatcherContext) {
            if (!dispatcherContext.executor().execute(task)) {
                jdkBuildInScheduler.onStart(task);
            }
            return;
        }
        DispatcherContext parentContext = getCurrentContext();
        if (parentContext != null) {
            DispatcherContext newContext = parentContext.inheritContext(task.thread());
            task.attach(newContext);
            if (newContext.executor().execute(task)) {
                return;
            }
        }
        // 1.找不到任何父级执行器，那么就使用jdk的调度器
        // 2.或者父级的调度器无法投递 那么就使用jdk的调度器
        task.attach(null);
        jdkBuildInScheduler.onStart(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        if (task.attachment() instanceof DispatcherContext dispatcherContext && dispatcherContext.executor().execute(task)) {
            return;
        }
        jdkBuildInScheduler.onContinue(task);
    }

    public static Thread newThread(AwareShutdownExecutor executor, Runnable runnable) {
        DynamicDispatcherContext newContext = new DynamicDispatcherContext(getCurrentContext(), null, executor);
        Thread thread = Thread.VirtualThreadScheduler.newThread(() -> ScopedValue.where(DISPATCHER_EXECUTOR_SCOPED_VALUE, newContext).run(runnable), newContext);
        newContext.currentThread = thread;
        return thread;
    }

    public static void propagateExecutor(AwareShutdownExecutor executor, Runnable runnable) {
        propagateExecutor(executor, DispatchType.DYNAMIC, runnable);
    }

    public static void propagateExecutor(AwareShutdownExecutor executor, DispatchType type, Runnable runnable) {
        DispatcherContext parentContext = getCurrentContext();
        Thread currentThread = Thread.currentThread();
        DispatcherContext newContext = switch (type) {
            case DYNAMIC -> new DynamicDispatcherContext(parentContext, currentThread, executor);
            case PINNING -> new PinningContext(parentContext, currentThread, executor);
        };
        ScopedValue.where(DISPATCHER_EXECUTOR_SCOPED_VALUE, newContext)
                .run(runnable);
    }

    public static <T> T switchExecutor(AwareShutdownExecutor executor, Callable<T> task) {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("current thread is not virtual thread");
        }
        Object dispatcherContext = LoomSecretHelper.getCurrentTask().attachment();
        if (!(dispatcherContext instanceof DynamicDispatcherContext dynamicDispatcherExecutor)) {
            throw new IllegalStateException("current thread is not from dynamic dispatcher");
        }
        AwareShutdownExecutor prevExecutor = dynamicDispatcherExecutor.switchExecutor(executor);
        Thread.yield();
        try {
            return task.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            dynamicDispatcherExecutor.switchExecutor(prevExecutor);
            Thread.yield();
        }
    }

    public enum DispatchType {
        DYNAMIC,
        PINNING
    }

    public static List<Thread> traceThreads() {
        DispatcherContext currentContext = getCurrentContext();
        ArrayList<Thread> threads = new ArrayList<>();
        while (currentContext != null) {
            Thread currentThread = currentContext.currentThread;
            threads.add(currentThread);
            currentContext = currentContext.parent;
        }
        return threads.reversed();
    }

    private static DispatcherContext getCurrentContext() {
        DispatcherContext dispatcherContext = DISPATCHER_EXECUTOR_SCOPED_VALUE.orElse(DUMMY);
        if (dispatcherContext != DUMMY) {
            return dispatcherContext;
        }
        if (Thread.currentThread().isVirtual() && LoomSecretHelper.getCurrentTask().attachment() instanceof DispatcherContext parentContext) {
            return parentContext;
        }
        return null;
    }

    private sealed static abstract class DispatcherContext permits DynamicDispatcherContext, EmptyContext, PinningContext {
        protected final DispatcherContext parent;
        protected Thread currentThread;

        private DispatcherContext(DispatcherContext parent, Thread currentThread) {
            this.parent = parent;
            this.currentThread = currentThread;
        }

        abstract AwareShutdownExecutor executor();

        abstract DispatcherContext inheritContext(Thread currentThread);
    }

    private final static class PinningContext extends DispatcherContext {
        private final AwareShutdownExecutor executor;

        private PinningContext(DispatcherContext parent, Thread currentThread, AwareShutdownExecutor executor) {
            super(parent, currentThread);
            this.executor = executor;
        }

        @Override
        public AwareShutdownExecutor executor() {
            return executor;
        }

        @Override
        DispatcherContext inheritContext(Thread currentThread) {
            return new PinningContext(this, currentThread, executor);
        }
    }

    private static final class EmptyContext extends DispatcherContext {
        private EmptyContext(Thread currentThread) {
            super(null, currentThread);
        }

        @Override
        public AwareShutdownExecutor executor() {
            return null;
        }

        @Override
        DispatcherContext inheritContext(Thread currentThread) {
            return new EmptyContext(currentThread);
        }
    }

    private final static class DynamicDispatcherContext extends DispatcherContext {
        private final AtomicReference<AwareShutdownExecutor> executorRef;

        public DynamicDispatcherContext(DispatcherContext parent, Thread currentThread, AwareShutdownExecutor executor) {
            super(parent, currentThread);
            this.executorRef = new AtomicReference<>(executor);
        }

        @Override
        public AwareShutdownExecutor executor() {
            return executorRef.get();
        }

        @Override
        public DispatcherContext inheritContext(Thread currentThread) {
            return new DynamicDispatcherContext(this, currentThread, executorRef.get());
        }

        public AwareShutdownExecutor switchExecutor(AwareShutdownExecutor executor) {
            Objects.requireNonNull(executor, "executor");
            return executorRef.getAndSet(executor);
        }
    }

    public interface AwareShutdownExecutor {
        boolean execute(Runnable runnable);

        static AwareShutdownExecutor adapt(Executor executor) {
            if (executor instanceof AwareShutdownExecutor awareShutdownExecutor) {
                return awareShutdownExecutor;
            }
            return runnable -> {
                try {
                    executor.execute(runnable);
                    return true;
                } catch (RejectedExecutionException executionException) {
                    return false;
                }
            };
        }
    }
}
