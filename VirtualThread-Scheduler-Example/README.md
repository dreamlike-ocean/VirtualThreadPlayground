# VirtualThread-Scheduler-Example

## 设计思想

### AbstractVirtualThreadRuntime — 统一虚拟线程 Runtime 基类

`AbstractVirtualThreadRuntime` 是整个框架的核心抽象，它实现了 `VirtualThreadRuntime` 接口——该接口同时继承了 `VirtualThreadPoller`（I/O 轮询）和 `Thread.VirtualThreadScheduler`（线程调度），将这两个原本独立的关注点统一到一个 Runtime 里，类似 Rust 的 Tokio Runtime。

```
VirtualThreadPoller（纯 I/O）            Thread.VirtualThreadScheduler（纯调度）
  poll / pollSelector / start              onStart / onContinue / schedule
          \                                /
           VirtualThreadRuntime（联合接口）
                    |
       AbstractVirtualThreadRuntime（抽象基类）
         jdkVirtualThreadPoller() → JDK 原始 PollerGroup
         jdkScheduler()           → JDK 内建调度器
```

**关键设计**：

- **惰性只读访问 JDK 内部状态**：通过 `VarHandle` 读取 `Poller.jdkPoller`（JDK 原始 PollerGroup 的适配器），通过 `MethodHandle` 调用 `VirtualThread.builtinScheduler(false)`（JDK 内建调度器的 externalView）。两者都在 `static {}` 中仅解析 handle，不触发目标类初始化。
- **无参构造**：用户 Runtime 不需要构造参数。JDK 的 poller 和 scheduler fallback 在运行时通过上述 handle 惰性获取，此时目标类一定已完成初始化。
- **默认委托**：`start()` 委托给 JDK poller，`onStart()`/`onContinue()` 委托给 JDK scheduler。用户只需覆盖自己关心的方法。

### CustomerVirtualThreadRuntime — 示例实现

`CustomerVirtualThreadRuntime` 是一个完整的自定义虚拟线程 Runtime 实现，演示如何同时接管 I/O 轮询和线程调度。

#### I/O 轮询

覆盖 `poll()` 和 `pollSelector()`，在委托给 JDK PollerGroup 之前/之后可以插入自定义逻辑（如集成 Netty EventLoop、io_uring 等）。当前示例仅做日志记录后 fallback。

#### 线程调度 — DispatcherContext 体系

核心调度模型基于 `DispatcherContext`，一个 sealed 类层次：

| Context 类型 | 用途 | Executor 继承行为 |
|---|---|---|
| `DynamicDispatcherContext` | 默认模式，支持运行时动态切换 executor | 子虚拟线程继承父 executor |
| `PinningContext` | 固定 executor，不可切换 | 子虚拟线程继承同一个 executor |
| `PollerContext` | 专用于 `POLLER_PER_CARRIER` 模式的 read poller 线程 | 不可继承 |
| `EmptyContext` | 哨兵值，表示无自定义 executor | — |

**调度流程**（`onStart`/`onContinue`）：

```
onStart(task):
  1. task 已有 DispatcherContext? → 用其 executor 投递
  2. 父虚拟线程有 DispatcherContext? → 继承并投递
  3. 都没有 → fallback 到 jdkScheduler()
```

#### 静态工具方法

| 方法 | 说明 |
|---|---|
| `propagateExecutor(executor, runnable)` | 在当前作用域传播一个自定义 executor，子虚拟线程会自动继承 |
| `newThread(executor, runnable)` | 创建一个绑定了自定义 executor 的虚拟线程 |
| `switchExecutor(executor, task)` | 在虚拟线程运行中动态切换 executor（仅 Dynamic 模式） |
| `traceThreads()` | 追溯当前虚拟线程的完整 DispatcherContext 链，返回线程列表 |

#### 定时任务 — schedule

覆盖 `schedule(task, delay, unit)`：如果当前 DispatcherContext 的 executor 支持定时调度（`supportSchedule() == true`），则委托给它；否则 fallback 到 JDK 内建调度器。

#### AwareShutdownExecutor

自定义 executor 接口，扩展了基本的 `execute(Runnable, Thread)` 语义：

- `execute(runnable, preferredThread)` — 投递任务，可指定偏好 carrier 线程
- `supportSchedule()` — 是否支持定时调度
- `schedule(task, delay, unit)` — 定时投递
- `adapt(Executor)` — 将标准 `Executor`/`ScheduledExecutorService` 适配为 `AwareShutdownExecutor`

## 构建

需要 [Project Loom EA JDK](https://github.com/openjdk/loom)（JDK 27-internal）。

```bash
# 切换到 loom-ea JDK
sdk use java loom-ea

# 构建全部模块（跳过测试）
mvn clean package -DskipTests

# 构建并运行测试
mvn clean install -DskipTests && mvn test -pl VirtualThread-Scheduler-Example
```

## 运行

### 使用启动脚本

```bash
sdk use java loom-ea
./VirtualThread-Scheduler-Example/run.sh
```

脚本会自动执行 `mvn package`，然后以 `-javaagent` 方式启动 Example 应用，并在 5005 端口开启 JDWP 远程调试。

### 手动运行

```bash
sdk use java loom-ea
mvn clean package -DskipTests -q

java \
  -javaagent:VirtualThread-Scheduler-Agent/target/VirtualThread-Scheduler-Agent-1.0-SNAPSHOT.jar=jdk.virtualThreadScheduler.poller.implClass=io.github.dreamlike.scheduler.example.CustomerVirtualThreadRuntime \
  -jar VirtualThread-Scheduler-Example/target/VirtualThread-Scheduler-Example-1.0-SNAPSHOT.jar
```

### Agent 参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `jdk.virtualThreadScheduler.poller.implClass` | （必填） | 用户 `VirtualThreadRuntime` 实现类全限定名 |
| `jdk.virtualThreadScheduler.poller.dumpBytecode` | `false` | 设为 `true` 时将生成的字节码 dump 到当前目录 |

### 预期输出

```
[VirtualThreadSchedulerAgent] installing agent; retransform support = true; dumpBytecode = false
[Transformer] transforming java.lang.VirtualThread
[Transformer] transforming sun.nio.ch.Poller
[CustomerRuntime] poll fdVal=8 event=1
[CustomerRuntime] poll fdVal=9 event=4
[CustomerRuntime] poll fdVal=9 event=1
socket recv: Hello World!
[CustomerRuntime] poll fdVal=8 event=1
```

I/O poll 和虚拟线程调度全部经过自定义 Runtime。
