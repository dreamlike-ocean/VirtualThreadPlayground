# VirtualThread-Scheduler-Agent（中文）

## 概览

该模块提供一个 Java Agent，同时改写 `sun.nio.ch.Poller` 和 `java.lang.VirtualThread`，创建一个**统一的虚拟线程 Runtime** —— 一个代理对象同时作为 I/O PollerGroup 和 VirtualThread Scheduler（`POLLER_GROUP == DEFAULT_SCHEDULER`）。

类似 Rust 的 Tokio Runtime：一个全局单例同时处理任务调度和异步 I/O 事件分发。

## 接口层次

```
VirtualThreadPoller（纯 I/O）              Thread.VirtualThreadScheduler（JDK，纯调度）
  poll(fdVal, event, nanos, isOpen)          onStart(VirtualThreadTask)
  pollSelector(fdVal, nanos)                 onContinue(VirtualThreadTask)
  start()
          \                                /
           \                              /
            VirtualThreadRuntime（两者的联合）
              extends VirtualThreadPoller, Thread.VirtualThreadScheduler
              （无额外方法）
```

- `VirtualThreadPoller` 和 `VirtualThreadScheduler` 是**平行、独立的接口**
- `VirtualThreadRuntime` 是它们的**联合** —— 面向用户的 API
- JDK Adaptor 只实现 `VirtualThreadPoller`（不需要调度方法桩）

## 工作原理

### Agent 改写两个 JDK 类

**1. `sun.nio.ch.Poller`**（通过 ClassFileTransformer 在首次加载时拦截）：
- 将 `createPollerGroup()` 重命名为 `createPollerGroup0()`
- 新增 `public static Object jdkPoller` 字段
- 新增 `public static Object pollerGroupForScheduler()` 方法，用于访问 `POLLER_GROUP`
- 生成新的 `createPollerGroup()`，用 `JdkProxyVirtualThreadRuntime` 包装结果

**2. `java.lang.VirtualThread`**（通过 `retransformClasses` —— premain 时已被 JVM 加载）：
- 替换 `loadCustomScheduler()` 方法体为 `return (VirtualThreadScheduler) Poller.pollerGroupForScheduler()`
- Agent 设置 `System.setProperty("jdk.virtualThreadScheduler.implClass", "agent-forced")` 强制原始 `<clinit>` 走自定义调度器分支

### 初始化顺序

```
premain → install():
  ① redefineModule: 将 sun.nio.ch + java.lang 开放给 agent 模块
  ② addTransformer（拦截 Poller + VirtualThread）
  ③ retransformClasses(VirtualThread) — 改写 loadCustomScheduler
  ④ defineClass(JdkVirtualThreadPollerAdaptor) 在 App CL 中
  ⑤ Class.forName("sun.nio.ch.Poller", false, null) — 仅加载，transformer 改写字节码
  ⑥ privateLookupIn(Poller) → defineClass(JdkProxyVirtualThreadRuntime)
  install() 结束。此时 Poller 和 VirtualThread 均未初始化。

首次使用虚拟线程 → VirtualThread.<clinit>:
  ① createBuiltinScheduler(true) → ForkJoinPool
  ② createExternalView(builtin) → externalView
  ③ loadCustomScheduler(...) [被改写]:
     → invokestatic Poller.pollerGroupForScheduler() → 触发 Poller.<clinit>:
       → createPollerGroup() [被改写]:
         → createPollerGroup0() → jdkGroup（已 start）
         → proxy = new JdkProxyVirtualThreadRuntime(jdkGroup)
           → adaptor = MH(jdkGroup)，存到 proxy.adaptor 字段
           → Poller.jdkPoller = proxy.adaptor
           → customerPoller = MH() — 无参构造
         → return proxy
       → POLLER_GROUP = proxy
     → return proxy as VirtualThreadScheduler
  ④ DEFAULT_SCHEDULER = proxy  ← 和 POLLER_GROUP 是同一个对象 ✅
  ⑤ EXTERNAL_VIEW = externalView
```

### 运行时访问（惰性，只读）

`AbstractVirtualThreadRuntime` 提供两个 final 访问方法：

- `jdkVirtualThreadPoller()` → 通过 `VarHandle` 读 `Poller.jdkPoller` → 返回 `VirtualThreadPoller`（adaptor）
- `jdkScheduler()` → 通过 `MethodHandle` 调用 `VirtualThread.builtinScheduler(false)` → 返回 JDK 内建调度器的 externalView

