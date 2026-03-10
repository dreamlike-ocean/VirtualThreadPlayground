package io.github.dreamlike;

import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.InvocationTargetException;

public class LoomSecretHelper {

    public static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle CURRENT_CARRIER_THREAD;
    private static final VarHandle VIRTUAL_THREAD_TASK_VAR_HANDLER;

    static {
        try {
            LOOKUP = trustedLookup();
            CURRENT_CARRIER_THREAD = LOOKUP.findStatic(
                    Thread.class,
                    "currentCarrierThread",
                    MethodType.methodType(Thread.class));
            Class<? extends Thread> vtClass = Thread.ofVirtual().unstarted(() -> {
            }).getClass();
            VIRTUAL_THREAD_TASK_VAR_HANDLER = LOOKUP.unreflectVarHandle(vtClass.getDeclaredField("runContinuation"));
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private static MethodHandles.Lookup trustedLookup() throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        ReflectionFactory reflectionFactory = ReflectionFactory.getReflectionFactory();
        return (MethodHandles.Lookup) reflectionFactory.newConstructorForSerialization(
                MethodHandles.Lookup.class,
                MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, Class.class, int.class)
        ).newInstance(Object.class, null, -1);
    }

    public static Thread.VirtualThreadTask getCurrentTask() {
        Thread currentThread = Thread.currentThread();
        return (Thread.VirtualThreadTask) VIRTUAL_THREAD_TASK_VAR_HANDLER.get(currentThread);
    }

    public static Thread getCurrentCarrierThread() {
        try {
            return (Thread) CURRENT_CARRIER_THREAD.invokeExact();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
