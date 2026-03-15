package io.github.dreamlike.scheduler.example;

import io.github.dreamlike.AbstractVirtualThreadPoller;
import io.github.dreamlike.LoomSecretHelper;
import io.github.dreamlike.VirtualThreadPoller;

import java.io.IOException;
import java.util.function.BooleanSupplier;

public class CustomerVirtualThreadPoller extends AbstractVirtualThreadPoller {

    private static final ScopedValue<InitParam> CURRENT_PARAM = ScopedValue.newInstance();
    private static final LazyConstant<CustomerVirtualThreadPoller> SINGLE_INSTANCE = LazyConstant.of(() -> {
        InitParam initParam = CURRENT_PARAM.get();
        return  new CustomerVirtualThreadPoller(
                initParam.jdkVirtualThreadPoller(),
                initParam.mode,
                initParam.subPoller()
        );
    });

    public CustomerVirtualThreadPoller(VirtualThreadPoller jdkVirtualThreadPoller, int mode, boolean subPoller) {
        super(jdkVirtualThreadPoller, mode, subPoller);
    }

    @Override
    public int implRead(int fdVal, byte[] b, int off, int len, long nanos, BooleanSupplier isOpen) throws IOException {
        return 0;
    }

    @Override
    public int implWrite(int fdVal, byte[] b, int off, int len, BooleanSupplier isOpen) throws IOException {
        return 0;
    }

    public static CustomerVirtualThreadPoller acquire(VirtualThreadPoller jdkVirtualThreadPoller, int mode, boolean subPoller) {
        // 中文：供 java.base 注入的 sun.nio.ch.JdkPollerProxy 反射查找的静态工厂方法。
        //      你可以在这里实现“单例/缓存/按 key 复用”等策略，避免每个 JDK Poller 都创建一个新实例。
        // English: Static factory method looked up by the injected java.base sun.nio.ch.JdkPollerProxy.
        //          Implement singleton/caching/reuse-by-key here to avoid creating a new instance per JDK Poller.
        if (SINGLE_INSTANCE.isInitialized()) {
            return SINGLE_INSTANCE.get();
        }
        InitParam initParam = new InitParam(jdkVirtualThreadPoller, mode, subPoller);
        return ScopedValue.where(CURRENT_PARAM, initParam)
                .call(SINGLE_INSTANCE::get);
    }

    private record InitParam(VirtualThreadPoller jdkVirtualThreadPoller, int mode, boolean subPoller) {};
}
