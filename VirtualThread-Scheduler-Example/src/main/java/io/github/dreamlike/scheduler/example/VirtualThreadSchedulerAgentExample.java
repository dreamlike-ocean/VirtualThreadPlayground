package io.github.dreamlike.scheduler.example;

import com.sun.tools.attach.VirtualMachine;

import java.lang.management.ManagementFactory;
import java.nio.file.Paths;

public final class VirtualThreadSchedulerAgentExample {

    public static void main(String[] args) throws ClassNotFoundException {
       Class.forName("sun.nio.ch.KQueuePoller");
    }

}
