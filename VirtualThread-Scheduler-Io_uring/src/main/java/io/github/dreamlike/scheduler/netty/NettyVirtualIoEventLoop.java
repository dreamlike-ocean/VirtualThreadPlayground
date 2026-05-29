package io.github.dreamlike.scheduler.netty;

import io.github.dreamlike.scheduler.uring.IoUringVirtualThreadRuntime;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.IoEventLoop;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoRegistration;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.unix.FileDescriptor;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.util.concurrent.AbstractScheduledEventExecutor;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.Ticker;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThreadExecutorMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


public class NettyVirtualIoEventLoop extends AbstractScheduledEventExecutor implements IoEventLoop {
    private static final Runnable WAKEUP_TASK = () -> {
        // NOOP
    };
    private static final int ST_STARTED = 0;
    private static final int ST_SHUTTING_DOWN = 1;
    private static final int ST_SHUTDOWN = 2;
    private static final int ST_TERMINATED = 3;
    private static final int NIO_READINESS_FD = -1;
    private static final MethodHandle KQUEUE_READINESS_FD_MH;
    private static final MethodHandle EPOLL_READINESS_FD_MH;
    private static final MethodHandle IO_UIRING_READINESS_FD_MH;
    private static final MethodHandle NIO_READINESS_FD_MH;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle kqueueFdMethodHandler;
        try {
            MethodHandles.Lookup kqueueLookup = MethodHandles.privateLookupIn(KQueueIoHandler.class, lookup);
            MethodHandle fdValue = lookup.findVirtual(FileDescriptor.class, "intValue", MethodType.methodType(int.class));
            MethodHandle kqueueNettyFdGetterMethodHandler =
                    kqueueLookup.findGetter(KQueueIoHandler.class, "kqueueFd", FileDescriptor.class);
            kqueueFdMethodHandler = MethodHandles.filterReturnValue(kqueueNettyFdGetterMethodHandler,fdValue);
        } catch (Throwable e) {
            kqueueFdMethodHandler = null;
        }
        KQUEUE_READINESS_FD_MH = kqueueFdMethodHandler;

        MethodHandle epollFdMethodHandler;
        try {
            MethodHandles.Lookup epollLookup = MethodHandles.privateLookupIn(EpollIoHandler.class, lookup);
            MethodHandle fdValue = lookup.findVirtual(FileDescriptor.class, "intValue", MethodType.methodType(int.class));
            MethodHandle epollNettyFdGetterMethodHandler =
                    epollLookup.findGetter(EpollIoHandler.class, "epollFd", FileDescriptor.class);
            epollFdMethodHandler =  MethodHandles.filterReturnValue(epollNettyFdGetterMethodHandler,fdValue);
        } catch (Throwable e){
            epollFdMethodHandler = null;
        }
        EPOLL_READINESS_FD_MH = epollFdMethodHandler;
        MethodHandle ioUringFdMethodHandler;
        try {
            Class<?> ringBufferClass = Class.forName("io.netty.channel.uring.RingBuffer");
            Class<?> submissionQueueClass = Class.forName("io.netty.channel.uring.SubmissionQueue");
            MethodHandles.Lookup ioUringLookup = MethodHandles.privateLookupIn(IoUringIoHandler.class, lookup);
            MethodHandles.Lookup ringBufferLookup = MethodHandles.privateLookupIn(ringBufferClass, lookup);
            MethodHandles.Lookup submissionQueueLookup = MethodHandles.privateLookupIn(submissionQueueClass, lookup);
            MethodHandle ioUringRingBufferGetterMethodHandler =
                    ioUringLookup.findGetter(IoUringIoHandler.class, "ringBuffer", ringBufferClass);
            MethodHandle ioUringRingBufferGetterSubmissionQueueGetterMethodHandler =
                    ringBufferLookup.findGetter(ringBufferClass, "ioUringSubmissionQueue",  submissionQueueClass);
            MethodHandle ioUringSubmissionQueueRingFdGetterMethodHandler =
                    submissionQueueLookup.findGetter(submissionQueueClass, "ringFd", int.class);
            ioUringFdMethodHandler = MethodHandles.filterReturnValue(
                    MethodHandles.filterReturnValue(ioUringRingBufferGetterMethodHandler, ioUringRingBufferGetterSubmissionQueueGetterMethodHandler),
                    ioUringSubmissionQueueRingFdGetterMethodHandler
                    );
        } catch (Throwable e) {
            ioUringFdMethodHandler = null;
        }
        IO_UIRING_READINESS_FD_MH = ioUringFdMethodHandler;

