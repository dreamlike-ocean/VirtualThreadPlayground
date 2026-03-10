# VirtualThread-Scheduler-Example

Build the agent and example modules and run the demo application with the agent attached:

```bash
mvn -pl VirtualThread-Scheduler-Agent,VirtualThread-Scheduler-Example -am package
java -javaagent:VirtualThread-Scheduler-Agent/target/VirtualThread-Scheduler-Agent-1.0-SNAPSHOT.jar \
     -cp VirtualThread-Scheduler-Example/target/VirtualThread-Scheduler-Example-1.0-SNAPSHOT.jar \
     io.github.dreamlike.scheduler.example.VirtualThreadSchedulerAgentExample
```

The JVM will print `[VirtualThreadSchedulerAgent]` lines when bootstrap classes are loaded, proving that the agent intercepts JDK internals without modifying them.
