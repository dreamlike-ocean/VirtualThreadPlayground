import io.github.dreamlike.CustomerVirtualThreadScheduler;
import io.github.dreamlike.LoomSecretHelper;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class VirtualThreadSchedulerTest {

    @Test
    public void testApply() {
        CompletableFuture<Thread> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> future.complete(LoomSecretHelper.getCurrentCarrierThread()));
        Thread thread = future.join();
        Assert.assertTrue(thread.getName().contains("ForkJoin"));
    }

    @Test
    public void tesPropagateExecutor() {
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
                Assert.assertEquals(threadName, thread.getName());
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
                Assert.assertEquals(threadName, thread.getName());
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
                Assert.assertEquals(threadName, thread.getName());
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
                Assert.assertEquals(threadName, thread.getName());
            }
        }
    }

    @Test
    public void testSwitchExecutor() {
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
            Assert.assertEquals(backupName, thread.getName());
            Thread afterSwitchThread = afterSwitchThreadCompletableFuture.join();
            Assert.assertEquals(threadName, afterSwitchThread.getName());
        }
    }

    @Test
    public void testTrace() {
        CompletableFuture<List<Thread>> future = new CompletableFuture<>();
        try (ExecutorService eventLoop = Executors.newFixedThreadPool(1, r -> new Thread(r, "EventLoop"))) {
            CustomerVirtualThreadScheduler.propagateExecutor(CustomerVirtualThreadScheduler.AwareShutdownExecutor.adapt(eventLoop), () -> Thread.ofVirtual()
                    .name("root-vt")
                    .start(() -> {
                        Thread.ofVirtual()
                                .name("child-vt-0")
                                .start(() -> {
                                    Thread.ofVirtual()
                                            .name("child-vt-1")
                                            .start(() -> {
                                                Thread.ofVirtual()
                                                        .name("child-vt-2")
                                                        .start(() -> {
                                                            future.complete(CustomerVirtualThreadScheduler.traceThreads());
                                                        });
                                            });
                                });
                    }));
            Assert.assertEquals(List.of(Thread.currentThread().getName(), "root-vt", "child-vt-0", "child-vt-1", "child-vt-2"), future.join().stream().map(Thread::getName).toList());

        }
    }
}
