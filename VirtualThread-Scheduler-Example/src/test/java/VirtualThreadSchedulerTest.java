import io.github.dreamlike.scheduler.example.CustomerVirtualThreadRuntime;
import io.github.dreamlike.LoomSecretHelper;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        //  platform(with propagateExecutor) -> Thread.startVirtualThread => executor
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                CustomerVirtualThreadRuntime.propagateExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> {
                    Thread.startVirtualThread(() -> {
                        currentThreadFuture.complete(LoomSecretHelper.getCurrentCarrierThread());
                    });
                });

                Thread thread = currentThreadFuture.join();
                Assert.assertEquals(threadName, thread.getName());
            }
        }
        //  Thread.startVirtualThread -> Thread.startVirtualThread => executor
        {
            try (ExecutorService eventLoop = Executors.newSingleThreadExecutor(r -> new Thread(r, threadName))) {
                CompletableFuture<Thread> currentThreadFuture = new CompletableFuture<>();
                Thread.startVirtualThread(() -> {
                    CustomerVirtualThreadRuntime.propagateExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> {
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
                CustomerVirtualThreadRuntime.propagateExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> {
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
                CustomerVirtualThreadRuntime.propagateExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> {
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
            CustomerVirtualThreadRuntime.newThread(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> {
                Thread targetThread = CustomerVirtualThreadRuntime.switchExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(backup), LoomSecretHelper::getCurrentCarrierThread);
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
            CustomerVirtualThreadRuntime.propagateExecutor(CustomerVirtualThreadRuntime.AwareShutdownExecutor.adapt(eventLoop), () -> Thread.ofVirtual()
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
                                                            future.complete(CustomerVirtualThreadRuntime.traceThreads());
                                                        });
                                            });
                                });
                    }));
            Assert.assertEquals(List.of(Thread.currentThread().getName(), "root-vt", "child-vt-0", "child-vt-1", "child-vt-2"), future.join().stream().map(Thread::getName).toList());
        }
    }

    @Test
    public void testPollInterception() throws Exception {
        CustomerVirtualThreadRuntime runtime = CustomerVirtualThreadRuntime.INSTANCE;
        Assert.assertNotNull("Runtime should be initialized by agent", runtime);
        runtime.resetPollCount();

        // Start a server on a random port
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();

        CompletableFuture<String> result = new CompletableFuture<>();
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port));
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[1024];
                int n = in.read(buf);
                result.complete(new String(buf, 0, n, StandardCharsets.UTF_8));
                socket.close();
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        });

        // Accept and send data
        Socket client = serverSocket.accept();
        client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
        client.close();

        Assert.assertEquals("hello", result.join());
        vt.join();
        serverSocket.close();

        // poll() should have been called at least once (for the socket connect/read)
        Assert.assertTrue("poll() should have been invoked, but count=" + runtime.getPollCount(),
                runtime.getPollCount() > 0);
    }
}
