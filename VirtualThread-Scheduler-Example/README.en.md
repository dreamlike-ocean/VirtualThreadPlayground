# VirtualThread-Scheduler-Example

## Design

### AbstractVirtualThreadRuntime — Unified Virtual Thread Runtime Base Class

`AbstractVirtualThreadRuntime` is the core abstraction of the framework. It implements the `VirtualThreadRuntime` interface, which extends both `VirtualThreadPoller` (I/O polling) and `Thread.VirtualThreadScheduler` (thread scheduling), unifying these two independent concerns into a single runtime — similar to Rust's Tokio Runtime.

```
VirtualThreadPoller (pure I/O)           Thread.VirtualThreadScheduler (pure scheduling)
  poll / pollSelector / start              onStart / onContinue / schedule
          \                                /
           VirtualThreadRuntime (union interface)
                    |
       AbstractVirtualThreadRuntime (abstract base)
         jdkVirtualThreadPoller() → JDK original PollerGroup
         jdkScheduler()           → JDK builtin scheduler
```

**Key design decisions**:

- **Lazy read-only access to JDK internals**: Reads `Poller.jdkPoller` (adaptor wrapping the JDK PollerGroup) via `VarHandle`, and calls `VirtualThread.builtinScheduler(false)` (the builtin scheduler's external view) via `MethodHandle`. Both handles are resolved in `static {}` without triggering target class initialization.
- **No-arg constructor**: User runtimes require no constructor parameters. JDK poller and scheduler fallbacks are obtained lazily at runtime via the handles above, at which point the target classes are guaranteed to be initialized.
- **Default delegation**: `start()` delegates to the JDK poller, `onStart()`/`onContinue()` delegate to the JDK scheduler. Users only override what they care about.

### CustomerVirtualThreadRuntime — Example Implementation

`CustomerVirtualThreadRuntime` is a full custom virtual thread runtime implementation, demonstrating how to take over both I/O polling and thread scheduling simultaneously.

#### I/O Polling

Overrides `poll()` and `pollSelector()` to insert custom logic before/after delegating to the JDK PollerGroup (e.g., integrating with Netty EventLoop, io_uring, etc.). The current example simply logs and falls back.

#### Thread Scheduling — DispatcherContext System

The core scheduling model is based on `DispatcherContext`, a sealed class hierarchy:

| Context Type | Purpose | Executor Inheritance |
|---|---|---|
| `DynamicDispatcherContext` | Default mode, supports runtime executor switching | Child virtual threads inherit parent's executor |
| `PinningContext` | Fixed executor, no switching | Child virtual threads inherit the same executor |
| `PollerContext` | Dedicated to `POLLER_PER_CARRIER` mode read poller threads | Not inheritable |
| `EmptyContext` | Sentinel value, no custom executor | — |

**Scheduling flow** (`onStart`/`onContinue`):

```
onStart(task):
  1. Task already has a DispatcherContext? → submit via its executor
  2. Parent virtual thread has a DispatcherContext? → inherit and submit
  3. Neither? → fallback to jdkScheduler()
```

#### Static Utility Methods

| Method | Description |
|---|---|
| `propagateExecutor(executor, runnable)` | Propagates a custom executor in the current scope; child virtual threads inherit it automatically |
| `newThread(executor, runnable)` | Creates a virtual thread bound to a custom executor |
| `switchExecutor(executor, task)` | Dynamically switches executor during virtual thread execution (Dynamic mode only) |
| `traceThreads()` | Traces the full DispatcherContext chain of the current virtual thread, returns thread list |

#### Scheduled Tasks — schedule

Overrides `schedule(task, delay, unit)`: if the current DispatcherContext's executor supports scheduling (`supportSchedule() == true`), delegates to it; otherwise falls back to the JDK builtin scheduler.

#### AwareShutdownExecutor

Custom executor interface extending basic `execute(Runnable, Thread)` semantics:

- `execute(runnable, preferredThread)` — submit task, optionally specifying preferred carrier thread
- `supportSchedule()` — whether scheduled task submission is supported
- `schedule(task, delay, unit)` — scheduled submission
- `adapt(Executor)` — adapts standard `Executor`/`ScheduledExecutorService` into `AwareShutdownExecutor`

## Build

Requires [Project Loom EA JDK](https://github.com/openjdk/loom) (JDK 27-internal).

```bash
# Switch to loom-ea JDK
sdk use java loom-ea

# Build all modules (skip tests)
mvn clean package -DskipTests

# Build and run tests
mvn clean install -DskipTests && mvn test -pl VirtualThread-Scheduler-Example
```

## Run

### Using the launch script

```bash
sdk use java loom-ea
./VirtualThread-Scheduler-Example/run.sh
```

The script automatically runs `mvn package`, then starts the Example application with `-javaagent` and opens JDWP remote debug on port 5005.

### Manual run

```bash
sdk use java loom-ea
mvn clean package -DskipTests -q

java \
  -javaagent:VirtualThread-Scheduler-Agent/target/VirtualThread-Scheduler-Agent-1.0-SNAPSHOT.jar=jdk.virtualThreadScheduler.poller.implClass=io.github.dreamlike.scheduler.example.CustomerVirtualThreadRuntime \
  -jar VirtualThread-Scheduler-Example/target/VirtualThread-Scheduler-Example-1.0-SNAPSHOT.jar
```

### Agent Parameters

| Parameter | Default | Description |
|---|---|---|
| `jdk.virtualThreadScheduler.poller.implClass` | (required) | Fully-qualified class name of user `VirtualThreadRuntime` implementation |
| `jdk.virtualThreadScheduler.poller.dumpBytecode` | `false` | When `true`, dumps generated bytecodes to the current directory |

### Expected Output

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

Both I/O poll and virtual thread scheduling go through the custom runtime.
