# VirtualThread-Scheduler-Agent (English)

## Overview

This module provides a Java Agent that rewrites both `sun.nio.ch.Poller` and `java.lang.VirtualThread` at runtime, creating a **unified virtual thread runtime** — a single proxy object that simultaneously serves as the I/O PollerGroup and the VirtualThread Scheduler (`POLLER_GROUP == DEFAULT_SCHEDULER`).

Think of it as a Tokio-style runtime for Java virtual threads: one global singleton handles both task scheduling and async I/O event dispatching.

## Interface Hierarchy

```
VirtualThreadPoller (pure I/O)            Thread.VirtualThreadScheduler (JDK, pure scheduling)
  poll(fdVal, event, nanos, isOpen)         onStart(VirtualThreadTask)
  pollSelector(fdVal, nanos)                onContinue(VirtualThreadTask)
  start()
          \                               /
           \                             /
            VirtualThreadRuntime (union of both)
              extends VirtualThreadPoller, Thread.VirtualThreadScheduler
              (no additional methods)
```

- `VirtualThreadPoller` and `VirtualThreadScheduler` are **parallel, independent interfaces**
- `VirtualThreadRuntime` is their **union** — the user-facing API
- The JDK adaptor only implements `VirtualThreadPoller` (no scheduling stubs needed)

## How It Works

### Agent Patches Two JDK Classes

**1. `sun.nio.ch.Poller`** (via ClassFileTransformer on first load):
- Renames `createPollerGroup()` → `createPollerGroup0()`
- Adds `public static Object jdkPoller` field
- Changes `POLLER_GROUP` field from `private` to `public`
- Generates new `createPollerGroup()` that wraps the result with `JdkProxyVirtualThreadRuntime`

**2. `java.lang.VirtualThread`** (via `retransformClasses` — already loaded at premain time):
- Replaces `loadCustomScheduler()` body to return `(VirtualThreadScheduler) Poller.POLLER_GROUP`
- Agent sets `System.setProperty("jdk.virtualThreadScheduler.implClass", "agent-forced")` to force the original `<clinit>` to take the custom scheduler branch

### Initialization Order

```
premain → install():
  ① redefineModule: open sun.nio.ch + java.lang to agent
  ② addTransformer (intercepts Poller + VirtualThread)
  ③ retransformClasses(VirtualThread) — rewrite loadCustomScheduler
  ④ defineClass(JdkVirtualThreadPollerAdaptor) in App CL
  ⑤ Class.forName("sun.nio.ch.Poller", false, null) — LOAD only, transformer rewrites
  ⑥ privateLookupIn(Poller) → defineClass(JdkProxyVirtualThreadRuntime)
  install() ends. Neither Poller nor VirtualThread have been INITIALIZED.

First use of virtual threads → VirtualThread.<clinit>:
  ① createBuiltinScheduler(true) → ForkJoinPool
  ② createExternalView(builtin) → externalView
  ③ loadCustomScheduler(...) [REWRITTEN]:
     → getstatic Poller.POLLER_GROUP → triggers Poller.<clinit>:
       → createPollerGroup() [REWRITTEN]:
         → createPollerGroup0() → jdkGroup (started)
         → proxy = new JdkProxyVirtualThreadRuntime(jdkGroup)
           → adaptor = MH(jdkGroup), stored to proxy.adaptor field
           → Poller.jdkPoller = proxy.adaptor
           → customerPoller = MH() — no-arg constructor
         → return proxy
       → POLLER_GROUP = proxy
     → return proxy as VirtualThreadScheduler
  ④ DEFAULT_SCHEDULER = proxy  ← same object as POLLER_GROUP ✅
  ⑤ EXTERNAL_VIEW = externalView
```

### Runtime Access (lazy, read-only)

`AbstractVirtualThreadRuntime` provides two final accessor methods:

- `jdkVirtualThreadPoller()` → reads `Poller.jdkPoller` via `VarHandle` → returns `VirtualThreadPoller` (adaptor)
- `jdkScheduler()` → calls `VirtualThread.builtinScheduler(false)` via `MethodHandle` → returns JDK builtin scheduler's external view

