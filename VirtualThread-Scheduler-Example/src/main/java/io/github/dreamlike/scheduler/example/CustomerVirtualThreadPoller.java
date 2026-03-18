package io.github.dreamlike.scheduler.example;

import io.github.dreamlike.AbstractVirtualThreadPoller;
import io.github.dreamlike.VirtualThreadPoller;
import io.netty.channel.IoEvent;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandle;
import io.netty.channel.IoRegistration;
import io.netty.channel.kqueue.KQueueIoEvent;
import io.netty.channel.kqueue.KQueueIoHandle;
import io.netty.channel.kqueue.KQueueIoOps;
import io.netty.util.concurrent.EventExecutor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class CustomerVirtualThreadPoller extends AbstractVirtualThreadPoller {

    public static final ScopedValue<IoEventLoop> currentIoEventLoop = ScopedValue.newInstance();
    private final Map<Integer, IoRegistration> registrationMap;

    static final short EVFILT_READ  = -1;
    static final short EVFILT_WRITE = -2;

    // flags
    static final int EV_ADD     = 0x0001;
    static final int EV_DELETE  = 0x0002;
    static final int EV_ONESHOT = 0x0010;
    static final int EV_CLEAR   = 0x0020;

    public CustomerVirtualThreadPoller(VirtualThreadPoller jdkVirtualThreadPoller, int mode, boolean subPoller) {
        super(jdkVirtualThreadPoller, mode, subPoller);
        this.registrationMap = new ConcurrentHashMap<>();
    }

    @Override
    public void implStartPoll(int fdVal) throws IOException {
        IoEventLoop ioEventLoop = currentIoEventLoop();
        try {
            System.out.println("enter CustomerVirtualThreadPoller driven by netty kqueue!");
            new Exception().printStackTrace(System.out);
            IoRegistration ioRegistration = ioEventLoop.register(new KQueueIoHandle() {
                @Override
                public void handle(IoRegistration ioRegistration, IoEvent ioEvent) {
                    if (ioEvent instanceof KQueueIoEvent kQueueIoEvent) {
                        System.out.println("poll end driven by netty kqueue!");
                        polled(fdVal);
                    }
                }

                @Override
                public void close() throws Exception {

                }

                @Override
                public int ident() {
                    return fdVal;
                }
            }).get();
            registrationMap.put(fdVal, ioRegistration);
            ioRegistration.submit(
                    KQueueIoOps.newOps(
                            isReadMode() ? EVFILT_READ :  EVFILT_WRITE,
                            (short) (EV_ADD|EV_ONESHOT),
                            (int) 0
                    )
            );
        } catch (Exception e) {
            throw new IOException(e);
        }

    }

    @Override
    public void implStopPoll(int fdVal, boolean polled) throws IOException {
        IoRegistration ioRegistration = registrationMap.remove(fdVal);
        if (ioRegistration != null) {
            ioRegistration.cancel();
        }
    }

    public IoEventLoop currentIoEventLoop() {
        return currentIoEventLoop.get();
    }

}
