package io.github.dreamlike.scheduler.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;

final class AgentBytecodeToolkit {

    private AgentBytecodeToolkit() {
    }

    static byte[] proxyJdkPoller() {
        String jdkProxyFieldName = "_jdkPoller";
        ClassDesc pollerDesc = ClassDesc.of("sun.nio.ch.Poller");
        ClassFile classFile = ClassFile.of();
        ClassDesc thisClassDesc = ClassDesc.of("io.github.dreamlike.scheduler", "JdkVirtualThreadPoller");
        ClassDesc booleanSupplierDesc = ClassDesc.of("java.util.function.BooleanSupplier");
        ClassDesc byteArrayDesc = ClassDesc.ofDescriptor("[B");

        return classFile.build(thisClassDesc, classBuilder -> {
            classBuilder.withFlags(AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask());
            classBuilder.withInterfaceSymbols(ClassDesc.of("io.github.dreamlike.VirtualThreadPoller"));
            classBuilder.withField(jdkProxyFieldName, pollerDesc, AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask());
            classBuilder.withMethodBody(
                    ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, pollerDesc),
                    AccessFlag.PUBLIC.mask(),
                    codeBuilder -> {
                        codeBuilder.aload(0);
                        codeBuilder.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                        codeBuilder.aload(0);
                        codeBuilder.aload(1);
                        codeBuilder.putfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                        codeBuilder.return_();
                    }
            );

            int methodFlags = AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask();

            MethodTypeDesc implReadDesc = MethodTypeDesc.of(
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_int,
                    byteArrayDesc,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_long,
                    booleanSupplierDesc
            );
            classBuilder.withMethodBody("implRead", implReadDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.aload(2);
                codeBuilder.iload(3);
                codeBuilder.iload(4);
                codeBuilder.lload(5);
                codeBuilder.aload(7);
                codeBuilder.invokevirtual(pollerDesc, "implRead", implReadDesc);
                codeBuilder.ireturn();
            });

            MethodTypeDesc implWriteDesc = MethodTypeDesc.of(
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_int,
                    byteArrayDesc,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_int,
                    booleanSupplierDesc
            );
            classBuilder.withMethodBody("implWrite", implWriteDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.aload(2);
                codeBuilder.iload(3);
                codeBuilder.iload(4);
                codeBuilder.aload(5);
                codeBuilder.invokevirtual(pollerDesc, "implWrite", implWriteDesc);
                codeBuilder.ireturn();
            });

