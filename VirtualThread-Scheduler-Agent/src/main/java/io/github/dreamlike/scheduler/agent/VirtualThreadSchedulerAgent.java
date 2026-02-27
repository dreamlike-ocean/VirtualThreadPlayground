package io.github.dreamlike.scheduler.agent;

import java.lang.classfile.*;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

            // 把 java.base 的 sun.nio.ch 包 打开 给 agent 模块（不是 export）。否则 privateLookupIn 会被模块访问检查挡住
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
            // 注入一个特洛伊木马 只是为了把我的Poller带进去
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

    // 入侵修改sun.nio.ch.DefaultPollerProvider
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

            // read poller和write poller都强制设置为1
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultReadPollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("defaultWritePollers"), classBuilder.constantPool().utf8Entry("()I"), 0, classBuilder);
            // supportReadOps和supportWriteOps都强制返回true 允许使用“async”方案
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportReadOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
            returnOneOrTrue(classBuilder.constantPool().utf8Entry("supportWriteOps"), classBuilder.constantPool().utf8Entry("()Z"), 0, classBuilder);
            spiPoller(classBuilder);
            // 统一返回jdkPollerProxy的木马代码
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
                        // 直接new一个sun.nio.ch.JdkPollerProxy#JdkPollerProxy
                        ClassDesc proxyDesc = ClassDesc.of(PROXY_POLLER_CLASS_NAME);
                        cb.new_(proxyDesc);
                        cb.dup();
                        cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                        cb.areturn();
                    });
                });
    }

    private static void emitIntConst(CodeBuilder cb, int value) {
        switch (value) {
            case -1 -> cb.iconst_m1();
            case 0 -> cb.iconst_0();
            case 1 -> cb.iconst_1();
            case 2 -> cb.iconst_2();
            case 3 -> cb.iconst_3();
            case 4 -> cb.iconst_4();
            case 5 -> cb.iconst_5();
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    cb.bipush(value);
                } else {
                    cb.sipush(value);
                }
            }
        }
    }

    private static void emitMethodType(CodeBuilder cb, ClassDesc returnType, ClassDesc... paramTypes) {
        ClassDesc classDesc = ClassDesc.ofDescriptor("Ljava/lang/Class;");
        ClassDesc methodTypeDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodType;");
        cb.ldc(returnType);
        emitIntConst(cb, paramTypes.length);
        cb.anewarray(classDesc);
        for (int i = 0; i < paramTypes.length; i++) {
            cb.dup();
            emitIntConst(cb, i);
            cb.ldc(paramTypes[i]);
            cb.aastore();
        }
        cb.invokestatic(methodTypeDesc, "methodType",
                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"));
    }

    private static byte[] jdkPollerProxy() {
        ClassFile classFile = ClassFile.of();
        String mhImplReadFieldName = "_mhImplRead";
        String mhImplWriteFieldName = "_mhImplWrite";
        String mhImplStartPollFieldName = "_mhImplStartPoll";
        String mhImplStopPollFieldName = "_mhImplStopPoll";
        String mhPollFieldName = "_mhPoll";
        String mhCloseFieldName = "_mhClose";
        ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");
        ClassDesc proxyDesc = ClassDesc.of(PROXY_POLLER_CLASS_NAME);
        ClassDesc objectDesc = ClassDesc.ofDescriptor("Ljava/lang/Object;");
        ClassDesc methodHandleDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandle;");
        ClassDesc intDesc = ClassDesc.ofDescriptor("I");
        ClassDesc longDesc = ClassDesc.ofDescriptor("J");
        ClassDesc booleanDesc = ClassDesc.ofDescriptor("Z");
        ClassDesc voidDesc = ClassDesc.ofDescriptor("V");
        ClassDesc byteArrayDesc = ClassDesc.ofDescriptor("[B");
        ClassDesc booleanSupplierDesc = ClassDesc.ofDescriptor("Ljava/util/function/BooleanSupplier;");
        MethodTypeDesc implReadDesc = MethodTypeDesc.ofDescriptor("(I[BIIJLjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc implWriteDesc = MethodTypeDesc.ofDescriptor("(I[BIILjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc implStartDesc = MethodTypeDesc.ofDescriptor("(I)V");
        MethodTypeDesc implStopDesc = MethodTypeDesc.ofDescriptor("(IZ)V");
        MethodTypeDesc pollDesc = MethodTypeDesc.ofDescriptor("(I)I");
        MethodTypeDesc closeDesc = MethodTypeDesc.ofDescriptor("()V");

        return classFile.build(proxyDesc, classBuilder -> {
            classBuilder.withSuperclass(ClassDesc.of("sun.nio.ch", "Poller"));
            classBuilder.withField(mhImplReadFieldName, methodHandleDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withField(mhImplWriteFieldName, methodHandleDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withField(mhImplStartPollFieldName, methodHandleDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withField(mhImplStopPollFieldName, methodHandleDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withField(mhPollFieldName, methodHandleDesc, fieldBuilder -> {
               fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL);
            });
            classBuilder.withField(mhCloseFieldName, methodHandleDesc, fieldBuilder -> {
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
                    // 等价于
                    //    static {
                    //        try {
                    //            ClassLoader var0 = Thread.currentThread().getContextClassLoader();
                    //            Class var1 = Class.forName("io.github.dreamlike.scheduler.example.CustomerPoller", true, var0);
                    //            Object var2 = var1.getConstructor().newInstance();
                    //            MethodHandles.Lookup var3 = MethodHandles.lookup();
                    //            _mhImplRead = var3.findVirtual(var1, "implRead", MethodType.methodType("I", new Class[]{"I", byte[].class, "I", "I", "J", BooleanSupplier.class})).bindTo(var2);
                    //            _mhImplWrite = var3.findVirtual(var1, "implWrite", MethodType.methodType("I", new Class[]{"I", byte[].class, "I", "I", BooleanSupplier.class})).bindTo(var2);
                    //            _mhImplStartPoll = var3.findVirtual(var1, "implStartPoll", MethodType.methodType("V", new Class[]{"I"})).bindTo(var2);
                    //            _mhImplStopPoll = var3.findVirtual(var1, "implStopPoll", MethodType.methodType("V", new Class[]{"I", "Z"})).bindTo(var2);
                    //            _mhPoll = var3.findVirtual(var1, "poll", MethodType.methodType("I", new Class[]{"I"})).bindTo(var2);
                    //            _mhClose = var3.findVirtual(var1, "close", MethodType.methodType("V", new Class[0])).bindTo(var2);
                    //        } catch (Exception var4) {
                    //            throw new RuntimeException("init io.github.dreamlike.scheduler.example.CustomerPoller fail!", var4);
                    //        }
                    //    }
                    Label tryStart = codeBuilder.newLabel();
                    Label tryEnd = codeBuilder.newLabel();
                    Label catchLabel = codeBuilder.newLabel();
                    Label returnLabel = codeBuilder.newLabel();

                    ClassDesc threadDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread;");
                    ClassDesc classDesc = ClassDesc.ofDescriptor("Ljava/lang/Class;");
                    ClassDesc constructorDesc = ClassDesc.ofDescriptor("Ljava/lang/reflect/Constructor;");
                    ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
                    ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");
                    ClassDesc methodHandlesDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles;");
                    ClassDesc lookupDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;");
                    ClassDesc moduleDesc = ClassDesc.ofDescriptor("Ljava/lang/Module;");

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
                    codeBuilder.astore(2);

                    codeBuilder.invokestatic(methodHandlesDesc, "lookup", MethodTypeDesc.ofDescriptor("()Ljava/lang/invoke/MethodHandles$Lookup;"));
                    codeBuilder.astore(3);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("implRead");
                    emitMethodType(codeBuilder, intDesc, intDesc, byteArrayDesc, intDesc, intDesc, longDesc, booleanSupplierDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhImplReadFieldName, methodHandleDesc);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("implWrite");
                    emitMethodType(codeBuilder, intDesc, intDesc, byteArrayDesc, intDesc, intDesc, booleanSupplierDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhImplWriteFieldName, methodHandleDesc);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("implStartPoll");
                    emitMethodType(codeBuilder, voidDesc, intDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhImplStartPollFieldName, methodHandleDesc);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("implStopPoll");
                    emitMethodType(codeBuilder, voidDesc, intDesc, booleanDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhImplStopPollFieldName, methodHandleDesc);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("poll");
                    emitMethodType(codeBuilder, intDesc, intDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhPollFieldName, methodHandleDesc);

                    codeBuilder.aload(3);
                    codeBuilder.aload(1);
                    codeBuilder.ldc("close");
                    emitMethodType(codeBuilder, voidDesc);
                    codeBuilder.invokevirtual(lookupDesc, "findVirtual", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.aload(2);
                    codeBuilder.invokevirtual(methodHandleDesc, "bindTo", MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
                    codeBuilder.putstatic(proxyDesc, mhCloseFieldName, methodHandleDesc);

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
                    // 等价于
                    //   int implRead(int var1, byte[] var2, int var3, int var4, long var5, BooleanSupplier var7) throws IOException {
                    //        return _mhImplRead.invokeExact(var1, var2, var3, var4, var5, var7);
                    //    }
                    cb.getstatic(proxyDesc, mhImplReadFieldName, methodHandleDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.lload(5);
                    cb.aload(7);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", implReadDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implWrite", implWriteDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhImplWriteFieldName, methodHandleDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.aload(5);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", implWriteDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implStartPoll", implStartDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhImplStartPollFieldName, methodHandleDesc);
                    cb.iload(1);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", implStartDesc);
                    cb.return_();
                });
            });

            classBuilder.withMethod("implStopPoll", implStopDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhImplStopPollFieldName, methodHandleDesc);
                    cb.iload(1);
                    cb.iload(2);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", implStopDesc);
                    cb.return_();
                });
            });

            classBuilder.withMethod("poll", pollDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhPollFieldName, methodHandleDesc);
                    cb.iload(1);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", pollDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("close", closeDesc, 0, methodBuilder -> {
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhCloseFieldName, methodHandleDesc);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", closeDesc);
                    cb.return_();
                });
            });
        });
    }
}
