package io.github.dreamlike;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Pure I/O polling interface for virtual threads.
 * <p>
 * This is a focused interface for I/O event management only.
 * It does NOT extend {@link Thread.VirtualThreadScheduler} — scheduling
 * and I/O are parallel, independent concerns unified by {@link VirtualThreadRuntime}.
 */
public interface VirtualThreadPoller {

    /**
     * Parks the current thread until a file descriptor is ready for the given op.
     */
    void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException;

    /**
     * Parks the current thread until a Selector's file descriptor is ready.
     */
    void pollSelector(int fdVal, long nanos) throws IOException;

    /**
     * Starts the poller group and any system-wide poller threads.
     */
    void start();
}
