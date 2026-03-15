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

    // 必须和 VirtualThreadPoller 在同一个包下，才能用 Lookup#defineClass 定义。
    // Must be in the same package as VirtualThreadPoller for Lookup#defineClass.
    static final String JDK_POLLER_ADAPTOR_CLASS_NAME = "io.github.dreamlike.scheduler.agent.JdkVirtualThreadPollerAdaptor";
    static final String CORE_POLLER_INTERFACE_NAME = "io.github.dreamlike.VirtualThreadPoller";
    private AgentBytecodeToolkit() {
    }

    static byte[] jdkPollerToVirtualThreadPollerAdaptor() {
        String jdkProxyFieldName = "_jdkPoller";
        ClassDesc pollerDesc = ClassDesc.of("sun.nio.ch.Poller");
        ClassFile classFile = ClassFile.of();
        ClassDesc thisClassDesc = ClassDesc.of(JDK_POLLER_ADAPTOR_CLASS_NAME);
        ClassDesc booleanSupplierDesc = ClassDesc.of("java.util.function.BooleanSupplier");
        ClassDesc byteArrayDesc = ClassDesc.ofDescriptor("[B");
        ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
        ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");
        ClassDesc methodHandleDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandle;");
        ClassDesc methodHandlesDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles;");
        ClassDesc lookupDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;");

        String mhImplReadFieldName = "_mhImplRead";
        String mhImplWriteFieldName = "_mhImplWrite";
        String mhImplStartPollFieldName = "_mhImplStartPoll";
        String mhImplStopPollFieldName = "_mhImplStopPoll";
        String mhPollTimeoutFieldName = "_mhPoll";
        String mhCloseFieldName = "_mhClose";
        // 当前 JDK Poller 可能不暴露 poll(fd,event,nanos,isOpen) 这个签名。
        // Current JDK Poller may not expose poll(fd,event,nanos,isOpen).

        return classFile.build(thisClassDesc, classBuilder -> {
            classBuilder.withFlags(AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask());
            classBuilder.withInterfaceSymbols(ClassDesc.of(CORE_POLLER_INTERFACE_NAME));
            classBuilder.withField(jdkProxyFieldName, pollerDesc, AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask());

            classBuilder.withField(mhImplReadFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplWriteFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplStartPollFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhImplStopPollFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhPollTimeoutFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            classBuilder.withField(mhCloseFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));

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

            classBuilder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(), cb -> {
                // 静态初始化：只 resolve 一次 MethodHandle，后续 invokeExact。
                // Static init: resolve MethodHandles once, then use invokeExact.
                Label tryStart = cb.newLabel();
                Label tryEnd = cb.newLabel();
                Label catchLabel = cb.newLabel();
                Label returnLabel = cb.newLabel();

                cb.labelBinding(tryStart);
                cb.invokestatic(methodHandlesDesc, "lookup", MethodTypeDesc.ofDescriptor("()Ljava/lang/invoke/MethodHandles$Lookup;"));
                cb.astore(0);
                cb.ldc(pollerDesc);
                cb.aload(0);
                cb.invokestatic(methodHandlesDesc, "privateLookupIn",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;"));
                cb.astore(1);

                // _mhImplRead：implRead 的 MethodHandle。
                // _mhImplRead: MethodHandle for implRead.
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("implRead");
                emitMethodType(cb, ConstantDescs.CD_int, ConstantDescs.CD_int, byteArrayDesc, ConstantDescs.CD_int,
                        ConstantDescs.CD_int, ConstantDescs.CD_long, booleanSupplierDesc);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhImplReadFieldName, methodHandleDesc);

                // _mhImplWrite：implWrite 的 MethodHandle。
                // _mhImplWrite: MethodHandle for implWrite.
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("implWrite");
                emitMethodType(cb, ConstantDescs.CD_int, ConstantDescs.CD_int, byteArrayDesc, ConstantDescs.CD_int,
                        ConstantDescs.CD_int, booleanSupplierDesc);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhImplWriteFieldName, methodHandleDesc);

                // _mhImplStartPoll：implStartPoll 的 MethodHandle。
                // _mhImplStartPoll: MethodHandle for implStartPoll.
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("implStartPoll");
                emitMethodType(cb, ConstantDescs.CD_void, ConstantDescs.CD_int);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhImplStartPollFieldName, methodHandleDesc);

                // _mhImplStopPoll：implStopPoll 的 MethodHandle。
                // _mhImplStopPoll: MethodHandle for implStopPoll.
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("implStopPoll");
                emitMethodType(cb, ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_boolean);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhImplStopPollFieldName, methodHandleDesc);

                // _mhPoll(timeout)：poll(int timeout) 的 MethodHandle。
                // _mhPoll(timeout): MethodHandle for poll(int timeout).
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("poll");
                emitMethodType(cb, ConstantDescs.CD_int, ConstantDescs.CD_int);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhPollTimeoutFieldName, methodHandleDesc);

                // _mhClose：close 的 MethodHandle。
                // _mhClose: MethodHandle for close.
                cb.aload(1);
                cb.ldc(pollerDesc);
                cb.ldc("close");
                emitMethodType(cb, ConstantDescs.CD_void);
                cb.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                cb.putstatic(thisClassDesc, mhCloseFieldName, methodHandleDesc);

                cb.labelBinding(tryEnd);
                cb.branch(Opcode.GOTO, returnLabel);

                cb.labelBinding(catchLabel);
                cb.astore(2);
                cb.new_(runtimeExceptionDesc);
                cb.dup();
                cb.ldc("init JdkVirtualThreadPollerAdaptor fail!");
                cb.aload(2);
                cb.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
                cb.athrow();

                cb.labelBinding(returnLabel);
                cb.return_();
                cb.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
            });

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
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;I[BIIJLjava/util/function/BooleanSupplier;)I");
                codeBuilder.getstatic(thisClassDesc, mhImplReadFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.aload(2);
                codeBuilder.iload(3);
                codeBuilder.iload(4);
                codeBuilder.lload(5);
                codeBuilder.aload(7);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
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
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;I[BIILjava/util/function/BooleanSupplier;)I");
                codeBuilder.getstatic(thisClassDesc, mhImplWriteFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.aload(2);
                codeBuilder.iload(3);
                codeBuilder.iload(4);
                codeBuilder.aload(5);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
                codeBuilder.ireturn();
            });

            MethodTypeDesc implStartPollDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
            classBuilder.withMethodBody("implStartPoll", implStartPollDesc, methodFlags, codeBuilder -> {
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;I)V");
                codeBuilder.getstatic(thisClassDesc, mhImplStartPollFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
                codeBuilder.return_();
            });

            MethodTypeDesc implStopPollDesc = MethodTypeDesc.of(
                    ConstantDescs.CD_void,
                    ConstantDescs.CD_int,
                    ConstantDescs.CD_boolean
            );
            classBuilder.withMethodBody("implStopPoll", implStopPollDesc, methodFlags, codeBuilder -> {
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;IZ)V");
                codeBuilder.getstatic(thisClassDesc, mhImplStopPollFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.iload(2);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
                codeBuilder.return_();
            });

            MethodTypeDesc pollTimeoutDesc = MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);
            classBuilder.withMethodBody("poll", pollTimeoutDesc, methodFlags, codeBuilder -> {
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;I)I");
                codeBuilder.getstatic(thisClassDesc, mhPollTimeoutFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.iload(1);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
                codeBuilder.ireturn();
            });

            MethodTypeDesc closeDesc = MethodTypeDesc.of(ConstantDescs.CD_void);
            classBuilder.withMethodBody("close", closeDesc, methodFlags, codeBuilder -> {
                MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller;)V");
                codeBuilder.getstatic(thisClassDesc, mhCloseFieldName, methodHandleDesc);
                codeBuilder.aload(0);
                codeBuilder.getfield(thisClassDesc, jdkProxyFieldName, pollerDesc);
                codeBuilder.invokevirtual(methodHandleDesc, "invokeExact", invokeDesc);
                codeBuilder.return_();
            });

        });
    }

    // jdkPollerProxy 差不多是下面这个结构。
    // jdkPollerProxy is roughly like the following.
    // package sun.nio.ch.JdkPollerProxy;
    // class JdkPollerProxy extends sun.nio.ch.Poller {
    //     static final Methodhandle CustomerPollerCtor;
    //      static final MethodHandle implStartPollMH;
    //       .... 等等io.github.dreamlike.VirtualThreadPoller中的其他函数mh 这些在customerPollerClass中都存在
    //
    //   static {
    //       ClassLoader var1 = Thread.currentThread().getContextClassLoader();
    //       if (var1 == null) {
    //             var1 = ClassLoader.getSystemClassLoader();
    //       }
    //
    //      Class customerPollerClass = Class.forName("io.github.dreamlike.scheduler.example.CustomerPoller", true, var1);
    //       Class pollerClass = Class.forName("io.github.dreamlike.VirtualThreadPoller", true, var1);
    //      MethodHandles.Lookup var3 = MethodHandles.publicLookup();
    //        customerPollerCtorMH = var3.findConstructor(var2, MethodType.methodType(void.class, pollerClass));
    //        implStartPollMH = var3.findVirtual(customerPollerClass, ..省略MethodType)
    //        ....省略其他mh获取
    //   }

    //  final Object customerPoller;
    //    public JdkPollerProxy(Object jdkPoller) {
    //      this.customerPoller = CustomerPollerCtor.invoke(jdkPoller);
    //    }
    //    void implStartPoll(int fdVal) throws IOException {
    //      implStartPollMH.invokeExact((io.github.dreamlike.scheduler.example.CustomerPoller)customerPoller, fdVal);
    //    }
    //  ....省略其他poller函数 宗旨就是sun.nio.ch.Poller和io.github.dreamlike.VirtualThreadPoller方法一一对应去代理
    // }
    static byte[] jdkPollerProxy(String proxyPollerClassName, String pollerImplClass) {

        ClassFile classFile = ClassFile.of();

        String pollerInstanceFieldName = "_poller";
        String mhCtorFieldName = "_mhCtor";
        String mhAdaptorCtorFieldName = "_mhAdaptorCtor";
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
        ClassDesc methodTypeDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodType;");
        ClassDesc methodHandlesDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles;");
        ClassDesc lookupDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;");
        ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
        ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");
        ClassDesc threadDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread;");
        ClassDesc classDesc = ClassDesc.ofDescriptor("Ljava/lang/Class;");
        ClassDesc classLoaderDesc = ClassDesc.ofDescriptor("Ljava/lang/ClassLoader;");

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

        MethodTypeDesc mhImplReadInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;I[BIIJLjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc mhImplWriteInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;I[BIILjava/util/function/BooleanSupplier;)I");
        MethodTypeDesc mhImplStartInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;I)V");
        MethodTypeDesc mhImplStopInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IZ)V");
        MethodTypeDesc mhPollInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;I)I");
        MethodTypeDesc mhCloseInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V");
        MethodTypeDesc mhAdaptorCtorInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/Object;");
        MethodTypeDesc mhCustomerCtorInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IZ)Ljava/lang/Object;");

        return classFile.build(proxyDesc, classBuilder -> {
            classBuilder.withSuperclass(ClassDesc.of("sun.nio.ch", "Poller"));

            classBuilder.withField(pollerInstanceFieldName, objectDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));

            classBuilder.withField(mhCtorFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));

            classBuilder.withField(mhAdaptorCtorFieldName, methodHandleDesc, fieldBuilder ->
                    fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));

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

            classBuilder.withMethod(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, objectDesc, ConstantDescs.CD_int, ConstantDescs.CD_boolean),
                    AccessFlag.PUBLIC.mask(),
                    methodBuilder -> {
                        methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                        methodBuilder.withCode(cb -> {
                            cb.aload(0);
                            cb.invokespecial(ClassDesc.of("sun.nio.ch", "Poller"), ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                            cb.aload(0);
                            cb.getstatic(proxyDesc, mhCtorFieldName, methodHandleDesc);
                            cb.getstatic(proxyDesc, mhAdaptorCtorFieldName, methodHandleDesc);
                            cb.aload(1);
                            cb.invokevirtual(methodHandleDesc, "invokeExact", mhAdaptorCtorInvokeDesc);
                            cb.iload(2);
                            cb.iload(3);
                            cb.invokevirtual(methodHandleDesc, "invokeExact", mhCustomerCtorInvokeDesc);
                            cb.putfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                            cb.return_();
                        });
                    });

            classBuilder.withMethod(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(), methodBuilder ->
                    methodBuilder.withCode(codeBuilder -> {
                        // static { findVirtual + asType, 不做 bindTo }
                        Label tryStart = codeBuilder.newLabel();
                        Label tryEnd = codeBuilder.newLabel();
                        Label catchLabel = codeBuilder.newLabel();
                        Label returnLabel = codeBuilder.newLabel();

                        codeBuilder.labelBinding(tryStart);
                        codeBuilder.invokestatic(threadDesc, "currentThread", MethodTypeDesc.ofDescriptor("()Ljava/lang/Thread;"));
                        codeBuilder.invokevirtual(threadDesc, "getContextClassLoader", MethodTypeDesc.ofDescriptor("()Ljava/lang/ClassLoader;"));
                        codeBuilder.astore(0);

                        // if (cl == null) cl = ClassLoader.getSystemClassLoader();
                        Label hasCl = codeBuilder.newLabel();
                        codeBuilder.aload(0);
                        codeBuilder.ifnonnull(hasCl);
                        codeBuilder.invokestatic(classLoaderDesc, "getSystemClassLoader", MethodTypeDesc.ofDescriptor("()Ljava/lang/ClassLoader;"));
                        codeBuilder.astore(0);
                        codeBuilder.labelBinding(hasCl);

                        codeBuilder.ldc(pollerImplClass);
                        codeBuilder.iconst_1();
                        codeBuilder.aload(0);
                        codeBuilder.invokestatic(classDesc, "forName", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                        codeBuilder.astore(1);

                        codeBuilder.invokestatic(methodHandlesDesc, "publicLookup", MethodTypeDesc.ofDescriptor("()Ljava/lang/invoke/MethodHandles$Lookup;"));
                        codeBuilder.astore(2);

                        // _mhAdaptorCtor: new io.github.dreamlike.JdkVirtualThreadPollerAdaptor(jdkPoller)
                        codeBuilder.ldc(JDK_POLLER_ADAPTOR_CLASS_NAME);
                        codeBuilder.iconst_1();
                        codeBuilder.aload(0);
                        codeBuilder.invokestatic(classDesc, "forName", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                        codeBuilder.astore(4);

                        codeBuilder.aload(2);
                        codeBuilder.aload(4);
                        codeBuilder.invokevirtual(classDesc, "getConstructors", MethodTypeDesc.ofDescriptor("()[Ljava/lang/reflect/Constructor;"));
                        codeBuilder.iconst_0();
                        codeBuilder.aaload();
                        codeBuilder.invokevirtual(lookupDesc, "unreflectConstructor",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, objectDesc, objectDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhAdaptorCtorFieldName, methodHandleDesc);

                        // _mhCtor = lookup.unreflectConstructor(customerPollerClass.getConstructors()[0])
                        //              .asType(MethodType.methodType(Object.class, Object.class))
                        ClassDesc constructorDesc = ClassDesc.ofDescriptor("Ljava/lang/reflect/Constructor;");
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.invokevirtual(classDesc, "getConstructors", MethodTypeDesc.ofDescriptor("()[Ljava/lang/reflect/Constructor;"));
                        codeBuilder.iconst_0();
                        codeBuilder.aaload();
                        codeBuilder.invokevirtual(lookupDesc, "unreflectConstructor",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, objectDesc, objectDesc, intDesc, booleanDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhCtorFieldName, methodHandleDesc);

                        // _mhImplRead
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("implRead");
                        emitMethodType(codeBuilder, intDesc, intDesc, byteArrayDesc, intDesc, intDesc, longDesc, booleanSupplierDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, intDesc, objectDesc, intDesc, byteArrayDesc, intDesc, intDesc, longDesc, booleanSupplierDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhImplReadFieldName, methodHandleDesc);

                        // _mhImplWrite
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("implWrite");
                        emitMethodType(codeBuilder, intDesc, intDesc, byteArrayDesc, intDesc, intDesc, booleanSupplierDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, intDesc, objectDesc, intDesc, byteArrayDesc, intDesc, intDesc, booleanSupplierDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhImplWriteFieldName, methodHandleDesc);

                        // _mhImplStartPoll
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("implStartPoll");
                        emitMethodType(codeBuilder, voidDesc, intDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, voidDesc, objectDesc, intDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhImplStartPollFieldName, methodHandleDesc);

                        // _mhImplStopPoll
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("implStopPoll");
                        emitMethodType(codeBuilder, voidDesc, intDesc, booleanDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, voidDesc, objectDesc, intDesc, booleanDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhImplStopPollFieldName, methodHandleDesc);

                        // _mhPoll(int timeout)
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("poll");
                        emitMethodType(codeBuilder, intDesc, intDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, intDesc, objectDesc, intDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhPollFieldName, methodHandleDesc);

                        // _mhClose
                        codeBuilder.aload(2);
                        codeBuilder.aload(1);
                        codeBuilder.ldc("close");
                        emitMethodType(codeBuilder, voidDesc);
                        codeBuilder.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(codeBuilder, voidDesc, objectDesc);
                        codeBuilder.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        codeBuilder.putstatic(proxyDesc, mhCloseFieldName, methodHandleDesc);

                        codeBuilder.labelBinding(tryEnd);
                        codeBuilder.branch(Opcode.GOTO, returnLabel);

                        codeBuilder.labelBinding(catchLabel);
                        codeBuilder.astore(3);
                        codeBuilder.new_(runtimeExceptionDesc);
                        codeBuilder.dup();
                        codeBuilder.ldc("init " + pollerImplClass + " fail!");
                        codeBuilder.aload(3);
                        codeBuilder.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
                        codeBuilder.athrow();

                        codeBuilder.labelBinding(returnLabel);
                        codeBuilder.return_();
                        codeBuilder.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
                    }));

            classBuilder.withMethod("implRead", implReadDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhImplReadFieldName, methodHandleDesc);
                    cb.aload(0);
                    cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.lload(5);
                    cb.aload(7);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", mhImplReadInvokeDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implWrite", implWriteDesc, 0, methodBuilder -> {
                methodBuilder.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                methodBuilder.withCode(cb -> {
                    cb.getstatic(proxyDesc, mhImplWriteFieldName, methodHandleDesc);
                    cb.aload(0);
                    cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                    cb.iload(1);
                    cb.aload(2);
                    cb.iload(3);
                    cb.iload(4);
                    cb.aload(5);
                    cb.invokevirtual(methodHandleDesc, "invokeExact", mhImplWriteInvokeDesc);
                    cb.ireturn();
                });
            });

            classBuilder.withMethod("implStartPoll", implStartDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhImplStartPollFieldName, methodHandleDesc);
                        cb.aload(0);
                        cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                        cb.iload(1);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", mhImplStartInvokeDesc);
                        cb.return_();
                    }));

            classBuilder.withMethod("implStopPoll", implStopDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhImplStopPollFieldName, methodHandleDesc);
                        cb.aload(0);
                        cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                        cb.iload(1);
                        cb.iload(2);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", mhImplStopInvokeDesc);
                        cb.return_();
                    }));

            classBuilder.withMethod("poll", pollDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhPollFieldName, methodHandleDesc);
                        cb.aload(0);
                        cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                        cb.iload(1);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", mhPollInvokeDesc);
                        cb.ireturn();
                    }));

            classBuilder.withMethod("close", closeDesc, 0, methodBuilder ->
                    methodBuilder.withCode(cb -> {
                        cb.getstatic(proxyDesc, mhCloseFieldName, methodHandleDesc);
                        cb.aload(0);
                        cb.getfield(proxyDesc, pollerInstanceFieldName, objectDesc);
                        cb.invokevirtual(methodHandleDesc, "invokeExact", mhCloseInvokeDesc);
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
