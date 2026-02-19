package io.github.dreamlike;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Main {

    public static void main() {
        printTraceThread();
    }
    public static void printTraceThread() {
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
            String threadTrace = future.join()
                    .stream()
                    .map(Thread::getName)
                    .collect(Collectors.joining("\n ⬇️ \n"));
            System.out.println(threadTrace);
        }
    }
}
