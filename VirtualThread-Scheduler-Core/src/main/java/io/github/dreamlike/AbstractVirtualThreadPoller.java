package io.github.dreamlike;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public abstract class AbstractVirtualThreadPoller implements VirtualThreadPoller {
    protected static final int READ_MODE = 1;
    protected static final int WRITE_MODE = 2;
    protected final VirtualThreadPoller jdkVirtualThreadPoller;
    protected final int mode;
    protected final boolean subPoller;

    protected AbstractVirtualThreadPoller(VirtualThreadPoller jdkVirtualThreadPoller, int mode, boolean subPoller) {
        this.jdkVirtualThreadPoller = jdkVirtualThreadPoller;
        this.mode = mode;
        this.subPoller = subPoller;
    }

    @Override
    public int implRead(int fdVal, byte[] b, int off, int len, long nanos,
                        BooleanSupplier isOpen) throws IOException {
        return jdkVirtualThreadPoller.implRead(fdVal, b, off, len, nanos, isOpen);
    }

    @Override
    public int implWrite(int fdVal, byte[] b, int off, int len,
                         BooleanSupplier isOpen) throws IOException {
        return jdkVirtualThreadPoller.implWrite(fdVal, b, off, len, isOpen);
    }

    @Override
    public void implStartPoll(int fdVal) throws IOException {
        jdkVirtualThreadPoller.implStartPoll(fdVal);
    }

    @Override
    public void implStopPoll(int fdVal, boolean polled) throws IOException {
        jdkVirtualThreadPoller.implStopPoll(fdVal, polled);
    }

    @Override
    public int poll(int timeout) throws IOException {
        return jdkVirtualThreadPoller.poll(timeout);
    }

    @Override
    public void close() throws IOException {
        jdkVirtualThreadPoller.close();
    }

    protected final boolean isReadMode() {
        return (mode & READ_MODE) != 0;
    }

    protected final boolean isWriteMode() {
        return (mode & WRITE_MODE) != 0;
    }

    protected final boolean isSubPoller() {
        return subPoller;
    }
}
