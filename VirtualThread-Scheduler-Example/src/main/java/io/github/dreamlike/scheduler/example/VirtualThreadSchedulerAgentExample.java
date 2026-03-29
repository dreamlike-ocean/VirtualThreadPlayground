package io.github.dreamlike.scheduler.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class VirtualThreadSchedulerAgentExample {

    public static void main(String[] args) throws Exception {
        startServer();

        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", 4399));
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[1024];
                int res = inputStream.read(buffer);
                String s = new String(buffer, 0, res, StandardCharsets.UTF_8);
                System.out.println("socket recv: " + s);
                socket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        thread.join();
    }

    private static void startServer() {
        Thread.ofVirtual().start(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(4399);
                while (true) {
                    Socket client = serverSocket.accept();
                    client.getOutputStream().write("Hello World!".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    client.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
