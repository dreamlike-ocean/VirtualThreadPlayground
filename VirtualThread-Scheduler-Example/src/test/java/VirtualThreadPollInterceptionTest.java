import io.github.dreamlike.scheduler.example.CustomerVirtualThreadRuntime;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public class VirtualThreadPollInterceptionTest {

    @Test
    public void testPollInterception() throws Exception {
        String expectedPollerMode = System.getProperty("test.expectedPollerMode");
        String pollerMode = System.getProperty("jdk.pollerMode");

        if (expectedPollerMode == null || expectedPollerMode.isBlank()) {
            Assert.assertNull("jdk.pollerMode should be absent when running without -Djdk.pollerMode", pollerMode);
        } else {
            Assert.assertEquals("jdk.pollerMode must match test.expectedPollerMode", expectedPollerMode, pollerMode);
        }

        String mode = switch (pollerMode) {
            case "1" -> "SYSTEM_THREADS";
            case "2" -> "VTHREAD_POLLERS";
            case "3" -> "POLLER_PER_CARRIER";
            case null -> "default";
            default -> throw new IllegalStateException("Unexpected value: " + pollerMode);
        };
        System.out.println("current mode: " + mode);

        CustomerVirtualThreadRuntime runtime = CustomerVirtualThreadRuntime.INSTANCE;
        Assert.assertNotNull("Runtime should be initialized by agent", runtime);
        runtime.resetPollCount();

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

        Socket client = serverSocket.accept();
        client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
        client.getOutputStream().flush();
        client.close();

        Assert.assertEquals("hello", result.join());
        vt.join();
        serverSocket.close();

        Assert.assertTrue("poll() should have been invoked, but count=" + runtime.getPollCount(),
                runtime.getPollCount() > 0);
    }
}
