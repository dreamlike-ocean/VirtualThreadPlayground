package io.github.dreamlike;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

public class SingleEventLoopScheduler implements Thread.VirtualThreadScheduler {

    private final BlockingQueue<Runnable> queue;

    public final Set<Thread> threadRecords = ConcurrentHashMap.newKeySet();

    public SingleEventLoopScheduler() {
        queue = new LinkedBlockingDeque<>();
        Thread.ofPlatform()
                .name("event-loop")
                .start(() -> {
                    while (true) {
                        try {
                            queue.take()
                                    .run();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
    }

    @Override
    public void onStart(Thread.VirtualThreadTask virtualThreadTask) {

    }

    @Override
    public void onContinue(Thread.VirtualThreadTask virtualThreadTask) {

    }
}