Both handles are resolved in `static {}` using `LoomSecretHelper.LOOKUP` (trusted lookup, needed because `VirtualThread` is a package-private class).

## JdkProxyVirtualThreadRuntime (injected into java.base)

```java
class JdkProxyVirtualThreadRuntime
        extends Poller$PollerGroup
        implements Thread$VirtualThreadScheduler {

    static final MethodHandle _mhAdaptorCtor;   // (Object) → Object
    static final MethodHandle _mhCtor;          // () → Object  [no-arg]
    static final MethodHandle _mhPoll, _mhPollSelector, _mhStart;
    static final MethodHandle _mhOnStart, _mhOnContinue;

    final PollerGroup jdk;
    final Object adaptor;          // JdkVirtualThreadPollerAdaptor instance
    final Object customerPoller;   // user VirtualThreadRuntime instance

    JdkProxyVirtualThreadRuntime(PollerGroup jdkGroup) {
        super(jdkGroup.provider());
        this.jdk = jdkGroup;
        this.adaptor = _mhAdaptorCtor.invokeExact((Object) jdkGroup);
        this.customerPoller = _mhCtor.invokeExact();  // no-arg
    }

    // User methods (MH → customerPoller)
    void poll(...)         { _mhPoll.invokeExact(customerPoller, ...); }
    void pollSelector(...) { _mhPollSelector.invokeExact(customerPoller, ...); }
    void start()           { _mhStart.invokeExact(customerPoller); }
    void onStart(task)     { _mhOnStart.invokeExact(customerPoller, task); }
    void onContinue(task)  { _mhOnContinue.invokeExact(customerPoller, task); }

    // Fallback (direct invokevirtual on jdk field)
    Poller masterPoller()       { return jdk.masterPoller(); }
    List<Poller> readPollers()  { return jdk.readPollers(); }
    List<Poller> writePollers() { return jdk.writePollers(); }
    boolean useLazyUnpark()     { return jdk.useLazyUnpark(); }
}
```

## Configuration (agentArgs)

Arguments are passed via `-javaagent:...=k=v,k2=v2`.

| Parameter | Default | Description |
|---|---|---|
| `jdk.virtualThreadScheduler.poller.implClass` | (required) | Fully-qualified class name of the user `VirtualThreadRuntime` implementation. Must have a public no-arg constructor. |
| `jdk.virtualThreadScheduler.poller.dumpBytecode` | `false` | When `true`, dumps all generated/transformed bytecodes to the current working directory. |

Example:
```bash
java -javaagent:agent.jar=jdk.virtualThreadScheduler.poller.implClass=com.example.MyRuntime,jdk.virtualThreadScheduler.poller.dumpBytecode=true \
     -jar app.jar
```

## User Runtime Implementation

```java
public class MyRuntime extends AbstractVirtualThreadRuntime {

    public MyRuntime() { super(); }  // no-arg constructor required

    @Override
    public void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
        // custom I/O logic, or fallback:
        jdkVirtualThreadPoller().poll(fdVal, event, nanos, isOpen);
    }

    @Override
    public void pollSelector(int fdVal, long nanos) throws IOException {
        jdkVirtualThreadPoller().pollSelector(fdVal, nanos);
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        // custom scheduling logic, or fallback:
        jdkScheduler().onStart(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        jdkScheduler().onContinue(task);
    }
}
```

## Dump Files

When `dumpBytecode=true`, the following files are written to the current directory:

| File | Content |
|---|---|
| `java_lang_VirtualThread_transformed.class` | Transformed VirtualThread (loadCustomScheduler rewritten) |
| `sun_nio_ch_Poller_transformed.class` | Transformed Poller (createPollerGroup rewritten, jdkPoller/POLLER_GROUP fields added/modified) |
| `sun_nio_ch_JdkProxyVirtualThreadRuntime.class` | Proxy: extends PollerGroup + implements VirtualThreadScheduler |
| `io_github_dreamlike_scheduler_agent_JdkVirtualThreadPollerAdaptor.class` | Adaptor: wraps PollerGroup as VirtualThreadPoller |
