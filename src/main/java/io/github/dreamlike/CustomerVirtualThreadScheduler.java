package io.github.dreamlike;

import java.lang.invoke.VarHandle;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

public record CustomerVirtualThreadScheduler(
        Thread.VirtualThreadScheduler defaultScheduler,
        BlockingQueue<Thread> dispatchRecords) implements Thread.VirtualThreadScheduler {

    private static Thread.VirtualThreadScheduler jdkBuildInScheduler;

    static {
        // force init
        Class<? extends Thread> vtClass = Thread.ofVirtual().unstarted(() -> {
        }).getClass();
    }

    public CustomerVirtualThreadScheduler(Thread.VirtualThreadScheduler defaultScheduler) {
        this(defaultScheduler, new LinkedBlockingDeque<>());
        jdkBuildInScheduler = defaultScheduler;
        VarHandle.storeStoreFence();
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        defaultScheduler.onStart(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        defaultScheduler.onContinue(task);
    }

    public static Thread.VirtualThreadScheduler getJdkBuildInScheduler() {
        return jdkBuildInScheduler;
    }

}
