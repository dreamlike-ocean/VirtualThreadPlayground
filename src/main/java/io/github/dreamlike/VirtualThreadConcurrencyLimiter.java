package io.github.dreamlike;

import java.util.concurrent.*;

// 缺少maxWait策略只作为展示
// 如果不考虑ThreadFactory功能 完全可以使用有界线程池直接替代
public class VirtualThreadConcurrencyLimiter implements Executor,ThreadFactory  {

    private final int maxConcurrency;
    private final Semaphore semaphore;
    private final Executor internelExecutor;
    public VirtualThreadConcurrencyLimiter(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(maxConcurrency);
        this.internelExecutor = Executors.newFixedThreadPool(maxConcurrency, Thread.ofVirtual().factory());
    }

    @Override
    public void execute(Runnable command) {
        internelExecutor.execute(() -> {
            semaphore.acquireUninterruptibly();
            try {
                command.run();
            } finally {
                semaphore.release();
            }
        });
    }

    @Override
    public Thread newThread(Runnable r) {
        return Thread.startVirtualThread(() -> {
            semaphore.acquireUninterruptibly();
            try {
                r.run();
            } finally {
                semaphore.release();
            }
        });
    }
}