        NIO_READINESS_FD_MH = MethodHandles.dropArguments(MethodHandles.constant(int.class, -1), 0, IoHandler.class);
    }

    private final AtomicInteger state;
    private final Promise<?> terminationFuture = new DefaultPromise<Void>(GlobalEventExecutor.INSTANCE);
    private final Queue<Runnable> taskQueue = PlatformDependent.newMpscQueue();
    private final IoHandlerContext nonBlockingContext = new IoHandlerContext() {
        @Override
        public boolean canBlock() {
            assert inEventLoop();
            return false;
        }

        @Override
        public long delayNanos(long currentTimeNanos) {
            assert inEventLoop();
            return 0;
        }

        @Override
        public long deadlineNanos() {
            assert inEventLoop();
            return -1;
        }
    };
    private final IoEventLoopGroup parent;
    private final AtomicReference<Thread> owningThread;
    private final IoHandler handler;
    private final Ticker ticker;
    private final BlockingIoHandlerContext blockingContext = new BlockingIoHandlerContext();
    private final int readinessFd;
    private volatile long gracefulShutdownQuietPeriod;
    private volatile long gracefulShutdownTimeout;
    private long gracefulShutdownStartTime;
    private long lastExecutionTime;
    private boolean initialized;

    public NettyVirtualIoEventLoop(IoHandlerFactory factory) {
        this.parent = null;
        this.owningThread = new AtomicReference<>();
        this.handler = factory.newHandler(this);
        this.ticker = Ticker.systemTicker();
        this.state = new AtomicInteger(ST_STARTED);
        this.readinessFd = readinessFd(handler);
    }

    private static int readinessFd(IoHandler ioHandler) {
        // java的pattern match不支持case EPOLL_READINESS_FD_MH != null && EpollIoHandler _ -> EPOLL_READINESS_FD_MH; 这样的卫语句 只能这样写了
        MethodHandle mh = null;
        if (ioHandler instanceof NioIoHandler) {
            mh = NIO_READINESS_FD_MH;
        }
        if (mh == null && EPOLL_READINESS_FD_MH != null && ioHandler instanceof EpollIoHandler) {
            mh = EPOLL_READINESS_FD_MH;
        }
        if (mh == null && IO_UIRING_READINESS_FD_MH != null && ioHandler instanceof IoUringIoHandler) {
            mh = IO_UIRING_READINESS_FD_MH;
        }
        if (mh == null && KQUEUE_READINESS_FD_MH != null && ioHandler instanceof KQueueIoHandler) {
            mh = KQUEUE_READINESS_FD_MH;
        }
        if (mh == null) {
            throw new IllegalArgumentException(ioHandler.getClass() + " cant be supported! please use NioIoHandler, EpollIoHandler, IoUringIoHandler or KQueueIoHandler");
        }
        try {
            return (int) mh.invoke(ioHandler);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * This allows to specify additional blocking conditions which will be used by the {@link IoHandler} to decide
     * whether it is allowed to block or not.
     */
    protected boolean canBlock() {
        return true;
    }

    @Override
    public final Ticker ticker() {
        return ticker;
    }

    /**
     * Poll and run tasks from the task queue, until the task queue is empty or the given deadline is exceeded.<br>
     * If {@code timeoutNanos} is less or equals 0, no deadline is applied.
     *
     * @param timeoutNanos the maximum time in nanoseconds to run tasks.
     */
    public final int runNonBlockingTasks(long timeoutNanos) {
        return runAllTasks(timeoutNanos, true);
    }

    private int runAllTasks(long timeoutNanos, boolean setCurrentExecutor) {
        assert inEventLoop();
        final Queue<Runnable> taskQueue = this.taskQueue;
        // since the taskQueue is unbounded we don't need to keep on calling this while draining it.
        boolean alwaysTrue = fetchFromScheduledTaskQueue(taskQueue);
        assert alwaysTrue;
        Runnable task = taskQueue.poll();
        if (task == null) {
            return 0;
        }
        EventExecutor old = setCurrentExecutor ? ThreadExecutorMap.setCurrentExecutor(this) : null;
        try {
            final long deadline = timeoutNanos > 0 ? getCurrentTimeNanos() + timeoutNanos : 0;
            int runTasks = 0;
            long lastExecutionTime;
            final Ticker ticker = this.ticker;
            for (; ; ) {
                safeExecute(task);

                runTasks++;

                if (timeoutNanos > 0) {
                    lastExecutionTime = ticker.nanoTime();
                    if ((lastExecutionTime - deadline) >= 0) {
                        break;
                    }
                }

                task = taskQueue.poll();
                if (task == null) {
                    lastExecutionTime = ticker.nanoTime();
                    break;
                }
            }
            this.lastExecutionTime = lastExecutionTime;
            return runTasks;
        } finally {
            if (setCurrentExecutor) {
                ThreadExecutorMap.setCurrentExecutor(old);
            }
        }
    }

    private int run(IoHandlerContext context, long runAllTasksTimeoutNanos) {
        if (!initialized) {
            if (owningThread.get() == null) {
                throw new IllegalStateException("Owning thread not set");
            }
            initialized = true;
            handler.initialize();
        }
        EventExecutor old = ThreadExecutorMap.setCurrentExecutor(this);
        try {
            if (isShuttingDown()) {
                if (terminationFuture.isDone()) {
                    // Already completely terminated
                    return 0;
                }
                return runAllTasksBeforeDestroy();
            }
            final int ioTasks = handler.run(context);
            // Now run all tasks.
            if (runAllTasksTimeoutNanos < 0) {
                return ioTasks;
            }
            int runResult = ioTasks + runAllTasks(runAllTasksTimeoutNanos, false);
            long timeoutNanos = context.deadlineNanos() == -1 ? -1 : context.delayNanos(System.nanoTime());
            if (readinessFd != NIO_READINESS_FD) {
                IoUringVirtualThreadRuntime.getInstance().waitJdkPollIn(readinessFd, timeoutNanos);
            }
            return runResult;
        } finally {
            ThreadExecutorMap.setCurrentExecutor(old);
        }
    }

    private int runAllTasksBeforeDestroy() {
        // Run all tasks before prepare to destroy.
        int run = runAllTasks(-1, false);
        handler.prepareToDestroy();
        if (confirmShutdown()) {
            // Destroy the handler now and run all remaining tasks.
            try {
                handler.destroy();
                for (; ; ) {
                    int r = runAllTasks(-1, false);
                    run += r;
                    if (r == 0) {
                        break;
                    }
                }
            } finally {
                state.set(ST_TERMINATED);
                terminationFuture.setSuccess(null);
            }
        }
        return run;
    }

    /**
     * Executes all ready IO and tasks for this {@link IoEventLoop}.
     * This methods will <strong>NOT</strong> block and wait for IO / tasks to be ready, it will just
     * return directly if there is nothing to do.
     * <p>
     * <strong>Must be called from the owning {@link Thread} that was passed as a parameter on construction.</strong>
     * <p>
     *
     * @param runAllTasksTimeoutNanos the maximum time in nanoseconds to run tasks.
     *                                If {@code = 0}, no timeout is applied; if {@code < 0} it just perform I/O tasks.
     * @return the number of IO and tasks executed.
     * @throws IllegalStateException if the method is not called from the owning {@link Thread}.
     */
    public final int runNow(long runAllTasksTimeoutNanos) {
        checkCurrentThread();
        return run(nonBlockingContext, runAllTasksTimeoutNanos);
    }

    /**
     * Run all ready IO and tasks for this {@link IoEventLoop}.
     * This methods will <strong>NOT</strong> block and wait for IO / tasks to be ready, it will just
     * return directly if there is nothing to do.
     * <p>
     * <strong>Must be called from the owning {@link Thread} that was passed as a parameter on construction.</strong>
     *
     * @return the number of IO and tasks executed.
     */
    public final int runNow() {
        checkCurrentThread();
        return run(nonBlockingContext, 0);
    }

    /**
     * Run all ready IO and tasks for this {@link IoEventLoop}.
     * This methods will block and wait for IO / tasks to be ready if there is nothing to process atm for the given
     * {@code waitNanos}.
     * <p>
     * <strong>Must be called from the owning {@link Thread} that was passed as an parameter on construction.</strong>
     *
     * @param runAllTasksTimeoutNanos the maximum time in nanoseconds to run tasks.
     *                                If {@code = 0}, no timeout is applied; if {@code < 0} it just perform I/O tasks.
     * @param waitNanos               the maximum amount of nanoseconds to wait before returning. IF {@code 0} it will block until
     *                                there is some IO / tasks ready, if {@code -1} will not block at all and just return directly
     *                                if there is nothing to run (like {@link #runNow()}).
     * @return the number of IO and tasks executed.
     */
    public final int run(long waitNanos, long runAllTasksTimeoutNanos) {
        checkCurrentThread();

        final IoHandlerContext context;
        if (waitNanos < 0) {
            context = nonBlockingContext;
        } else {
            context = blockingContext;
            blockingContext.maxBlockingNanos = waitNanos == 0 ? Long.MAX_VALUE : waitNanos;
        }
        return run(context, runAllTasksTimeoutNanos);
    }

    /**
     * Run all ready IO and tasks for this {@link IoEventLoop}.
     * This methods will block and wait for IO / tasks to be ready if there is nothing to process atm for the given
     * {@code waitNanos}.
     * <p>
     * <strong>Must be called from the owning {@link Thread} that was passed as an parameter on construction.</strong>
     *
     * @param waitNanos the maximum amount of nanoseconds to wait before returning. IF {@code 0} it will block until
     *                  there is some IO / tasks ready, if {@code -1} will not block at all and just return directly
     *                  if there is nothing to run (like {@link #runNow()}).
     * @return the number of IO and tasks executed.
     */
    public final int run(long waitNanos) {
        return run(waitNanos, 0);
    }

    private void checkCurrentThread() {
        if (!inEventLoop(Thread.currentThread())) {
            throw new IllegalStateException();
        }
    }

    /**
     * Force a wakeup and so the {@link #run(long)} method will unblock and return even if there was nothing to do.
     */
    public final void wakeup() {
        if (isShuttingDown()) {
            return;
        }
        handler.wakeup();
    }

    @Override
    public final NettyVirtualIoEventLoop next() {
        return this;
    }

    @Override
    public final IoEventLoopGroup parent() {
        return parent;
    }

    @Deprecated
    @Override
    public final ChannelFuture register(Channel channel) {
        return register(new DefaultChannelPromise(channel, this));
    }

    @Deprecated
    @Override
    public final ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        promise.channel().unsafe().register(this, promise);
        return promise;
    }

    @Override
    public final Future<IoRegistration> register(final IoHandle handle) {
        Promise<IoRegistration> promise = newPromise();
        if (inEventLoop()) {
            registerForIo0(handle, promise);
        } else {
            execute(() -> registerForIo0(handle, promise));
        }

        return promise;
    }

    private void registerForIo0(final IoHandle handle, Promise<IoRegistration> promise) {
        assert inEventLoop();
        final IoRegistration registration;
        try {
            registration = handler.register(handle);
        } catch (Exception e) {
            promise.setFailure(e);
            return;
        }
        promise.setSuccess(registration);
    }

    @Deprecated
    @Override
    public final ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        ObjectUtil.checkNotNull(channel, "channel");
        channel.unsafe().register(this, promise);
        return promise;
    }

    @Override
    public final boolean isCompatible(Class<? extends IoHandle> handleType) {
        return handler.isCompatible(handleType);
    }

    @Override
    public final boolean isIoType(Class<? extends IoHandler> handlerType) {
        return handler.getClass().equals(handlerType);
    }

    @Override
    public final boolean inEventLoop(Thread thread) {
        return this.owningThread.get() == thread;
    }

    /**
     * Set the owning thread that will call {@link #run}. May only be called once, and only if the owning thread was
     * not set in the constructor already.
     *
     * @param owningThread The owning thread
     */
    public final void setOwningThread(Thread owningThread) {
        Objects.requireNonNull(owningThread, "owningThread");
        if (!this.owningThread.compareAndSet(null, owningThread)) {
            throw new IllegalStateException("Owning thread already set");
        }
    }

    private void shutdown0(long quietPeriod, long timeout, int shutdownState) {
        boolean inEventLoop = inEventLoop();
        boolean wakeup;
        int oldState;
        for (; ; ) {
            if (isShuttingDown()) {
                return;
            }
            int newState;
            wakeup = true;
            oldState = state.get();
            if (inEventLoop) {
                newState = shutdownState;
            } else if (oldState == ST_STARTED) {
                newState = shutdownState;
            } else {
                newState = oldState;
                wakeup = false;
            }

            if (state.compareAndSet(oldState, newState)) {
                break;
            }
        }
        if (quietPeriod != -1) {
            gracefulShutdownQuietPeriod = quietPeriod;
        }
        if (timeout != -1) {
            gracefulShutdownTimeout = timeout;
        }

        if (wakeup) {
            // same as AbstractScheduledEventExecutor.WAKEUP_TASK
            taskQueue.offer(WAKEUP_TASK);
            handler.wakeup();
        }
    }

    @Override
    public final Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        ObjectUtil.checkPositiveOrZero(quietPeriod, "quietPeriod");
        if (timeout < quietPeriod) {
            throw new IllegalArgumentException(
                    "timeout: " + timeout + " (expected >= quietPeriod (" + quietPeriod + "))");
        }
        ObjectUtil.checkNotNull(unit, "unit");

        shutdown0(unit.toNanos(quietPeriod), unit.toNanos(timeout), ST_SHUTTING_DOWN);
        return terminationFuture();
    }

    @Override
    @Deprecated
    public final void shutdown() {
        shutdown0(-1, -1, ST_SHUTDOWN);
    }

    @Override
    public final Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    public final boolean isShuttingDown() {
        return state.get() >= ST_SHUTTING_DOWN;
    }

    @Override
    public final boolean isShutdown() {
        return state.get() >= ST_SHUTDOWN;
    }

    @Override
    public final boolean isTerminated() {
        return state.get() == ST_TERMINATED;
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminationFuture.await(timeout, unit);
    }

    @Override
    public final void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        boolean inEventLoop = inEventLoop();
        if (inEventLoop) {
            if (isShutdown()) {
                throw new RejectedExecutionException("event executor terminated");
            }
        }
        taskQueue.add(command);
        if (!inEventLoop) {
            if (isShutdown()) {
                boolean reject = false;
                try {
                    if (taskQueue.remove(command)) {
                        reject = true;
                    }
                } catch (UnsupportedOperationException e) {
                    // The task queue does not support removal so the best thing we can do is to just move on and
                    // hope we will be able to pick-up the task before its completely terminated.
                    // In worst case we will log on termination.
                }
                if (reject) {
                    throw new RejectedExecutionException("event executor terminated");
                }
            }
            handler.wakeup();
        }
    }

    private boolean hasTasks() {
        return !taskQueue.isEmpty();
    }

    private boolean confirmShutdown() {
        if (!isShuttingDown()) {
            return false;
        }

        if (!inEventLoop()) {
            throw new IllegalStateException("must be invoked from an event loop");
        }

        cancelScheduledTasks();

        if (gracefulShutdownStartTime == 0) {
            gracefulShutdownStartTime = ticker.nanoTime();
        }

        if (runAllTasks(-1, false) > 0) {
            if (isShutdown()) {
                // Executor shut down - no new tasks anymore.
                return true;
            }

            // There were tasks in the queue. Wait a little bit more until no tasks are queued for the quiet period or
            // terminate if the quiet period is 0.
            // See https://github.com/netty/netty/issues/4241
            if (gracefulShutdownQuietPeriod == 0) {
                return true;
            }
            return false;
        }

        final long nanoTime = ticker.nanoTime();

        if (isShutdown() || nanoTime - gracefulShutdownStartTime > gracefulShutdownTimeout) {
            return true;
        }

        if (nanoTime - lastExecutionTime <= gracefulShutdownQuietPeriod) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }

            return false;
        }

        // No tasks were added for last quiet period - hopefully safe to shut down.
        // (Hopefully because we really cannot make a guarantee that there will be no execute() calls by a user.)
        return true;
    }

    @Override
    public final <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        // We need to check if the method was called from within the EventLoop as this would cause a deadlock.
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks);
    }

    @Override
    public final <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        // We need to check if the method was called from within the EventLoop as this would cause a deadlock.
        throwIfInEventLoop("invokeAny");
        return super.invokeAny(tasks, timeout, unit);
    }

    @Override
    public final <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        // We need to check if the method was called from within the EventLoop as this would cause a deadlock.
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks);
    }

    @Override
    public final <T> List<java.util.concurrent.Future<T>> invokeAll(
            Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        // We need to check if the method was called from within the EventLoop as this would cause a deadlock.
        throwIfInEventLoop("invokeAll");
        return super.invokeAll(tasks, timeout, unit);
    }

    private void throwIfInEventLoop(String method) {
        if (inEventLoop()) {
            throw new RejectedExecutionException(
                    "Calling " + method + " from within the EventLoop is not allowed as it would deadlock");
        }
    }

    private class BlockingIoHandlerContext implements IoHandlerContext {
        // this is a positive amount of nanos or Long.MAX_VALUE for no limit
        long maxBlockingNanos = Long.MAX_VALUE;

        @Override
        public boolean canBlock() {
            assert inEventLoop();
            return NettyVirtualIoEventLoop.this.readinessFd == NIO_READINESS_FD
                    && !hasTasks()
                    && !hasScheduledTasks()
                    && NettyVirtualIoEventLoop.this.canBlock();
        }

        @Override
        public long delayNanos(long currentTimeNanos) {
            assert inEventLoop();
            //todo 我们并不会真的block 所以再看看maxBlocking是不是真的需要
            return Math.min(maxBlockingNanos, NettyVirtualIoEventLoop.this.delayNanos(currentTimeNanos, maxBlockingNanos));
        }

        @Override
        public long deadlineNanos() {
            assert inEventLoop();
            long next = nextScheduledTaskDeadlineNanos();
            if (maxBlockingNanos == Long.MAX_VALUE) {
                // next == -1? -1 : next i.e. return next
                return next;
            }
            long now = ticker.nanoTime();
            // we cannot just check Math.min as nanoTime can be negative or wrap around!
            if (next == -1 || next - now > maxBlockingNanos) {
                return now + maxBlockingNanos;
            }
            return next;
        }
    }
}
