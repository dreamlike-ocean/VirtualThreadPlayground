package io.github.dreamlike;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.Socket;
import java.net.SocketOption;
import java.util.Deque;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static final MethodHandle CURRENT_CARRIER_THREAD;

    static {
        MethodHandles.Lookup lookup = OpenDoorHelper.LOOKUP;
        try {
            CURRENT_CARRIER_THREAD = lookup.findStatic(
                    Thread.class,
                    "currentCarrierThread",
                    MethodType.methodType(Thread.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
//        Properties properties = System.getProperties();
//        properties.setProperty("jdk.pollerMode", "3");
//        properties.setProperty("jdk.virtualThreadScheduler.implClass",
//                CustomerVirtualThreadScheduler.class.getTypeName());
        // can't mix custom default scheduler and API prototypes at this time
        testSwitchExecutor();
    }

    public static Thread getCurrentCarrierThread() {
        try {
            return (Thread) CURRENT_CARRIER_THREAD.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void testNest() {
        var customerVirtualThreadScheduler = ((CustomerVirtualThreadScheduler) CompletableFuture.supplyAsync(Thread.VirtualThreadScheduler::current, Executors.newVirtualThreadPerTaskExecutor()).join());
        var subVTSchedulerFuture = new CompletableFuture<Thread.VirtualThreadScheduler>();
        Thread.ofVirtual()
                .scheduler(customerVirtualThreadScheduler)
                .name("parent vt")
                .start(() -> {
                    Thread.ofVirtual()
                            .scheduler(customerVirtualThreadScheduler)
                            .name("sub vt")
                            .start(() -> subVTSchedulerFuture.complete(Thread.VirtualThreadScheduler.current()));
                });
        Thread.VirtualThreadScheduler subVTScheduler = subVTSchedulerFuture.join();
        System.out.println("sub vt scheduler:" + subVTScheduler);
        customerVirtualThreadScheduler.dispatchRecords()
                .stream()
                .filter(t -> t.getName() != null && !t.getName().isBlank())
                .forEach(System.out::println);
    }

    private static void testPerCarrierPoller() {
        var singleEventLoopScheduler = new SingleEventLoopScheduler();
        CountDownLatch latch = new CountDownLatch(1);
        Thread.ofVirtual()
                .scheduler(singleEventLoopScheduler)
                .name("netClient")
                .start(() -> {
                    try {
                        try (Socket socket = new Socket("www.baidu.com", 8080)) {
                            socket.setSoTimeout(1000);
                            socket.getInputStream().read();
                        }
                    } catch (IOException e) {
                    }
                    latch.countDown();
                });
        try {
            latch.await();
            // 你可以看到
            // VirtualThread[#32,event-loop-Read-Poller]/waiting
            // VirtualThread[#29,netClient]/terminated
            for (Thread threadRecord : singleEventLoopScheduler.threadRecords) {
                System.out.println(threadRecord);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void testSwitchExecutor() {
        try (ExecutorService defaultExecutor = Executors.newFixedThreadPool(1, Thread.ofPlatform().name("first").factory());
             ExecutorService secondExecutor = Executors.newFixedThreadPool(1, Thread.ofPlatform().name("second").factory());
        ) {
            Thread.ofVirtual()
                    .name("main")
                    .scheduler(new CoroutineDispatcher(defaultExecutor))
                    .start(() -> {
                        System.out.println("before switch " + getCurrentCarrierThread());
                        String s = CoroutineDispatcher.switchExecutor(secondExecutor, () -> "in switch " + getCurrentCarrierThread());
                        System.out.println(s);
                        System.out.println("after switch " + getCurrentCarrierThread());
                    }).join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
