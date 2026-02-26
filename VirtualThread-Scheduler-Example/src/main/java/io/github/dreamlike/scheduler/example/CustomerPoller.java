package io.github.dreamlike.scheduler.example;

import io.github.dreamlike.Poller;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public class CustomerPoller implements Poller {
    @Override
    public int implRead(int fdVal, byte[] b, int off, int len, long nanos, BooleanSupplier isOpen) throws IOException {
        return 0;
    }

    @Override
    public int implWrite(int fdVal, byte[] b, int off, int len, BooleanSupplier isOpen) throws IOException {
        return 0;
    }

}