两个 handle 在 `static {}` 中通过 `LoomSecretHelper.LOOKUP`（trusted lookup）解析（因为 `VirtualThread` 是 package-private 类）。

## JdkProxyVirtualThreadRuntime（注入到 java.base）

```java
class JdkProxyVirtualThreadRuntime
        extends Poller$PollerGroup
        implements Thread$VirtualThreadScheduler {

    static final MethodHandle _mhAdaptorCtor;   // (Object) → Object
    static final MethodHandle _mhCtor;          // () → Object  [无参]
    static final MethodHandle _mhPoll, _mhPollSelector, _mhStart;
    static final MethodHandle _mhOnStart, _mhOnContinue;

    final PollerGroup jdk;
    final Object adaptor;          // JdkVirtualThreadPollerAdaptor 实例
    final Object customerPoller;   // 用户 VirtualThreadRuntime 实例

    JdkProxyVirtualThreadRuntime(PollerGroup jdkGroup) {
        super(jdkGroup.provider());
        this.jdk = jdkGroup;
        this.adaptor = _mhAdaptorCtor.invokeExact((Object) jdkGroup);
        this.customerPoller = _mhCtor.invokeExact();  // 无参
    }

    // 用户方法 (MH → customerPoller)
    void poll(...)         { _mhPoll.invokeExact(customerPoller, ...); }
    void pollSelector(...) { _mhPollSelector.invokeExact(customerPoller, ...); }
    void start()           { _mhStart.invokeExact(customerPoller); }
    void onStart(task)     { _mhOnStart.invokeExact(customerPoller, task); }
    void onContinue(task)  { _mhOnContinue.invokeExact(customerPoller, task); }

    // 回退方法（直接 invokevirtual）
    Poller masterPoller()       { return jdk.masterPoller(); }
    List<Poller> readPollers()  { return jdk.readPollers(); }
    List<Poller> writePollers() { return jdk.writePollers(); }
    boolean useLazyUnpark()     { return jdk.useLazyUnpark(); }
}
```

## 参数配置（agentArgs）

参数通过 `-javaagent:...=k=v,k2=v2` 传入。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `jdk.virtualThreadScheduler.poller.implClass` | （必填） | 用户自定义 `VirtualThreadRuntime` 实现类全限定名。必须有公开的无参构造函数。 |
| `jdk.virtualThreadScheduler.poller.dumpBytecode` | `false` | 设为 `true` 时，将所有生成/改写的字节码写到当前工作目录。 |

示例：
```bash
java -javaagent:agent.jar=jdk.virtualThreadScheduler.poller.implClass=com.example.MyRuntime,jdk.virtualThreadScheduler.poller.dumpBytecode=true \
     -jar app.jar
```

## 用户 Runtime 实现

```java
public class MyRuntime extends AbstractVirtualThreadRuntime {

    public MyRuntime() { super(); }  // 必须有无参构造

    @Override
    public void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
        // 自定义 I/O 逻辑，或回退：
        jdkVirtualThreadPoller().poll(fdVal, event, nanos, isOpen);
    }

    @Override
    public void pollSelector(int fdVal, long nanos) throws IOException {
        jdkVirtualThreadPoller().pollSelector(fdVal, nanos);
    }

    @Override
    public void onStart(Thread.VirtualThreadTask task) {
        // 自定义调度逻辑，或回退：
        jdkScheduler().onStart(task);
    }

    @Override
    public void onContinue(Thread.VirtualThreadTask task) {
        jdkScheduler().onContinue(task);
    }
}
```

## Dump 文件

当 `dumpBytecode=true` 时，以下文件会写到当前目录：

| 文件 | 内容 |
|---|---|
| `java_lang_VirtualThread_transformed.class` | 改写后的 VirtualThread（loadCustomScheduler 被替换） |
| `sun_nio_ch_Poller_transformed.class` | 改写后的 Poller（createPollerGroup 重写，jdkPoller/POLLER_GROUP 字段新增/修改） |
| `sun_nio_ch_JdkProxyVirtualThreadRuntime.class` | 代理：继承 PollerGroup + 实现 VirtualThreadScheduler |
| `io_github_dreamlike_scheduler_agent_JdkVirtualThreadPollerAdaptor.class` | 适配器：将 PollerGroup 包装为 VirtualThreadPoller |
