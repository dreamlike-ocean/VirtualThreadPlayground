package io.github.dreamlike;

import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;

public class OpenDoorHelper {

    public static final MethodHandles.Lookup LOOKUP;

    static {
        try {
            LOOKUP = trustedLookup();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
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
}