            MethodTypeDesc implStartPollDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
            classBuilder.withMethodBody("implStartPoll", implStartPollDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.invokevirtual(pollerDesc, "implStartPoll", implStartPollDesc);
                codeBuilder.return_();
            });

            MethodTypeDesc implStopPollDesc = MethodTypeDesc.of(
                    ConstantDescs.CD_void,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_boolean
            );
            classBuilder.withMethodBody("implStopPoll", implStopPollDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.iload(2);
                codeBuilder.invokevirtual(pollerDesc, "implStopPoll", implStopPollDesc);
                codeBuilder.return_();
            });

            MethodTypeDesc pollTimeoutDesc = MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
            classBuilder.withMethodBody("poll", pollTimeoutDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.invokevirtual(pollerDesc, "poll", pollTimeoutDesc);
                codeBuilder.ireturn();
            });

            MethodTypeDesc closeDesc = MethodTypeDesc.of(ConstantDescs.CD_void);
            classBuilder.withMethodBody("close", closeDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.invokevirtual(pollerDesc, "close", closeDesc);
                codeBuilder.return_();
            });

            MethodTypeDesc pollDesc = MethodTypeDesc.of(
                    ConstantDescs.CD_void,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_long,
                    booleanSupplierDesc
            );
            classBuilder.withMethodBody("poll", pollDesc, methodFlags, codeBuilder -> {
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.iload(2);
                codeBuilder.lload(3);
                codeBuilder.aload(5);
                codeBuilder.invokevirtual(pollerDesc, "poll", pollDesc);
                codeBuilder.return_();
            });
        });
    }

    static byte[] jdkPollerProxy(String proxyPollerClassName, String pollerImplClass) {
        ClassFile classFile = ClassFile.of();
        String mhImplReadFieldName = "_mhImplRead";
        String mhImplWriteFieldName = "_mhImplWrite";
        String mhImplStartPollFieldName = "_mhImplStartPoll";
        String mhImplStopPollFieldName = "_mhImplStopPoll";
        String mhPollFieldName = "_mhPoll";
        String mhCloseFieldName = "_mhClose";

        ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");
        ClassDesc proxyDesc = ClassDesc.of(proxyPollerClassName);
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

            classBuilder.withField(mhImplReadFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplWriteFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplStartPollFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplStopPollFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhPollFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhCloseFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));

            classBuilder.withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.aload(0);
                    cb.invokespecial(ClassDesc.of("sun.nio.ch", "Poller"), ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    cb.return_();
                });
            });

            classBuilder.withMethod(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(), methodBuilder ->
                    methodBuilder.withCode(codeBuilder -> emitJdkPollerProxyClinit(codeBuilder, proxyDesc, objectDesc,
                            methodHandleDesc, intDesc, longDesc, booleanDesc, voidDesc, byteArrayDesc,
                            booleanSupplierDesc, mhImplReadFieldName, mhImplWriteFieldName, mhImplStartPollFieldName,
                            mhImplStopPollFieldName, mhPollFieldName, mhCloseFieldName, pollerImplClass)));

            classBuilder.withMethod("implRead", implReadDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
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

            classBuilder.withMethod("implStartPoll", implStartDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhImplStartPollFieldName, methodHandleDesc);
                        cb.iload(1);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", implStartDesc);
                        cb.return_();
                    }));

            classBuilder.withMethod("implStopPoll", implStopDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhImplStopPollFieldName, methodHandleDesc);
                        cb.iload(1);
                        cb.iload(2);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", implStopDesc);
                        cb.return_();
                    }));

            classBuilder.withMethod("poll", pollDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhPollFieldName, methodHandleDesc);
                        cb.iload(1);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", pollDesc);
                        cb.ireturn();
                    }));

            classBuilder.withMethod("close", closeDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhCloseFieldName, methodHandleDesc);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", closeDesc);
                        cb.return_();
                    }));
        });
    }

    private static void emitJdkPollerProxyClinit(
            CodeBuilder codeBuilder,
            ClassDesc proxyDesc,
            ClassDesc objectDesc,
            ClassDesc methodHandleDesc,
            ClassDesc intDesc,
            ClassDesc longDesc,
            ClassDesc booleanDesc,
            ClassDesc voidDesc,
            ClassDesc byteArrayDesc,
            ClassDesc booleanSupplierDesc,
            String mhImplReadFieldName,
            String mhImplWriteFieldName,
            String mhImplStartPollFieldName,
            String mhImplStopPollFieldName,
            String mhPollFieldName,
            String mhCloseFieldName,
            String pollerImplClass
    ) {
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

        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhImplReadFieldName, "implRead",
                intDesc, intDesc, byteArrayDesc, intDesc, intDesc, longDesc, booleanSupplierDesc);
        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhImplWriteFieldName, "implWrite",
                intDesc, intDesc, byteArrayDesc, intDesc, intDesc, booleanSupplierDesc);
        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhImplStartPollFieldName, "implStartPoll",
                voidDesc, intDesc);
        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhImplStopPollFieldName, "implStopPoll",
                voidDesc, intDesc, booleanDesc);
        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhPollFieldName, "poll", intDesc, intDesc);
        bindField(codeBuilder, proxyDesc, methodHandleDesc, lookupDesc, mhCloseFieldName, "close", voidDesc);

        codeBuilder.labelBinding(tryEnd);
        codeBuilder.branch(Opcode.GOTO, returnLabel);

        codeBuilder.labelBinding(catchLabel);
        codeBuilder.astore(4);
        codeBuilder.new_(runtimeExceptionDesc);
        codeBuilder.dup();
        codeBuilder.ldc(errorMessage);
        codeBuilder.aload(4);
        codeBuilder.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME,
                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
        codeBuilder.athrow();

        codeBuilder.labelBinding(returnLabel);
        codeBuilder.return_();
        codeBuilder.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
    }

    private static void bindField(
            CodeBuilder codeBuilder,
            ClassDesc proxyDesc,
            ClassDesc methodHandleDesc,
            ClassDesc lookupDesc,
            String targetField,
            String targetMethod,
            ClassDesc returnType,
            ClassDesc... params
    ) {
        // stack: Lookup (local 3), Class (local 1), instance (local 2)
        // _mhX = lookup.findVirtual(clazz, "X", MethodType.methodType(...)).bindTo(instance)
        codeBuilder.aload(3);
        codeBuilder.aload(1);
        codeBuilder.ldc(targetMethod);
        emitMethodType(codeBuilder, returnType, params);
        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
        codeBuilder.aload(2);
        codeBuilder.invokevirtual(methodHandleDesc, "bindTo",
                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"));
        codeBuilder.putstatic(proxyDesc, targetField, methodHandleDesc);
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
}
