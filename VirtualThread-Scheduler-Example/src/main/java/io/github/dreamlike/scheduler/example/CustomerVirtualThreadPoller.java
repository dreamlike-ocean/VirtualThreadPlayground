package io.github.dreamlike.scheduler.example;

import io.github.dreamlike.AbstractVirtualThreadPoller;
import io.github.dreamlike.VirtualThreadPoller;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public class CustomerVirtualThreadPoller extends AbstractVirtualThreadPoller {

    public CustomerVirtualThreadPoller(VirtualThreadPoller jdkVirtualThreadPoller, int mode) {
        super(jdkVirtualThreadPoller, mode);
    }

    @Override
    public int implRead(int fdVal, byte[] b, int off, int len, long nanos, BooleanSupplier isOpen) throws IOException {
        return 0;
    }

    @Override
    public int implWrite(int fdVal, byte[] b, int off, int len, BooleanSupplier isOpen) throws IOException {
        return 0;
    }
}
