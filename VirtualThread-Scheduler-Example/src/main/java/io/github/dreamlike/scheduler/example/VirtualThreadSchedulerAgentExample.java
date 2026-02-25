package io.github.dreamlike.scheduler.example;

import com.sun.tools.attach.VirtualMachine;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;

public final class VirtualThreadSchedulerAgentExample {

    static {
        try {
            selfAttach();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws ClassNotFoundException {
       Class.forName("sun.nio.ch.KQueuePoller");
    }

    // 测试使用
    public static void selfAttach() throws Throwable {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
        var agentJar = Paths.get(
                "VirtualThread-Scheduler-Agent",
                "target",
                "VirtualThread-Scheduler-Agent-1.0-SNAPSHOT.jar"
        ).toAbsolutePath().normalize();
        VirtualMachine virtualMachine = VirtualMachine.attach(pid);
        virtualMachine.loadAgent(agentJar.toString());
        virtualMachine.detach();
    }
}
