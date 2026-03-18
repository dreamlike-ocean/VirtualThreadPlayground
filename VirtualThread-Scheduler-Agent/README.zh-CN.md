# VirtualThread-Scheduler-Agent（中文）

## 概览
该模块提供一个 Java Agent，用于在运行期改写 `java.base` 内部类 `sun.nio.ch.DefaultPollerProvider`：

- 把 `readPoller/writePoller` 的原始实现挪到 `readPoller0/writePoller0`。
- 新增的 `readPoller/writePoller` 会创建并返回注入到 `java.base` 的 `sun.nio.ch.JdkPollerProxy`。
- `JdkPollerProxy` 内部通过 `MethodHandle.invokeExact` 把调用转发给用户自定义的 `VirtualThreadPoller` 实现（例如 `CustomerVirtualThreadPoller`）。

## 伪代码（改写点）
说明：这里 “Patched by agent” 指的是 **被改写的 JDK 内部类** `sun.nio.ch.DefaultPollerProvider`。

```java
// === Patched by agent: java.base/sun.nio.ch.DefaultPollerProvider ===

// 原始逻辑搬到 readPoller0/writePoller0（方法体来自 JDK 原实现）
Poller readPoller0(boolean subPoller) throws IOException { /* original JDK code */ }
Poller writePoller0(boolean subPoller) throws IOException { /* original JDK code */ }

// 新 readPoller/writePoller：先调用 *0 拿到 JDK Poller，再包装为 JdkPollerProxy
Poller readPoller(boolean subPoller) throws IOException {
  Poller jdk = readPoller0(subPoller);
  return new sun.nio.ch.JdkPollerProxy(jdk, /*mode*/ 1, subPoller);
}

Poller writePoller(boolean subPoller) throws IOException {
  Poller jdk = writePoller0(subPoller);
  return new sun.nio.ch.JdkPollerProxy(jdk, /*mode*/ 2, subPoller);
}
```

## JdkPollerProxy 伪代码（注入到 java.base）
说明：`sun.nio.ch.JdkPollerProxy` 是由 agent 注入到 `java.base` 的类，它在 `<clinit>` 中一次性解析所需的 `MethodHandle`，后续路径全部走 `invokeExact`。

```java
// === Injected by agent: java.base/sun.nio.ch.JdkPollerProxy ===
class sun.nio.ch.JdkPollerProxy extends sun.nio.ch.Poller {
  // 用户类创建入口：优先静态工厂 acquire(...)，否则 fallback 到 public ctor(...)
  static final MethodHandle mhCreatorOrCtor;

  // 用于把 JDK Poller 适配成 VirtualThreadPoller 的 adaptor 构造器
  static final MethodHandle mhAdaptorCtor;

  // 下面这些都是用户 poller 的同名方法句柄（全部在 <clinit> resolve 一次）
  static final MethodHandle mhImplRead;
  static final MethodHandle mhImplWrite;
  static final MethodHandle mhImplStartPoll;
  static final MethodHandle mhImplStopPoll;
  static final MethodHandle mhPollTimeout;
  static final MethodHandle mhClose;

  // 持有用户 poller 实例（可能是单例/缓存返回的实例）
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

## ASCII 调用链（含类加载器）

```
[Bootstrap CL | java.base]
sun.nio.ch.DefaultPollerProvider.readPoller/writePoller   (patched)
  -> new sun.nio.ch.JdkPollerProxy(jdkPoller, mode, subPoller)

[Bootstrap CL | java.base]
sun.nio.ch.JdkPollerProxy  (injected)
  -> (MH invokeExact) user poller: CustomerVirtualThreadPoller

[AppClassLoader/Agent CL | your app]
io.github.dreamlike.scheduler.example.CustomerVirtualThreadPoller
  -> uses jdkVirtualThreadPoller (adaptor) to fallback to JDK poller

[AppClassLoader/Agent CL | your app]
io.github.dreamlike.scheduler.agent.JdkVirtualThreadPollerAdaptor (injected/defined)
  -> (MH invokeExact) sun.nio.ch.Poller methods

[Bootstrap CL | java.base]
sun.nio.ch.KQueuePoller / EpollPoller / ... (real JDK poller)
```

## 参数配置（agentArgs）
参数通过 `-javaagent:...=k=v,k2=v2` 传入。

- `jdk.virtualThreadScheduler.poller.implClass`
  - 用户自定义 poller 的实现类全限定名。
  - 例：`io.github.dreamlike.scheduler.example.CustomerVirtualThreadPoller`

- `jdk.virtualThreadScheduler.poller.supportReadOps`（默认 `true`）
  - 改写后的 `DefaultPollerProvider.supportReadOps()` 返回值。

- `jdk.virtualThreadScheduler.poller.supportWriteOps`（默认 `true`）
  - 改写后的 `DefaultPollerProvider.supportWriteOps()` 返回值。

## 用户 poller 的产生方式（构造器）
`sun.nio.ch.JdkPollerProxy` 在 `<clinit>` 阶段解析一次用户 poller 的创建方式：

使用 public 构造器（要求“单构造器”）：

```java
public <T>(VirtualThreadPoller jdk, int mode, boolean subPoller)
```

说明：
- 这一步解析发生在 `java.base` 注入类里，因此采用 `MethodHandle` 调用。
- MH 在 `<clinit>` 里 resolve 一次，后续 `invokeExact`，不使用 `bindTo`。
