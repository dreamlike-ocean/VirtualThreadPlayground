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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            // Define the proxy class *into java.base* (same module/package as sun.nio.ch.*),
            // so that java.base classes can link to it. Putting it on the bootstrap classpath
            // would place it in the bootstrap unnamed module, which java.base does not read.
            Module javaBase = Object.class.getModule();
            Module agentModule = VirtualThreadSchedulerAgent.class.getModule();
            instrumentation.redefineModule(
                    javaBase,
                    Set.of(),
                    Map.of(),
                    Map.of("sun.nio.ch", Set.of(agentModule)),
                    Set.of(),
                    Map.of()
            );

            Class<?> anchor = Class.forName("sun.nio.ch.DefaultPollerProvider", false, null);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
            lookup.defineClass(jdkPollerProxy());

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

    private static byte[] spiPollerProvider(byte[] defaultPollerProviderBytecode) {
        ClassFile classFile = ClassFile.of();
        ClassModel defaultPollerProviderCodeModel = classFile.parse(defaultPollerProviderBytecode);

        return classFile.build(defaultPollerProviderCodeModel.thisClass().asSymbol(), classBuilder -> {
            for (ClassElement classElement : defaultPollerProviderCodeModel) {
                if (classElement instanceof MethodModel methodModel) {
                    boolean needHack = switch (methodModel.methodName().stringValue()) {
                        case "defaultReadPollers", "defaultWritePollers", "supportReadOps", "supportWriteOps",
                             "readPoller", "writePoller" -> true;
                        default -> false;
                    };
                    if (needHack) {
                        continue;
                    }
                }
                classBuilder.with(classElement);
            }

            returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultReadPollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultWritePollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportReadOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportWriteOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
            spiPoller(classBuilder);
            jdkPoller("readPoller", classBuilder);
            jdkPoller("writePoller", classBuilder);
        });
    }

    private static void returnOneOrTrue(Utf8Entry name,
                                        Utf8Entry descriptor,
                                        int methodFlags, ClassBuilder classBuilder) {
        classBuilder.withMethod(name, descriptor, methodFlags, mb -> {
            mb.withCode(cb -> {
                cb.iconst_1();
                cb.ireturn();
            });
        });
    }

    private static void jdkPoller(String methodName, ClassBuilder classBuilder) {
        classBuilder.withMethod(methodName, MethodTypeDesc.ofDescriptor("(Z)Lsun/nio/ch/Poller;"), 0, mb -> {
            mb.withCode(cb -> {
                cb.invokestatic(ClassDesc.ofDescriptor("Lsun/nio/ch/DefaultPollerProvider;"), "spiPoller", MethodTypeDesc.ofDescriptor("()Lsun/nio/ch/Poller;"));
                cb.areturn();
            });
            mb.with(ExceptionsAttribute.ofSymbols(ClassDesc.ofDescriptor("Ljava/io/IOException;")));
        });
    }

    private static void spiPoller(ClassBuilder classBuilder) {
        classBuilder.withMethod("spiPoller",
                MethodTypeDesc.ofDescriptor("()Lsun/nio/ch/Poller;"),
                AccessFlag.STATIC.mask() | AccessFlag.PUBLIC.mask(), mb -> {
                    mb.with(ExceptionsAttribute.ofSymbols(ClassDesc.ofDescriptor("Ljava/io/IOException;")));
                    mb.withCode(cb -> {
                        ClassDesc proxyDesc = ClassDesc.of(PROXY_POLLER_CLASS_NAME);
                        cb.new_(proxyDesc);
                        cb.dup();
                        cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                        cb.areturn();
                    });
                });
    }

    private static byte[] jdkPollerProxy() {
        ClassFile classFile = ClassFile.of();
        String realPollerFieldName = "_realPoller";
        ClassDesc pollerImplDesc = ClassDesc.of(pollerImplClass);
        ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");
        ClassDesc proxyDesc = ClassDesc.of(PROXY_POLLER_CLASS_NAME);
        MethodTypeDesc implReadDesc = MethodTypeDesc.ofDescriptor("(I[BIIJLjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc implWriteDesc = MethodTypeDesc.ofDescriptor("(I[BIILjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc implStartDesc = MethodTypeDesc.ofDescriptor("(I)V");
        MethodTypeDesc implStopDesc = MethodTypeDesc.ofDescriptor("(IZ)V");
        MethodTypeDesc pollDesc = MethodTypeDesc.ofDescriptor("(I)I");
        MethodTypeDesc closeDesc = MethodTypeDesc.ofDescriptor("()V");
        return classFile.build(proxyDesc, classBuilder -> {
            classBuilder.withSuperclass(ClassDesc.of("sun.nio.ch", "Poller"));
            classBuilder.withField(realPollerFieldName, pollerImplDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.aload(0);
                    cb.invokespecial(ClassDesc.of("sun.nio.ch", "Poller"), ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    cb.return_();
                });
            });
            classBuilder.withMethod(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(), methodBuilder -> {
                methodBuilder.withCode(codeBuilder -> {
                    Label tryStart = codeBuilder.newLabel();
                    Label tryEnd = codeBuilder.newLabel();
                    Label catchLabel = codeBuilder.newLabel();
                    Label returnLabel = codeBuilder.newLabel();

                    ClassDesc threadDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread;");
                    ClassDesc classDesc = ClassDesc.ofDescriptor("Ljava/lang/Class;");
                    ClassDesc constructorDesc = ClassDesc.ofDescriptor("Ljava/lang/reflect/Constructor;");
                    ClassDesc objectDesc = ClassDesc.ofDescriptor("Ljava/lang/Object;");
                    ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
                    ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");

                    String errorMessage = "init " + pollerImplClass + " fail!";

                    codeBuilder.labelBinding(tryStart);
                    codeBuilder.invokestatic(threadDesc, "currentThread", MethodTypeDesc.ofDescriptor("()Ljava/lang/Thread;"));
                    codeBuilder.invokevirtual(threadDesc, "getContextClassLoader", MethodTypeDesc.ofDescriptor("()Ljava/lang/ClassLoader;"));
                    codeBuilder.astore(0);

                    codeBuilder.ldc(pollerImplClass);
                    codeBuilder.iconst_1();
                    codeBuilder.aload(0);
                    codeBuilder.invokestatic(classDesc, "forName", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                    codeBuilder.astore(1);

                    codeBuilder.aload(1);
                    codeBuilder.iconst_0();
                    codeBuilder.anewarray(classDesc);
                    codeBuilder.invokevirtual(classDesc, "getConstructor", MethodTypeDesc.ofDescriptor("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"));
                    codeBuilder.iconst_0();
                    codeBuilder.anewarray(objectDesc);
                    codeBuilder.invokevirtual(constructorDesc, "newInstance", MethodTypeDesc.ofDescriptor("([Ljava/lang/Object;)Ljava/lang/Object;"));
                    codeBuilder.checkcast(pollerImplDesc);
                    codeBuilder.putstatic(proxyDesc, realPollerFieldName, pollerImplDesc);

                    codeBuilder.labelBinding(tryEnd);
                    codeBuilder.branch(Opcode.GOTO, returnLabel);

                    codeBuilder.labelBinding(catchLabel);
                    codeBuilder.astore(0);
                    codeBuilder.new_(runtimeExceptionDesc);
                    codeBuilder.dup();
                    codeBuilder.ldc(errorMessage);
                    codeBuilder.aload(0);
                    codeBuilder.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME, MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
                    codeBuilder.athrow();

                    codeBuilder.labelBinding(returnLabel);
                    codeBuilder.return_();

                    codeBuilder.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
                });
            });

            classBuilder.withMethod("implRead", implReadDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.lload(5);
                    cb.aload(7);
                    cb.invokevirtual(pollerImplDesc, "implRead", implReadDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implWrite", implWriteDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.aload(5);
                    cb.invokevirtual(pollerImplDesc, "implWrite", implWriteDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implStartPoll", implStartDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.iload(1);
                    cb.invokevirtual(pollerImplDesc, "implStartPoll", implStartDesc);
                    cb.return_();
                });
            });

            classBuilder.withMethod("implStopPoll", implStopDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.iload(1);
                    cb.iload(2);
                    cb.invokevirtual(pollerImplDesc, "implStopPoll", implStopDesc);
                    cb.return_();
                });
            });

            classBuilder.withMethod("poll", pollDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.iload(1);
                    cb.invokevirtual(pollerImplDesc, "poll", pollDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("close", closeDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, realPollerFieldName, pollerImplDesc);
                    cb.invokevirtual(pollerImplDesc, "close", closeDesc);
                    cb.return_();
                });
            });
        });
    }
}
