# VirtualThread-Scheduler-Agent (English)

## Overview
This module provides a Java Agent that rewrites the JDK-internal class `sun.nio.ch.DefaultPollerProvider` inside `java.base` at runtime:

- Moves the original `readPoller/writePoller` implementations to `readPoller0/writePoller0`.
- The new `readPoller/writePoller` creates and returns an injected `java.base` class `sun.nio.ch.JdkPollerProxy`.
- `JdkPollerProxy` delegates operations to a user-defined `VirtualThreadPoller` implementation (for example `CustomerVirtualThreadPoller`) via `MethodHandle.invokeExact`.

## Pseudocode (patch point)
Note: "Patched by agent" means the JDK internal class being rewritten: `java.base/sun.nio.ch.DefaultPollerProvider`.

```java
// === Patched by agent: java.base/sun.nio.ch.DefaultPollerProvider ===

// Original logic moved to readPoller0/writePoller0 (method bodies are from JDK)
Poller readPoller0(boolean subPoller) throws IOException { /* original JDK code */ }
Poller writePoller0(boolean subPoller) throws IOException { /* original JDK code */ }

// New readPoller/writePoller: call *0 to get the JDK Poller, then wrap it
Poller readPoller(boolean subPoller) throws IOException {
  Poller jdk = readPoller0(subPoller);
  return new sun.nio.ch.JdkPollerProxy(jdk, /*mode*/ 1, subPoller);
}

Poller writePoller(boolean subPoller) throws IOException {
  Poller jdk = writePoller0(subPoller);
  return new sun.nio.ch.JdkPollerProxy(jdk, /*mode*/ 2, subPoller);
}
```

## JdkPollerProxy pseudocode (injected into java.base)
Note: `sun.nio.ch.JdkPollerProxy` is injected by the agent into `java.base`. It resolves all `MethodHandle`s once in `<clinit>` and uses `invokeExact` on the hot path.

```java
// === Injected by agent: java.base/sun.nio.ch.JdkPollerProxy ===
class sun.nio.ch.JdkPollerProxy extends sun.nio.ch.Poller {
  // User instantiation entry: prefer static factory acquire(...), fallback to public ctor(...)
  static final MethodHandle mhCreatorOrCtor;

  // Adaptor ctor to wrap the JDK Poller into a VirtualThreadPoller view
  static final MethodHandle mhAdaptorCtor;

  // User poller method handles (resolved once in <clinit>)
  static final MethodHandle mhImplRead;
  static final MethodHandle mhImplWrite;
  static final MethodHandle mhImplStartPoll;
  static final MethodHandle mhImplStopPoll;
  static final MethodHandle mhPollTimeout;
  static final MethodHandle mhClose;

  // Holds the user poller instance (could be a singleton / cached instance)
  final Object userPoller;

  static {
    Class<?> userCls = Class.forName(implClass, true, cl);

    // Prefer: public static <T> acquire(VirtualThreadPoller, int, boolean)
    // Fallback: public <T>(VirtualThreadPoller, int, boolean)
    mhCreatorOrCtor = resolveAcquireOrCtor(userCls);

    // resolve user method handles: implRead/implWrite/...
    mhImplRead = resolve(userCls, "implRead", ...);
    ...
  }

  JdkPollerProxy(Poller jdkPoller, int mode, boolean subPoller) {
    VirtualThreadPoller adaptor = (VirtualThreadPoller) mhAdaptorCtor.invokeExact((Object) jdkPoller);
    this.userPoller = mhCreatorOrCtor.invokeExact((Object) adaptor, mode, subPoller);
  }

  // delegate to user poller
  int implRead(...)  { return (int) mhImplRead.invokeExact(userPoller, ...); }
  int implWrite(...) { return (int) mhImplWrite.invokeExact(userPoller, ...); }
  void implStartPoll(int fd) { mhImplStartPoll.invokeExact(userPoller, fd); }
  void implStopPoll(int fd, boolean polled) { mhImplStopPoll.invokeExact(userPoller, fd, polled); }
  int poll(int timeout) { return (int) mhPollTimeout.invokeExact(userPoller, timeout); }
  void close() { mhClose.invokeExact(userPoller); }
}
```

## ASCII call chain (with class loaders)

```
[Bootstrap CL | java.base]
sun.nio.ch.DefaultPollerProvider.readPoller/writePoller   (patched)
  -> new sun.nio.ch.JdkPollerProxy(jdkPoller, mode, subPoller)

[Bootstrap CL | java.base]
sun.nio.ch.JdkPollerProxy  (injected)
  -> (MH invokeExact) user poller: CustomerVirtualThreadPoller

[AppClassLoader/Agent CL | your app]
io.github.dreamlike.scheduler.example.CustomerVirtualThreadPoller
  -> uses jdkVirtualThreadPoller (adaptor) to fallback to the JDK poller

[AppClassLoader/Agent CL | your app]
io.github.dreamlike.scheduler.agent.JdkVirtualThreadPollerAdaptor (injected/defined)
  -> (MH invokeExact) sun.nio.ch.Poller methods

[Bootstrap CL | java.base]
sun.nio.ch.KQueuePoller / EpollPoller / ... (real JDK poller)
```

## Configuration (agentArgs)
Arguments are passed via `-javaagent:...=k=v,k2=v2`.

- `jdk.virtualThreadScheduler.poller.implClass`
  - Fully-qualified class name of the user poller implementation.
  - Example: `io.github.dreamlike.scheduler.example.CustomerVirtualThreadPoller`

- `jdk.virtualThreadScheduler.poller.supportReadOps` (default `true`)
  - Controls the rewritten `DefaultPollerProvider.supportReadOps()` return value.

- `jdk.virtualThreadScheduler.poller.supportWriteOps` (default `true`)
  - Controls the rewritten `DefaultPollerProvider.supportWriteOps()` return value.

## User poller instantiation (acquire vs ctor)
`sun.nio.ch.JdkPollerProxy` resolves the user poller creation strategy once in `<clinit>`:

1) Prefer a static factory method:

```java
public static <T> T acquire(VirtualThreadPoller jdk, int mode, boolean subPoller)
```

2) If the factory method is not found, fallback to a public constructor (must be a single-ctor):

```java
public <T>(VirtualThreadPoller jdk, int mode, boolean subPoller)
```

Notes:
- This resolution happens in an injected `java.base` class, so it uses `MethodHandle` calls.
- MethodHandles are resolved once in `<clinit>` and then called via `invokeExact`, without `bindTo`.
