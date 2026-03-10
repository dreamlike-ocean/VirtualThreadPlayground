package io.github.dreamlike.scheduler.example;

import java.util.Scanner;

public final class VirtualThreadSchedulerAgentExample {

    public static void main(String[] args) throws ClassNotFoundException {
       Class.forName("sun.nio.ch.KQueuePoller");
       new Scanner(System.in).nextLine();
    }

}
