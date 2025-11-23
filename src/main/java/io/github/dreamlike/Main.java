package io.github.dreamlike;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) {
        Properties properties = System.getProperties();
        properties.setProperty("jdk.pollerMode", "3");
        properties.setProperty("jdk.virtualThreadScheduler.implClass",
                "io.github.dreamlike.CustomerVirtualThreadScheduler");
        plain();
        propagateExecutor();
        switchExecutor();
    }

    public static void plain() {
        CompletableFuture<Thread> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> future.complete(LoomSecretHelper.getCurrentCarrierThread()));
        Thread thread = future.join();
        if (!thread.getName().contains("ForkJoin")) {
            throw new IllegalArgumentException(
                    "thread name is not ForkJoin"
            );
        }
    }

    public static void propagateExecutor() {
        String threadName = "EventLoop";
        //  platform(with propagateExecutor) -> Thread.startVirtualThread => executor ✅
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                CustomerVirtualThreadScheduler.propagateExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> {
                    Thread.startVirtualThread(() -> {
                        currentThreadFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
                    });
                });

                Thread thread = currentThreadFuture.join();
                if (!thread.getName().equals(threadName)) {
                    throw new IllegalArgumentException(
                            "thread name is not " + threadName
                    );
                }
            }
        }
        //  Thread.startVirtualThread -> Thread.startVirtualThread => executor ✅
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    CustomerVirtualThreadScheduler.propagateExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> {
                        Thread.startVirtualThread(() -> {
                            currentThreadFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
                        });
                    });
                });
                Thread thread = currentThreadFuture.join();
                if (!thread.getName().equals(threadName)) {
                    throw new IllegalArgumentException(
                            "thread name is not " + threadName
                    );
                }
            }
        }
        //vt(with executor) -> Thread.startVirtualThread => executor
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                CustomerVirtualThreadScheduler.propagateExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> {
                    Thread.startVirtualThread(() -> {
                        Thread.startVirtualThread(() -> {
                            currentThreadFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
                        });
                    });
                });
                Thread thread = currentThreadFuture.join();
                if (!thread.getName().equals(threadName)) {
                    throw new IllegalArgumentException(
                            "thread name is not " + threadName
                    );
                }
            }
        }

        //vt(with executor) -> Thread.startVirtualThread -> Thread.startVirtualThread
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                CustomerVirtualThreadScheduler.propagateExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> {
                    Thread.startVirtualThread(() -> {
                        Thread.startVirtualThread(() -> {
                            Thread.startVirtualThread(() -> {
                                currentThreadFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
                            });
                        });
                    });
                });
                Thread thread = currentThreadFuture.join();
                if (!thread.getName().equals(threadName)) {
                    throw new IllegalArgumentException(
                            "thread name is not " + threadName
                    );
                }
            }
        }
    }

    public static void switchExecutor() {
        String threadName = "EventLoop";
        String backupName = "Backup";
        try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName));
             ExecutorService backup = Executors.newSingleThreadExecutor(r -> new Thread(r, backupName))
        ) {
            CompletableFuture<Thread> threadCompletableFuture = new CompletableFuture<>();
            CompletableFuture<Thread> afterSwitchThreadCompletableFuture = new CompletableFuture<>();
            CustomerVirtualThreadScheduler.newThread(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> {
                Thread targetThread = CustomerVirtualThreadScheduler.switchExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(backup), LoomSecretHelper::getCurrentCarrierThread);
                threadCompletableFuture.complete(targetThread);
                afterSwitchThreadCompletableFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
            }).start();

            Thread thread = threadCompletableFuture.join();
            if (!thread.getName().equals(backupName)) {
                throw new IllegalArgumentException(
                        "thread name is not " + backupName
                );
            }
            Thread afterSwitchThread = afterSwitchThreadCompletableFuture.join();
            if (!afterSwitchThread.getName().equals(threadName)) {
                throw new IllegalArgumentException(
                        "thread name is not " + threadName
                );
            }
        }
    }
}
