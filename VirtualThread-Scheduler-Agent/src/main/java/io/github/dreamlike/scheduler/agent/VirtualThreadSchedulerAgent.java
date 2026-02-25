package io.github.dreamlike.scheduler.agent;

import java.lang.classfile.*;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal Java agent that prints the names of bootstrap classes it sees without modifying them.
 */
public final class VirtualThreadSchedulerAgent {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private VirtualThreadSchedulerAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    private static void install(Instrumentation instrumentation) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        System.setProperty("jdk.pollerMode", "1");
        ClassFileTransformer transformer = new BootstrapClassLoggingTransformer();
        System.out.println("[VirtualThreadSchedulerAgent] installing agent; retransform support = "
                + instrumentation.isRetransformClassesSupported());
        try {
            instrumentation.addTransformer(transformer, true);
        } catch (Throwable t) {
            System.err.println("[VirtualThreadSchedulerAgent] failed to register transformer");
            t.printStackTrace(System.err);
            throw (t instanceof RuntimeException runtimeException)
                    ? runtimeException
                    : new RuntimeException("Failed to install VirtualThreadSchedulerAgent", t);
        }
    }

    private static final class BootstrapClassLoggingTransformer implements ClassFileTransformer {
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
                    mb.withCode(cb -> {
                        ClassDesc pollerDesc = ClassDesc.ofDescriptor("Lsun/nio/ch/Poller;");
                        ClassDesc serviceLoaderDesc = ClassDesc.ofDescriptor("Ljava/util/ServiceLoader;");
                        ClassDesc streamDesc = ClassDesc.ofDescriptor("Ljava/util/stream/Stream;");
                        ClassDesc listDesc = ClassDesc.ofDescriptor("Ljava/util/List;");
                        ClassDesc providerDesc = ClassDesc.ofDescriptor("Ljava/util/ServiceLoader$Provider;");
                        ClassDesc illegalArgumentExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/IllegalArgumentException;");

                        Label atLeastOneImplementation = cb.newLabel();
                        Label singleImplementation = cb.newLabel();

                        cb.ldc(pollerDesc);
                        cb.invokestatic(serviceLoaderDesc, "load", MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;)Ljava/util/ServiceLoader;"));
                        cb.astore(0);
                        cb.aload(0);
                        cb.invokevirtual(serviceLoaderDesc, "stream", MethodTypeDesc.ofDescriptor("()Ljava/util/stream/Stream;"));
                        cb.invokeinterface(streamDesc, "toList", MethodTypeDesc.ofDescriptor("()Ljava/util/List;"));
                        cb.astore(1);
                        cb.aload(1);
                        cb.invokeinterface(listDesc, "isEmpty", MethodTypeDesc.ofDescriptor("()Z"));
                        cb.ifeq(atLeastOneImplementation);
                        cb.new_(illegalArgumentExceptionDesc);
                        cb.dup();
                        cb.ldc("Provide at least one Poller implementation.");
                        cb.invokespecial(illegalArgumentExceptionDesc, "<init>", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
                        cb.athrow();

                        cb.labelBinding(atLeastOneImplementation);
                        cb.aload(1);
                        cb.invokeinterface(listDesc, "size", MethodTypeDesc.ofDescriptor("()I"));
                        cb.iconst_1();
                        cb.if_icmple(singleImplementation);
                        cb.new_(illegalArgumentExceptionDesc);
                        cb.dup();
                        cb.ldc("Multiple Poller implementations found.");
                        cb.invokespecial(illegalArgumentExceptionDesc, "<init>", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
                        cb.athrow();

                        cb.labelBinding(singleImplementation);
                        cb.aload(1);
                        cb.iconst_0();
                        cb.invokeinterface(listDesc, "get", MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Object;"));
                        cb.checkcast(providerDesc);
                        cb.astore(2);
                        cb.aload(2);
                        cb.invokeinterface(providerDesc, "get", MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;"));
                        cb.checkcast(pollerDesc);
                        cb.areturn();
                    });
                });
    }
    // 上面的classfile api搞出来的 就是下面的字节码
    //public static spiPoller()Lsun/nio/ch/Poller;
    //   L0
    //    LINENUMBER 17 L0
    //    LDC Lsun/nio/ch/Poller;.class
    //    INVOKESTATIC java/util/ServiceLoader.load (Ljava/lang/Class;)Ljava/util/ServiceLoader;
    //    ASTORE 0
    //   L1
    //    LINENUMBER 18 L1
    //    ALOAD 0
    //    INVOKEVIRTUAL java/util/ServiceLoader.stream ()Ljava/util/stream/Stream;
    //    INVOKEINTERFACE java/util/stream/Stream.toList ()Ljava/util/List; (itf)
    //    ASTORE 1
    //   L2
    //    LINENUMBER 19 L2
    //    ALOAD 1
    //    INVOKEINTERFACE java/util/List.isEmpty ()Z (itf)
    //    IFEQ L3
    //   L4
    //    LINENUMBER 20 L4
    //    NEW java/lang/IllegalArgumentException
    //    DUP
    //    LDC "Provide at least one Poller implementation."
    //    INVOKESPECIAL java/lang/IllegalArgumentException.<init> (Ljava/lang/String;)V
    //    ATHROW
    //   L3
    //    LINENUMBER 22 L3
    //    ALOAD 1
    //    INVOKEINTERFACE java/util/List.size ()I (itf)
    //    ICONST_1
    //    IF_ICMPLE L5
    //   L6
    //    LINENUMBER 23 L6
    //    NEW java/lang/IllegalArgumentException
    //    DUP
    //    LDC "Multiple Poller implementations found."
    //    INVOKESPECIAL java/lang/IllegalArgumentException.<init> (Ljava/lang/String;)V
    //    ATHROW
    //   L5
    //    LINENUMBER 25 L5
    //    ALOAD 1
    //    ICONST_0
    //    INVOKEINTERFACE java/util/List.get (I)Ljava/lang/Object; (itf)
    //    CHECKCAST java/util/ServiceLoader$Provider
    //    ASTORE 2
    //   L7
    //    LINENUMBER 26 L7
    //    ALOAD 2
    //    INVOKEINTERFACE java/util/ServiceLoader$Provider.get ()Ljava/lang/Object; (itf)
    //    CHECKCAST sun/nio/ch/Poller
    //    ARETURN
    //   L8
    //    LOCALVARIABLE pollerServiceLoader Ljava/util/ServiceLoader; L1 L8 0
    //    // signature Ljava/util/ServiceLoader<Lsun/nio/ch/Poller;>;
    //    // declaration: pollerServiceLoader extends java.util.ServiceLoader<sun.nio.ch.Poller>
    //    LOCALVARIABLE pollImplements Ljava/util/List; L2 L8 1
    //    // signature Ljava/util/List<Ljava/util/ServiceLoader$Provider<Lsun/nio/ch/Poller;>;>;
    //    // declaration: pollImplements extends java.util.List<java.util.ServiceLoader$Provider<sun.nio.ch.Poller>>
    //    LOCALVARIABLE pollerProvider Ljava/util/ServiceLoader$Provider; L7 L8 2
    //    // signature Ljava/util/ServiceLoader$Provider<Lsun/nio/ch/Poller;>;
    //    // declaration: pollerProvider extends java.util.ServiceLoader$Provider<sun.nio.ch.Poller>
    //    MAXSTACK = 3
    //    MAXLOCALS = 3

}
