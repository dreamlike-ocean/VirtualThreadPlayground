package io.github.dreamlike.scheduler.agent;

import java.lang.classfile.*;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VirtualThreadSchedulerAgent {

    private static final String POLL_IMPL_CLASS = "jdk.virtualThreadScheduler.poller.implClass";
    private static final String SUPPORT_READ_OPS = "jdk.virtualThreadScheduler.poller.supportReadOps";
    private static final String SUPPORT_WRITE_OPS = "jdk.virtualThreadScheduler.poller.supportWriteOps";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final String PROXY_POLLER_CLASS_NAME = "sun.nio.ch.JdkPollerProxy";
    private static final Map<String, String> args = new HashMap<>();
    private static String pollerImplClass = null;
    private static boolean supportReadOps = true;
    private static boolean supportWriteOps = true;

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
        ClassFileTransformer transformer = new PollerRewriteTransformer();
        System.out.println("[VirtualThreadSchedulerAgent] installing agent; retransform support = "
                + instrumentation.isRetransformClassesSupported());

        try {
            instrumentation.addTransformer(transformer, true);

            // 把 java.base 的 sun.nio.ch 包 open 给 agent 模块（不是 export）；否则 privateLookupIn 会被模块访问检查挡住。
            // Open java.base: sun.nio.ch to the agent module (not export); otherwise privateLookupIn will fail module access checks.
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
                    Map.of("sun.nio.ch", modules),
                    Set.of(),
                    Map.of()
            );

            MethodHandles.Lookup currentLookup = MethodHandles.lookup();
            currentLookup.defineClass(AgentBytecodeToolkit.jdkPollerToVirtualThreadPollerAdaptor());
            Class<?> anchor = Class.forName("sun.nio.ch.DefaultPollerProvider", false, null);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(anchor, currentLookup);
            // 注入一个“特洛伊木马”类，只是为了把自定义 Poller 相关类带进 java.base。
            // Inject a "Trojan" class only to bring our custom Poller-related classes into java.base.
            lookup.defineClass(AgentBytecodeToolkit.jdkPollerProxy(PROXY_POLLER_CLASS_NAME, pollerImplClass));
        } catch (Throwable t) {
            System.err.println("[VirtualThreadSchedulerAgent] failed to register transformer");
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

        supportReadOps = parseBooleanArg(args.get(SUPPORT_READ_OPS), true);
        supportWriteOps = parseBooleanArg(args.get(SUPPORT_WRITE_OPS), true);
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

    private static final class PollerRewriteTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module,
                                ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (className.equals("sun/nio/ch/DefaultPollerProvider")) {
                return spiPollerProvider(classfileBuffer);
            }
            return classfileBuffer;
        }
    }

    // 入侵/改写 sun.nio.ch.DefaultPollerProvider。
    // Patch/Rewrite sun.nio.ch.DefaultPollerProvider.
    private static byte[] spiPollerProvider(byte[] defaultPollerProviderBytecode) {
        ClassFile classFile = ClassFile.of();
        ClassModel defaultPollerProviderCodeModel = classFile.parse(defaultPollerProviderBytecode);
        ClassDesc providerDesc = defaultPollerProviderCodeModel.thisClass().asSymbol();

        return classFile.build(providerDesc, classBuilder -> {
            for (ClassElement classElement : defaultPollerProviderCodeModel) {
                if (classElement instanceof MethodModel methodModel) {
                    // supportReadOps/supportWriteOps 按 agentArgs 配置返回 true/false，用于控制是否启用 "async" 方案。
                    // supportReadOps/supportWriteOps return true/false based on agentArgs to control whether "async" mode is enabled.
                    boolean needSkip = switch (methodModel.methodName().stringValue()) {
                        case "supportReadOps" ->
                                returnConst(classBuilder.constantPool().utf8Entry("supportReadOps"), classBuilder.constantPool().utf8Entry("()Z"), supportReadOps ? 1 : 0, 0, classBuilder);
                        case "supportWriteOps" ->
                                returnConst(classBuilder.constantPool().utf8Entry("supportWriteOps"), classBuilder.constantPool().utf8Entry("()Z"), supportWriteOps ? 1 : 0, 0, classBuilder);
                        case "readPoller", "writePoller" ->
                            moveToInternal(methodModel, classBuilder);
                        default -> false;
                    };
                    if (needSkip) {
                        continue;
                    }
                }
                classBuilder.with(classElement);
            }

            ClassDesc proxyDesc = ClassDesc.of(PROXY_POLLER_CLASS_NAME);
            MethodTypeDesc pollerFactoryDesc = MethodTypeDesc.ofDescriptor("(Z)Lsun/nio/ch/Poller;");
            MethodTypeDesc proxyCtorDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IZ)V");
            ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");

            // 读 Poller：先调用 readPoller0，再用 JdkPollerProxy 包装，并传入 mode=1。
            // Read poller: call readPoller0 first, then wrap with JdkPollerProxy and pass mode=1.
            classBuilder.withMethod("readPoller", pollerFactoryDesc, 0, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(cb -> {
                    cb.new_(proxyDesc);
                    cb.dup();
                    cb.aload(0);
                    cb.iload(1);
                    cb.invokevirtual(providerDesc, "readPoller0", pollerFactoryDesc);
                    cb.iconst_1();
                    cb.iload(1);
                    cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, proxyCtorDesc);
                    cb.areturn();
                });
            });

            // 写 Poller：先调用 writePoller0，再用 JdkPollerProxy 包装，并传入 mode=2。
            // Write poller: call writePoller0 first, then wrap with JdkPollerProxy and pass mode=2.
            classBuilder.withMethod("writePoller", pollerFactoryDesc, 0, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(cb -> {
                    cb.new_(proxyDesc);
                    cb.dup();
                    cb.aload(0);
                    cb.iload(1);
                    cb.invokevirtual(providerDesc, "writePoller0", pollerFactoryDesc);
                    cb.iconst_2();
                    cb.iload(1);
                    cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, proxyCtorDesc);
                    cb.areturn();
                });
            });
        });
    }

    private static boolean returnConst(Utf8Entry name,
                                       Utf8Entry descriptor,
                                       int constant,
                                       int methodFlags,
                                       ClassBuilder classBuilder) {
        classBuilder.withMethod(name, descriptor, methodFlags, mb -> {
            mb.withCode(cb -> {
                if (constant == 0) {
                    cb.iconst_0();
                } else {
                    cb.iconst_1();
                }
                cb.ireturn();
            });
        });
        return true;
    }

    private static boolean moveToInternal(MethodModel method, ClassBuilder classBuilder) {
        classBuilder.withMethod(method.methodName().stringValue() + "0", method.methodTypeSymbol(), method.flags().flagsMask(), mb -> mb.transform(method, MethodTransform.ACCEPT_ALL));
        return true;
    }
}
