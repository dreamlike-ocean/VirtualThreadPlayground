package io.github.dreamlike;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

/**
 * Abstract base class for custom virtual thread runtime implementations.
 * <p>
 * Provides lazy access to JDK internals:
 * <ul>
 *   <li>{@link #jdkVirtualThreadPoller()} — reads {@code sun.nio.ch.Poller.jdkPoller}
 *       via VarHandle to get the original JDK PollerGroup wrapped as {@link VirtualThreadPoller}.</li>
 *   <li>{@link #jdkScheduler()} — calls {@code VirtualThread.builtinScheduler(false)}
 *       via MethodHandle to get the JDK builtin scheduler's external view.</li>
 * </ul>
 * The VarHandle/MethodHandle are resolved in {@code static {}} (which only <em>loads</em>
 * the target classes without <em>initializing</em> them). Actual values are read/invoked
 * lazily at runtime when the fields are guaranteed to be initialized.
 * <p>
 * Users must implement {@link #poll}, {@link #pollSelector}, {@link #onStart}, and
 * {@link #onContinue}. The {@link #start()} method delegates to the JDK poller by default.
 */
public abstract class AbstractVirtualThreadRuntime implements VirtualThreadRuntime {

    private static final VarHandle JDK_POLLER_VH;
    private static final MethodHandle BUILTIN_SCHEDULER_MH;

    static {
        try {
            var lookup = LoomSecretHelper.LOOKUP;

            // Only LOAD (not initialize) Poller — resolve field offset only
            Class<?> pollerClass = Class.forName("sun.nio.ch.Poller", false, null);
            JDK_POLLER_VH = lookup.findStaticVarHandle(pollerClass, "jdkPoller", Object.class);

            // VirtualThread.builtinScheduler(boolean) is package-private static,
            // but our trusted Lookup (allowedModes=-1) can access it.
            // builtinScheduler(false) returns EXTERNAL_VIEW — the safe wrapper around builtin scheduler.
            Class<?> vtClass = Class.forName("java.lang.VirtualThread", false, null);
            BUILTIN_SCHEDULER_MH = lookup.findStatic(vtClass, "builtinScheduler",
                    MethodType.methodType(Thread.VirtualThreadScheduler.class, boolean.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve handles for VirtualThread runtime", e);
        }
    }

    protected AbstractVirtualThreadRuntime() {
    }

    /**
     * Returns the JDK's original PollerGroup wrapped as {@link VirtualThreadPoller}.
     * <p>
     * Reads {@code sun.nio.ch.Poller.jdkPoller} via VarHandle. At runtime this
     * field is guaranteed to be initialized (Poller's clinit has completed before
     * any I/O poll is invoked).
     */
    protected final VirtualThreadPoller jdkVirtualThreadPoller() {
        return (VirtualThreadPoller) JDK_POLLER_VH.get();
    }

    /**
     * Returns the JDK builtin scheduler's external view.
     * <p>
     * Calls {@code VirtualThread.builtinScheduler(false)} via MethodHandle.
     * At runtime VirtualThread's clinit has completed before any virtual thread
     * is scheduled.
     */
    protected final Thread.VirtualThreadScheduler jdkScheduler() {
        try {
            return (Thread.VirtualThreadScheduler) BUILTIN_SCHEDULER_MH.invokeExact(false);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke builtinScheduler", e);
        }
    }

    @Override
    public final void start() {
        jdkVirtualThreadPoller().start();
        start0();
    }

    protected void start0() {

    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        jdkScheduler().onStart(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        jdkScheduler().onContinue(task);
    }
}
