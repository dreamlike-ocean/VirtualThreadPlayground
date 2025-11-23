package io.github.dreamlike;

import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;


// 默认继承线程
// 1. platform -> Thread.startVirtualThread => jdkScheduler  ✅
// 2. platform(with propagateExecutor)/Thread.startVirtualThread -> Thread.startVirtualThread => executor ✅
// 3. vt(with executor) -> Thread.startVirtualThread => executor ✅
// 4. vt(with executor) -> Thread.startVirtualThread -> Thread.startVirtualThread => executor ✅
public class CustomerVirtualThreadScheduler implements Thread.VirtualThreadScheduler {

    private static final EmptyExecutor EMPTY_EXECUTOR = new EmptyExecutor();
    private static final ScopedValue<DispatcherExecutor> DISPATCHER_EXECUTOR_SCOPED_VALUE = ScopedValue.newInstance();
    public static CustomerVirtualThreadScheduler INSTANCE;
    private static Thread.VirtualThreadScheduler jdkBuildInScheduler;

    public CustomerVirtualThreadScheduler(Thread.VirtualThreadScheduler defaultScheduler) {
        jdkBuildInScheduler = defaultScheduler;
        INSTANCE = this;
        VarHandle.storeStoreFence();
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        if (task.attachment() instanceof DispatcherExecutor dispatcherExecutor) {
            if (!dispatcherExecutor.executor().execute(task)) {
                task.attach(EMPTY_EXECUTOR);
                jdkBuildInScheduler.onStart(task);
            }
            return;
        }
        // 不归属于任何调度器的野线程
        DispatcherExecutor dispatcherExecutor = DISPATCHER_EXECUTOR_SCOPED_VALUE.orElse(EMPTY_EXECUTOR);
        // 看看能不能从当前线程中获取一个executor
        if (dispatcherExecutor == EMPTY_EXECUTOR
                && Thread.currentThread().isVirtual()
                && LoomSecretHelper.getCurrentTask().attachment() instanceof DispatcherExecutor currentDispatcherExecutor) {
            // 只是共用executor 但是不要指向同一个DispatcherExecutor
            // 防止switch的时候误触发了其他vt的切换
            dispatcherExecutor = currentDispatcherExecutor.inheritExecutor();
        }
        task.attach(dispatcherExecutor);
        if (dispatcherExecutor == EMPTY_EXECUTOR) {
            jdkBuildInScheduler.onStart(task);
            return;
        }
        if (!dispatcherExecutor.executor().execute(task)) {
            jdkBuildInScheduler.onStart(task);
        }
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {

        DispatcherExecutor dispatcherExecutor = (DispatcherExecutor) task.attachment();
        if (dispatcherExecutor == EMPTY_EXECUTOR) {
            jdkBuildInScheduler.onContinue(task);
        } else {
            if (!dispatcherExecutor.executor().execute(task)) {
                //强制切换到默认调度器
                task.attach(EMPTY_EXECUTOR);
                jdkBuildInScheduler.onContinue(task);
            }
        }
    }

    public static void propagateExecutor(AwareShutdownExecutor executor, Runnable runnable) {
        propagateExecutor(executor, DispatchType.DYNAMIC, runnable);
    }

    public static Thread newThread(AwareShutdownExecutor executor, Runnable runnable) {
        Objects.requireNonNull(executor, "executor");
        DynamicDispatcherExecutor ctx = new DynamicDispatcherExecutor(executor);
        return Thread.VirtualThreadScheduler.newThread(
                () -> ScopedValue.where(DISPATCHER_EXECUTOR_SCOPED_VALUE, ctx).run(runnable),
                ctx
        );
    }

    public static void propagateExecutor(AwareShutdownExecutor executor, DispatchType type, Runnable runnable) {
        Objects.requireNonNull(executor, "executor");
        DispatcherExecutor currentDispatcherExecutor = switch (type) {
            case DYNAMIC -> new DynamicDispatcherExecutor(executor);
            case PINNING -> new PinningExecutor(executor);
        };

        ScopedValue.where(DISPATCHER_EXECUTOR_SCOPED_VALUE, currentDispatcherExecutor)
                .run(runnable);
    }

    public static <T> T switchExecutor(AwareShutdownExecutor executor, Callable<T> task) {
        if (!Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("current thread is not virtual thread");
        }
        DispatcherExecutor dispatcherExecutor = DISPATCHER_EXECUTOR_SCOPED_VALUE.orElse(EMPTY_EXECUTOR);
        if (!(dispatcherExecutor instanceof DynamicDispatcherExecutor dynamicDispatcherExecutor)) {
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

    private sealed interface DispatcherExecutor permits DynamicDispatcherExecutor, EmptyExecutor, PinningExecutor {
        AwareShutdownExecutor executor();
        default DispatcherExecutor inheritExecutor() {
            return this;
        }
    }

    private record PinningExecutor(AwareShutdownExecutor executor) implements DispatcherExecutor {
        @Override
        public AwareShutdownExecutor executor() {
            return executor;
        }
    }

    private record EmptyExecutor() implements DispatcherExecutor {
        @Override
        public AwareShutdownExecutor executor() {
            return null;
        }
    }

    private final static class DynamicDispatcherExecutor implements DispatcherExecutor {
        private final AtomicReference<AwareShutdownExecutor> executorRef;

        public DynamicDispatcherExecutor(AwareShutdownExecutor executor) {
            this.executorRef = new AtomicReference<>(executor);
        }

        @Override
        public AwareShutdownExecutor executor() {
            return executorRef.get();
        }

        @Override
        public DispatcherExecutor inheritExecutor() {
            return new DynamicDispatcherExecutor(executorRef.get());
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
