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
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final String PROXY_POLLER_CLASS_NAME = "sun.nio.ch.JdkPollerProxy";
    private static final Map<String, String> args = new HashMap<>();
    private static String pollerImplClass = null;

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
        System.setProperty("jdk.pollerMode", "1");
        ClassFileTransformer transformer = new PollerRewriteTransformer();
        System.out.println("[VirtualThreadSchedulerAgent] installing agent; retransform support = "
                + instrumentation.isRetransformClassesSupported());

        try {
            instrumentation.addTransformer(transformer, true);

            // 把 java.base 的 sun.nio.ch 包 打开 给 agent 模块（不是 export）。否则 privateLookupIn 会被模块访问检查挡住
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
            // 注入一个特洛伊木马 只是为了把我的Poller带进去
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

    // 入侵修改sun.nio.ch.DefaultPollerProvider
    private static byte[] spiPollerProvider(byte[] defaultPollerProviderBytecode) {
        ClassFile classFile = ClassFile.of();
        ClassModel defaultPollerProviderCodeModel = classFile.parse(defaultPollerProviderBytecode);
        ClassDesc providerDesc = defaultPollerProviderCodeModel.thisClass().asSymbol();

        return classFile.build(providerDesc, classBuilder -> {
            for (ClassElement classElement : defaultPollerProviderCodeModel) {
                if (classElement instanceof MethodModel methodModel) {
                    //  read poller和write poller都强制设置为1
                    // supportReadOps和supportWriteOps都强制返回true 允许使用“async”方案
                    boolean needSkip = switch (methodModel.methodName().stringValue()) {
                        case "defaultReadPollers" ->
                                returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultReadPollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
                        case "defaultWritePollers" ->
                                returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultWritePollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
                        case "supportReadOps" ->
                                returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportReadOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
                        case "supportWriteOps" ->
                                returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportWriteOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
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
            MethodTypeDesc proxyCtorDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;I)V");
            ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");

            // Poller readPoller(boolean subPoller) throws IOException {
            //     return new sun.nio.ch.JdkPollerProxy(readPoller0(subPoller));
            // }
            classBuilder.withMethod("readPoller", pollerFactoryDesc, 0, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(cb -> {
                    cb.new_(proxyDesc);
                    cb.dup();
                    cb.aload(0);
                    cb.iload(1);
                    cb.invokevirtual(providerDesc, "readPoller0", pollerFactoryDesc);
                    cb.iconst_1();
                    cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, proxyCtorDesc);
                    cb.areturn();
                });
            });

            // Poller writePoller(boolean subPoller) throws IOException {
            //     return new sun.nio.ch.JdkPollerProxy(writePoller0(subPoller));
            // }
            classBuilder.withMethod("writePoller", pollerFactoryDesc, 0, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(cb -> {
                    cb.new_(proxyDesc);
                    cb.dup();
                    cb.aload(0);
                    cb.iload(1);
                    cb.invokevirtual(providerDesc, "writePoller0", pollerFactoryDesc);
                    cb.iconst_2();
                    cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, proxyCtorDesc);
                    cb.areturn();
                });
            });
        });
    }

    private static boolean returnOneOrTrue(Utf8Entry name,
                                           Utf8Entry descriptor,
                                           int methodFlags, ClassBuilder classBuilder) {
        classBuilder.withMethod(name, descriptor, methodFlags, mb -> {
            mb.withCode(cb -> {
                cb.iconst_1();
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
