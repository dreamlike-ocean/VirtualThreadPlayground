package io.github.dreamlike.scheduler.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VirtualThreadSchedulerAgent {

    private static final String POLL_IMPL_CLASS = "jdk.virtualThreadScheduler.poller.implClass";
    private static final String DUMP_BYTECODE = "jdk.virtualThreadScheduler.poller.dumpBytecode";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    static final String PROXY_RUNTIME_CLASS_NAME = "sun.nio.ch.JdkProxyVirtualThreadRuntime";
    private static final Map<String, String> args = new HashMap<>();
    private static String pollerImplClass = null;
    private static boolean dumpBytecode = false;

    private VirtualThreadSchedulerAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static void install(String agentArgs, Instrumentation instrumentation) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        initArgs(agentArgs);

        // Force VirtualThread.<clinit> to take the "custom scheduler" branch
        // by ensuring the implClass property is non-null. The rewritten loadCustomScheduler
        // ignores this value and returns Poller.POLLER_GROUP directly.
        System.setProperty("jdk.virtualThreadScheduler.implClass", "agent-forced");

        ClassFileTransformer transformer = new RuntimeRewriteTransformer();
        System.out.println("[VirtualThreadSchedulerAgent] installing agent; retransform support = "
                + instrumentation.isRetransformClassesSupported()
                + "; dumpBytecode = " + dumpBytecode);

        try {
            // Open java.base packages to the agent module
            Module javaBase = Object.class.getModule();
            HashSet<Module> modules = new HashSet<>();
            Module agentModule = VirtualThreadSchedulerAgent.class.getModule();
            modules.add(agentModule);
            if (agentModule.isNamed()) {
                modules.add(Thread.currentThread().getContextClassLoader().getUnnamedModule());
            }
            instrumentation.redefineModule(
                    javaBase,
                    Set.of(),
                    Map.of(),
                    Map.of("sun.nio.ch", modules, "java.lang", modules),
                    Set.of(),
                    Map.of()
            );

            // 1. Register transformer FIRST — must be in place before Poller/VirtualThread are loaded
            instrumentation.addTransformer(transformer, true);

            // 1.5. VirtualThread is loaded early by JVM bootstrap — retransform it so our
            //      transformer can rewrite loadCustomScheduler. Its <clinit> has NOT run yet
            //      (no virtual threads have been created), so the rewritten loadCustomScheduler
            //      will be in effect when <clinit> eventually executes.
            instrumentation.retransformClasses(Class.forName("java.lang.VirtualThread", false, null));

            // 2. Define the adaptor class in App ClassLoader (wraps JDK PollerGroup -> VirtualThreadPoller)
            MethodHandles.Lookup currentLookup = MethodHandles.lookup();
            byte[] adaptorBytes = AgentBytecodeToolkit.jdkPollerGroupToVirtualThreadPollerAdaptor();
            dumpIfNeeded(AgentBytecodeToolkit.JDK_POLLER_GROUP_ADAPTOR_CLASS_NAME, adaptorBytes);
            currentLookup.defineClass(adaptorBytes);

            // 3. Load (but NOT initialize) Poller — triggers transformer to rewrite bytecode
            Class<?> pollerAnchor = Class.forName("sun.nio.ch.Poller", false, null);
            MethodHandles.Lookup pollerLookup = MethodHandles.privateLookupIn(pollerAnchor, currentLookup);

            // 4. Inject JdkProxyVirtualThreadRuntime into sun.nio.ch
            byte[] proxyBytes = AgentBytecodeToolkit.jdkProxyVirtualThreadRuntime(PROXY_RUNTIME_CLASS_NAME, pollerImplClass);
            dumpIfNeeded(PROXY_RUNTIME_CLASS_NAME, proxyBytes);
            pollerLookup.defineClass(proxyBytes);
        } catch (Throwable t) {
            System.err.println("[VirtualThreadSchedulerAgent] failed to install agent");
            t.printStackTrace(System.err);
            throw (t instanceof RuntimeException runtimeException)
                    ? runtimeException
                    : new RuntimeException("Failed to install VirtualThreadSchedulerAgent", t);
        }
    }

    private static void initArgs(String agentArgs) {
        String[] split = Objects.requireNonNullElse(agentArgs, "").split(",");
        Map<String, String> argMap = Stream.of(split)
                .map(s -> s.split("="))
                .filter(s -> s.length == 2)
                .collect(Collectors.toMap(s -> s[0], s -> s[1], (a, b) -> a));
        args.putAll(argMap);
        pollerImplClass = args.get(POLL_IMPL_CLASS);
        if (pollerImplClass == null) {
            throw new NullPointerException(POLL_IMPL_CLASS + " is null");
        }
        dumpBytecode = parseBooleanArg(args.get(DUMP_BYTECODE), false);
    }

    private static boolean parseBooleanArg(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "y", "on" -> true;
            case "0", "false", "no", "n", "off" -> false;
            default -> defaultValue;
        };
    }

    static void dumpIfNeeded(String className, byte[] bytecode) {
        if (!dumpBytecode) {
            return;
        }
        try {
            String fileName = className.replace('.', '_') + ".class";
            Path path = Path.of(fileName);
            Files.write(path, bytecode);
            System.out.println("[VirtualThreadSchedulerAgent] dumped bytecode: " + path.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[VirtualThreadSchedulerAgent] failed to dump bytecode for " + className);
            e.printStackTrace(System.err);
        }
    }

    private static final class RuntimeRewriteTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module,
                                ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className.equals("sun/nio/ch/Poller")) {
                System.out.println("[Transformer] transforming sun.nio.ch.Poller");
                byte[] transformed = AgentBytecodeToolkit.transformPoller(classfileBuffer);
                dumpIfNeeded("sun.nio.ch.Poller_transformed", transformed);
                return transformed;
            }
            if (className.equals("java/lang/VirtualThread")) {
                System.out.println("[Transformer] transforming java.lang.VirtualThread");
                byte[] transformed = AgentBytecodeToolkit.transformVirtualThread(classfileBuffer);
                dumpIfNeeded("java.lang.VirtualThread_transformed", transformed);
                return transformed;
            }
            return classfileBuffer;
        }
    }
}
