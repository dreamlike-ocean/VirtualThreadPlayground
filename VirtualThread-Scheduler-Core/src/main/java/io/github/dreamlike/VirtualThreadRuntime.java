package io.github.dreamlike;

/**
 * Unified virtual thread runtime interface — combines I/O polling and thread scheduling.
 * <p>
 * Analogous to Rust's Tokio runtime: one global singleton handles both
 * task scheduling ({@link Thread.VirtualThreadScheduler}) and async I/O
 * event dispatching ({@link VirtualThreadPoller}) for virtual threads.
 */
public interface VirtualThreadRuntime extends VirtualThreadPoller, Thread.VirtualThreadScheduler {
    // Union of I/O polling + thread scheduling. No additional methods.
}
