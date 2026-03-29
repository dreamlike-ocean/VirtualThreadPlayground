package io.github.dreamlike.scheduler.agent;

import java.lang.classfile.*;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;

/**
 * Bytecode generation toolkit for the unified virtual-thread Runtime agent.
 * <p>
 * Generates / transforms four targets:
 * <ol>
 *   <li>{@code transformPoller} — rewrites {@code sun.nio.ch.Poller}: renames {@code createPollerGroup}
 *       to {@code createPollerGroup0}, adds a {@code public static final Object jdkPoller} field, makes
 *       {@code POLLER_GROUP} public, and generates a new {@code createPollerGroup} that wraps the JDK
 *       group with {@code JdkProxyVirtualThreadRuntime} and stores the adaptor.</li>
 *   <li>{@code jdkPollerGroupToVirtualThreadPollerAdaptor} — generates {@code JdkVirtualThreadPollerAdaptor}
 *       (in App ClassLoader) that implements {@code VirtualThreadPoller} (pure I/O) by wrapping a JDK
 *       PollerGroup via static final MethodHandles.</li>
 *   <li>{@code jdkProxyVirtualThreadRuntime} — generates {@code JdkProxyVirtualThreadRuntime} (injected
 *       into {@code sun.nio.ch}) that extends {@code Poller$PollerGroup} <b>and</b> implements
 *       {@code Thread$VirtualThreadScheduler}. User-customizable I/O methods delegate via MH;
 *       scheduling methods ({@code onStart}, {@code onContinue}) also delegate via MH.
 *       Fallback methods (masterPoller, readPollers, …) use direct invokevirtual.</li>
   *   <li>{@code transformVirtualThread} &mdash; rewrites {@code java.lang.VirtualThread}:
   *       replaces {@code loadCustomScheduler} body to return {@code Poller.POLLER_GROUP}.</li>
 * </ol>
 */
final class AgentBytecodeToolkit {

    static final String JDK_POLLER_GROUP_ADAPTOR_CLASS_NAME = "io.github.dreamlike.scheduler.agent.JdkVirtualThreadPollerAdaptor";
    static final String CORE_POLLER_INTERFACE_NAME = "io.github.dreamlike.VirtualThreadPoller";

    /**
     * Proxy class name — resolved from {@code VirtualThreadSchedulerAgent}.
     * Value: {@code "sun.nio.ch.JdkProxyVirtualThreadRuntime"}.
     */
    static final String PROXY_RUNTIME_CLASS_NAME = VirtualThreadSchedulerAgent.PROXY_RUNTIME_CLASS_NAME;

    private AgentBytecodeToolkit() {
    }

    // ==================== 1. transformPoller ====================

    /**
     * Transforms {@code sun.nio.ch.Poller}:
     * <ul>
     *   <li>Renames {@code createPollerGroup()} to {@code createPollerGroup0()}</li>
     *   <li>Adds {@code public static final Object jdkPoller} field</li>
     *   <li>Changes {@code POLLER_GROUP} from private to public</li>
     *   <li>Generates a new {@code createPollerGroup()} that:
     *       (1) calls {@code createPollerGroup0()} → jdkGroup,
     *       (2) creates {@code new JdkProxyVirtualThreadRuntime(jdkGroup)} → proxy,
     *       (3) stores {@code proxy.adaptor} to {@code Poller.jdkPoller},
     *       (4) returns the proxy</li>
     * </ul>
     */
    public static byte[] transformPoller(byte[] pollerBytecode) {
        ClassFile classFile = ClassFile.of();
        ClassModel pollerModel = classFile.parse(pollerBytecode);
        ClassDesc pollerDesc = pollerModel.thisClass().asSymbol();
        ClassDesc pollerGroupDesc = ClassDesc.of("sun.nio.ch.Poller$PollerGroup");
        ClassDesc proxyDesc = ClassDesc.of(PROXY_RUNTIME_CLASS_NAME);

        return classFile.build(pollerDesc, classBuilder -> {
            // --- Pass 1: iterate existing class elements ---
            for (ClassElement element : pollerModel) {
                if (element instanceof MethodModel methodModel) {
                    String methodName = methodModel.methodName().stringValue();
                    if ("createPollerGroup".equals(methodName)) {
                        // Rename createPollerGroup → createPollerGroup0
                        classBuilder.withMethod(
                                "createPollerGroup0",
                                methodModel.methodTypeSymbol(),
                                methodModel.flags().flagsMask(),
                                mb -> mb.transform(methodModel, MethodTransform.ACCEPT_ALL)
                        );
                        continue; // skip adding the original
                    }
                }

                // Make POLLER_GROUP field public (was private)
                if (element instanceof FieldModel fieldModel) {
                    String fieldName = fieldModel.fieldName().stringValue();
                    if ("POLLER_GROUP".equals(fieldName)) {
                        int newFlags = (fieldModel.flags().flagsMask() & ~AccessFlag.PRIVATE.mask())
                                | AccessFlag.PUBLIC.mask();
                        classBuilder.withField(fieldName, fieldModel.fieldTypeSymbol(),
                                fb -> fb.withFlags(newFlags));
                        continue;
                    }
                }

                classBuilder.with(element);
            }

            // --- Add new field: public static Object jdkPoller ---
            classBuilder.withField("jdkPoller", ConstantDescs.CD_Object,
                    fb -> fb.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC));

            // --- Generate new createPollerGroup() ---
            MethodTypeDesc createPollerGroupDesc = MethodTypeDesc.ofDescriptor("()Lsun/nio/ch/Poller$PollerGroup;");
            MethodTypeDesc proxyCtorDesc = MethodTypeDesc.ofDescriptor("(Lsun/nio/ch/Poller$PollerGroup;)V");

            classBuilder.withMethod("createPollerGroup", createPollerGroupDesc,
                    AccessFlag.PRIVATE.mask() | AccessFlag.STATIC.mask(),
                    mb -> {
                        mb.withCode(cb -> {
                            // PollerGroup jdkGroup = createPollerGroup0();
                            cb.invokestatic(pollerDesc, "createPollerGroup0", createPollerGroupDesc);
                            cb.astore(0); // local 0 = jdkGroup

                            // JdkProxyVirtualThreadRuntime proxy = new JdkProxyVirtualThreadRuntime(jdkGroup);
                            cb.new_(proxyDesc);
                            cb.dup();
                            cb.aload(0);
                            cb.invokespecial(proxyDesc, ConstantDescs.INIT_NAME, proxyCtorDesc);
                            cb.astore(1); // local 1 = proxy

                            // Poller.jdkPoller = proxy.adaptor;
                            cb.aload(1);
                            cb.getfield(proxyDesc, "adaptor", ConstantDescs.CD_Object);
                            cb.putstatic(pollerDesc, "jdkPoller", ConstantDescs.CD_Object);

                            // return proxy;
                            cb.aload(1);
                            cb.areturn();
                        });
                    });
        });
    }

    // ==================== 2. JdkVirtualThreadPollerAdaptor ====================

    /**
     * Generates {@code JdkVirtualThreadPollerAdaptor} that implements {@code VirtualThreadPoller}
     * (pure I/O interface — no onStart/onContinue) by wrapping a JDK {@code PollerGroup} instance.
     * Uses static final MethodHandles resolved in {@code <clinit>} via {@code privateLookupIn(Poller.class)}.
     *
     * <pre>
     * class JdkVirtualThreadPollerAdaptor implements VirtualThreadPoller {
     *     static final MethodHandle _mhPoll;           // PollerGroup.poll(int,int,long,BooleanSupplier)
     *     static final MethodHandle _mhPollSelector;   // PollerGroup.pollSelector(int,long)
     *     static final MethodHandle _mhStart;          // PollerGroup.start()
     *     final Object _jdkPollerGroup;
     *
     *     JdkVirtualThreadPollerAdaptor(Object pollerGroup) { this._jdkPollerGroup = pollerGroup; }
     *     void poll(...)         { _mhPoll.invokeExact(_jdkPollerGroup, ...); }
     *     void pollSelector(...) { _mhPollSelector.invokeExact(_jdkPollerGroup, ...); }
     *     void start()           { _mhStart.invokeExact(_jdkPollerGroup); }
     * }
     * </pre>
     */
    static byte[] jdkPollerGroupToVirtualThreadPollerAdaptor() {
        ClassFile classFile = ClassFile.of();
        ClassDesc thisClass = ClassDesc.of(JDK_POLLER_GROUP_ADAPTOR_CLASS_NAME);
        ClassDesc pollerGroupDesc = ClassDesc.of("sun.nio.ch.Poller$PollerGroup");
        ClassDesc pollerDesc = ClassDesc.of("sun.nio.ch.Poller");
        ClassDesc booleanSupplierDesc = ClassDesc.of("java.util.function.BooleanSupplier");
        ClassDesc methodHandleDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandle;");
        ClassDesc methodHandlesDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles;");
        ClassDesc lookupDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;");
        ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
        ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");
        ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");

        String jdkFieldName = "_jdkPollerGroup";
        String mhPoll = "_mhPoll";
        String mhPollSelector = "_mhPollSelector";
        String mhStart = "_mhStart";

        return classFile.build(thisClass, cb -> {
            cb.withFlags(AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask());
            cb.withInterfaceSymbols(ClassDesc.of(CORE_POLLER_INTERFACE_NAME));

            // Fields
            cb.withField(jdkFieldName, ConstantDescs.CD_Object,
                    fieldBuilder -> fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));
            cb.withField(mhPoll, methodHandleDesc,
                    fieldBuilder -> fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            cb.withField(mhPollSelector, methodHandleDesc,
                    fieldBuilder -> fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            cb.withField(mhStart, methodHandleDesc,
                    fieldBuilder -> fieldBuilder.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));

            // Constructor: JdkVirtualThreadPollerAdaptor(Object pollerGroup)
            cb.withMethodBody(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object),
                    AccessFlag.PUBLIC.mask(),
                    code -> {
                        code.aload(0);
                        code.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                        code.aload(0);
                        code.aload(1);
                        code.putfield(thisClass, jdkFieldName, ConstantDescs.CD_Object);
                        code.return_();
                    });

            // <clinit>: resolve MHs via privateLookupIn(Poller.class)
            cb.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(), code -> {
                Label tryStart = code.newLabel();
                Label tryEnd = code.newLabel();
                Label catchLabel = code.newLabel();
                Label returnLabel = code.newLabel();

                code.labelBinding(tryStart);

                // Lookup lookup = MethodHandles.lookup();
                code.invokestatic(methodHandlesDesc, "lookup",
                        MethodTypeDesc.ofDescriptor("()Ljava/lang/invoke/MethodHandles$Lookup;"));
                code.astore(0);

                // Lookup pollerLookup = MethodHandles.privateLookupIn(Poller.class, lookup);
                code.ldc(pollerDesc);
                code.aload(0);
                code.invokestatic(methodHandlesDesc, "privateLookupIn",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;"));
                code.astore(1);

                // Class<?> pollerGroupClass = Class.forName("sun.nio.ch.Poller$PollerGroup", false, null);
                code.ldc("sun.nio.ch.Poller$PollerGroup");
                code.iconst_0();
                code.aconst_null();
                code.invokestatic(ClassDesc.of("java.lang.Class"), "forName",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                code.astore(2);

                // _mhPoll = pollerLookup.findVirtual(PollerGroup.class, "poll", MethodType(void, int, int, long, BooleanSupplier))
                code.aload(1);
                code.aload(2);
                code.ldc("poll");
                emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int,
                        ConstantDescs.CD_long, booleanSupplierDesc);
                code.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                // asType to (Object, int, int, long, BooleanSupplier)V
                emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_int,
                        ConstantDescs.CD_int, ConstantDescs.CD_long, booleanSupplierDesc);
                code.invokevirtual(methodHandleDesc, "asType",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                code.putstatic(thisClass, mhPoll, methodHandleDesc);

                // _mhPollSelector = pollerLookup.findVirtual(PollerGroup.class, "pollSelector", MethodType(void, int, long))
                code.aload(1);
                code.aload(2);
                code.ldc("pollSelector");
                emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_long);
                code.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_long);
                code.invokevirtual(methodHandleDesc, "asType",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                code.putstatic(thisClass, mhPollSelector, methodHandleDesc);

                // _mhStart = pollerLookup.findVirtual(PollerGroup.class, "start", MethodType(void))
                code.aload(1);
                code.aload(2);
                code.ldc("start");
                emitMethodType(code, ConstantDescs.CD_void);
                code.invokevirtual(lookupDesc, "findVirtual",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_Object);
                code.invokevirtual(methodHandleDesc, "asType",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                code.putstatic(thisClass, mhStart, methodHandleDesc);

                code.labelBinding(tryEnd);
                code.branch(Opcode.GOTO, returnLabel);

                code.labelBinding(catchLabel);
                code.astore(3);
                code.new_(runtimeExceptionDesc);
                code.dup();
                code.ldc("init JdkVirtualThreadPollerAdaptor fail!");
                code.aload(3);
                code.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
                code.athrow();

                code.labelBinding(returnLabel);
                code.return_();
                code.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
            });

            int methodFlags = AccessFlag.PUBLIC.mask() | AccessFlag.FINAL.mask();

            // void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException
            MethodTypeDesc pollDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_long, booleanSupplierDesc);
            MethodTypeDesc mhPollInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IIJLjava/util/function/BooleanSupplier;)V");
            cb.withMethod("poll", pollDesc, methodFlags, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(code -> {
                    code.getstatic(thisClass, mhPoll, methodHandleDesc);
                    code.aload(0);
                    code.getfield(thisClass, jdkFieldName, ConstantDescs.CD_Object);
                    code.iload(1); // fdVal
                    code.iload(2); // event
                    code.lload(3); // nanos
                    code.aload(5); // isOpen
                    code.invokevirtual(methodHandleDesc, "invokeExact", mhPollInvokeDesc);
                    code.return_();
                });
            });

            // void pollSelector(int fdVal, long nanos) throws IOException
            MethodTypeDesc pollSelectorDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_int, ConstantDescs.CD_long);
            MethodTypeDesc mhPollSelectorInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IJ)V");
            cb.withMethod("pollSelector", pollSelectorDesc, methodFlags, mb -> {
                mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                mb.withCode(code -> {
                    code.getstatic(thisClass, mhPollSelector, methodHandleDesc);
                    code.aload(0);
                    code.getfield(thisClass, jdkFieldName, ConstantDescs.CD_Object);
                    code.iload(1); // fdVal
                    code.lload(2); // nanos
                    code.invokevirtual(methodHandleDesc, "invokeExact", mhPollSelectorInvokeDesc);
                    code.return_();
                });
            });

            // void start()
            MethodTypeDesc startDesc = MethodTypeDesc.of(ConstantDescs.CD_void);
            MethodTypeDesc mhStartInvokeDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V");
            cb.withMethodBody("start", startDesc, methodFlags, code -> {
                code.getstatic(thisClass, mhStart, methodHandleDesc);
                code.aload(0);
                code.getfield(thisClass, jdkFieldName, ConstantDescs.CD_Object);
                code.invokevirtual(methodHandleDesc, "invokeExact", mhStartInvokeDesc);
                code.return_();
            });
        });
    }

    // ==================== 3. JdkProxyVirtualThreadRuntime ====================

    /**
     * Generates {@code JdkProxyVirtualThreadRuntime extends Poller$PollerGroup implements
     * Thread$VirtualThreadScheduler}, injected into {@code sun.nio.ch}.
     * <p>
     * The proxy is the <b>single unified Runtime object</b>: it is both {@code POLLER_GROUP}
     * and {@code DEFAULT_SCHEDULER}. I/O methods ({@code poll}, {@code pollSelector}, {@code start})
     * delegate to the user's Runtime via MH. Scheduling methods ({@code onStart}, {@code onContinue})
     * also delegate via MH. Fallback JDK methods (masterPoller, readPollers, writePollers, useLazyUnpark)
     * use direct {@code invokevirtual} on the stored JDK PollerGroup.
     * <p>
     * The {@code public final Object adaptor} field stores the {@code JdkVirtualThreadPollerAdaptor}
     * instance, which {@code createPollerGroup()} reads and stores to {@code Poller.jdkPoller}.
     *
     * @param proxyClassName the fully-qualified proxy class name (e.g. "sun.nio.ch.JdkProxyVirtualThreadRuntime")
     * @param pollerImplClass the user's VirtualThreadRuntime implementation class name
     */
    static byte[] jdkProxyVirtualThreadRuntime(String proxyClassName, String pollerImplClass) {
        ClassFile classFile = ClassFile.of();

        ClassDesc proxyDesc = ClassDesc.of(proxyClassName);
        ClassDesc pollerGroupDesc = ClassDesc.of("sun.nio.ch.Poller$PollerGroup");
        ClassDesc pollerProviderDesc = ClassDesc.of("sun.nio.ch.PollerProvider");
        ClassDesc pollerDesc = ClassDesc.of("sun.nio.ch.Poller");
        ClassDesc objectDesc = ConstantDescs.CD_Object;
        ClassDesc methodHandleDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandle;");
        ClassDesc methodHandlesDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles;");
        ClassDesc lookupDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodHandles$Lookup;");
        ClassDesc runtimeExceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/RuntimeException;");
        ClassDesc exceptionDesc = ClassDesc.ofDescriptor("Ljava/lang/Exception;");
        ClassDesc ioExceptionDesc = ClassDesc.ofDescriptor("Ljava/io/IOException;");
        ClassDesc booleanSupplierDesc = ClassDesc.of("java.util.function.BooleanSupplier");
        ClassDesc listDesc = ClassDesc.of("java.util.List");
        ClassDesc threadDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread;");
        ClassDesc classDescType = ClassDesc.ofDescriptor("Ljava/lang/Class;");
        ClassDesc classLoaderDesc = ClassDesc.ofDescriptor("Ljava/lang/ClassLoader;");
        // Thread$VirtualThreadScheduler — the JDK scheduling interface
        ClassDesc virtualThreadSchedulerDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread$VirtualThreadScheduler;");
        // Thread$VirtualThreadTask — parameter type for onStart/onContinue
        ClassDesc virtualThreadTaskDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread$VirtualThreadTask;");

        // Field names
        String jdkField = "jdk";
        String customerField = "customerPoller";
        String adaptorField = "adaptor";

        // Static final MH field names
        String mhAdaptorCtor = "_mhAdaptorCtor";
        String mhCtor = "_mhCtor";
        String mhPoll = "_mhPoll";
        String mhPollSelector = "_mhPollSelector";
        String mhStart = "_mhStart";
        String mhOnStart = "_mhOnStart";
        String mhOnContinue = "_mhOnContinue";

        return classFile.build(proxyDesc, cb -> {
            cb.withSuperclass(pollerGroupDesc);
            // Implement Thread$VirtualThreadScheduler
            cb.withInterfaceSymbols(virtualThreadSchedulerDesc);

            // ==================== Instance fields ====================

            // jdk: PollerGroup — direct type since proxy lives in sun.nio.ch
            cb.withField(jdkField, pollerGroupDesc,
                    fb -> fb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));
            // customerPoller: Object — the user's VirtualThreadRuntime instance
            cb.withField(customerField, objectDesc,
                    fb -> fb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));
            // adaptor: Object — the JdkVirtualThreadPollerAdaptor instance
            cb.withField(adaptorField, objectDesc,
                    fb -> fb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));

            // ==================== Static final MH fields ====================

            for (String f : new String[]{mhAdaptorCtor, mhCtor, mhPoll, mhPollSelector, mhStart, mhOnStart, mhOnContinue}) {
                cb.withField(f, methodHandleDesc,
                        fb -> fb.withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL));
            }

            // ==================== <clinit>: resolve all MHs ====================
            cb.withMethod(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, AccessFlag.STATIC.mask(),
                    mb -> mb.withCode(code -> {
                        Label tryStart = code.newLabel();
                        Label tryEnd = code.newLabel();
                        Label catchLabel = code.newLabel();
                        Label returnLabel = code.newLabel();

                        code.labelBinding(tryStart);

                        // ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        code.invokestatic(threadDesc, "currentThread",
                                MethodTypeDesc.ofDescriptor("()Ljava/lang/Thread;"));
                        code.invokevirtual(threadDesc, "getContextClassLoader",
                                MethodTypeDesc.ofDescriptor("()Ljava/lang/ClassLoader;"));
                        code.astore(0); // local 0 = cl

                        // if (cl == null) cl = ClassLoader.getSystemClassLoader();
                        Label hasCl = code.newLabel();
                        code.aload(0);
                        code.ifnonnull(hasCl);
                        code.invokestatic(classLoaderDesc, "getSystemClassLoader",
                                MethodTypeDesc.ofDescriptor("()Ljava/lang/ClassLoader;"));
                        code.astore(0);
                        code.labelBinding(hasCl);

                        // Class<?> customerClass = Class.forName(pollerImplClass, true, cl);
                        code.ldc(pollerImplClass);
                        code.iconst_1();
                        code.aload(0);
                        code.invokestatic(classDescType, "forName",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                        code.astore(1); // local 1 = customerClass

                        // Lookup publicLookup = MethodHandles.publicLookup();
                        code.invokestatic(methodHandlesDesc, "publicLookup",
                                MethodTypeDesc.ofDescriptor("()Ljava/lang/invoke/MethodHandles$Lookup;"));
                        code.astore(2); // local 2 = publicLookup

                        // --- _mhAdaptorCtor: (Object) → Object ---
                        // Class<?> adaptorClass = Class.forName(JDK_POLLER_GROUP_ADAPTOR_CLASS_NAME, true, cl);
                        code.ldc(JDK_POLLER_GROUP_ADAPTOR_CLASS_NAME);
                        code.iconst_1();
                        code.aload(0);
                        code.invokestatic(classDescType, "forName",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                        code.astore(3); // local 3 = adaptorClass

                        code.aload(2);
                        code.aload(3);
                        code.invokevirtual(classDescType, "getConstructors",
                                MethodTypeDesc.ofDescriptor("()[Ljava/lang/reflect/Constructor;"));
                        code.iconst_0();
                        code.aaload();
                        code.invokevirtual(lookupDesc, "unreflectConstructor",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, objectDesc, objectDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhAdaptorCtor, methodHandleDesc);

                        // --- _mhCtor: () → Object  (NO-ARG constructor) ---
                        code.aload(2);
                        code.aload(1);
                        code.invokevirtual(classDescType, "getConstructors",
                                MethodTypeDesc.ofDescriptor("()[Ljava/lang/reflect/Constructor;"));
                        code.iconst_0();
                        code.aaload();
                        code.invokevirtual(lookupDesc, "unreflectConstructor",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/reflect/Constructor;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, objectDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhCtor, methodHandleDesc);

                        // --- _mhPoll: (Object, int, int, long, BooleanSupplier) → void ---
                        code.aload(2);
                        code.aload(1);
                        code.ldc("poll");
                        emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int,
                                ConstantDescs.CD_long, booleanSupplierDesc);
                        code.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, ConstantDescs.CD_void, objectDesc, ConstantDescs.CD_int,
                                ConstantDescs.CD_int, ConstantDescs.CD_long, booleanSupplierDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhPoll, methodHandleDesc);

                        // --- _mhPollSelector: (Object, int, long) → void ---
                        code.aload(2);
                        code.aload(1);
                        code.ldc("pollSelector");
                        emitMethodType(code, ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_long);
                        code.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, ConstantDescs.CD_void, objectDesc, ConstantDescs.CD_int, ConstantDescs.CD_long);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhPollSelector, methodHandleDesc);

                        // --- _mhStart: (Object) → void ---
                        code.aload(2);
                        code.aload(1);
                        code.ldc("start");
                        emitMethodType(code, ConstantDescs.CD_void);
                        code.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, ConstantDescs.CD_void, objectDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhStart, methodHandleDesc);

                        // --- _mhOnStart: (Object, Thread$VirtualThreadTask) → void ---
                        code.aload(2);
                        code.aload(1);
                        code.ldc("onStart");
                        emitMethodType(code, ConstantDescs.CD_void, virtualThreadTaskDesc);
                        code.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, ConstantDescs.CD_void, objectDesc, virtualThreadTaskDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhOnStart, methodHandleDesc);

                        // --- _mhOnContinue: (Object, Thread$VirtualThreadTask) → void ---
                        code.aload(2);
                        code.aload(1);
                        code.ldc("onContinue");
                        emitMethodType(code, ConstantDescs.CD_void, virtualThreadTaskDesc);
                        code.invokevirtual(lookupDesc, "findVirtual",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        emitMethodType(code, ConstantDescs.CD_void, objectDesc, virtualThreadTaskDesc);
                        code.invokevirtual(methodHandleDesc, "asType",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"));
                        code.putstatic(proxyDesc, mhOnContinue, methodHandleDesc);

                        code.labelBinding(tryEnd);
                        code.branch(Opcode.GOTO, returnLabel);

                        code.labelBinding(catchLabel);
                        code.astore(4);
                        code.new_(runtimeExceptionDesc);
                        code.dup();
                        code.ldc("init " + pollerImplClass + " (ProxyVirtualThreadRuntime) fail!");
                        code.aload(4);
                        code.invokespecial(runtimeExceptionDesc, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/Throwable;)V"));
                        code.athrow();

                        code.labelBinding(returnLabel);
                        code.return_();
                        code.exceptionCatch(tryStart, tryEnd, catchLabel, exceptionDesc);
                    }));

            // ==================== Constructor ====================
            // JdkProxyVirtualThreadRuntime(PollerGroup jdkPollerGroup) {
            //     super(jdkPollerGroup.provider());
            //     this.jdk = jdkPollerGroup;
            //     Object adaptorObj = _mhAdaptorCtor.invokeExact((Object) jdkPollerGroup);
            //     this.adaptor = adaptorObj;
            //     this.customerPoller = _mhCtor.invokeExact();   // no-arg
            // }
            cb.withMethod(ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, pollerGroupDesc),
                    AccessFlag.PUBLIC.mask(),
                    mb -> {
                        mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                        mb.withCode(code -> {
                            // super(jdkPollerGroup.provider());
                            code.aload(0);
                            code.aload(1);
                            code.invokevirtual(pollerGroupDesc, "provider",
                                    MethodTypeDesc.of(pollerProviderDesc));
                            code.invokespecial(pollerGroupDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void, pollerProviderDesc));

                            // this.jdk = jdkPollerGroup;
                            code.aload(0);
                            code.aload(1);
                            code.putfield(proxyDesc, jdkField, pollerGroupDesc);

                            // Object adaptorObj = _mhAdaptorCtor.invokeExact((Object) jdkPollerGroup);
                            code.getstatic(proxyDesc, mhAdaptorCtor, methodHandleDesc);
                            code.aload(1);
                            code.invokevirtual(methodHandleDesc, "invokeExact",
                                    MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Ljava/lang/Object;"));
                            code.astore(2); // local 2 = adaptorObj

                            // this.adaptor = adaptorObj;
                            code.aload(0);
                            code.aload(2);
                            code.putfield(proxyDesc, adaptorField, objectDesc);

                            // this.customerPoller = _mhCtor.invokeExact();  // no-arg
                            code.aload(0);
                            code.getstatic(proxyDesc, mhCtor, methodHandleDesc);
                            code.invokevirtual(methodHandleDesc, "invokeExact",
                                    MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;"));
                            code.putfield(proxyDesc, customerField, objectDesc);

                            code.return_();
                        });
                    });

            // ==================== User-customizable I/O methods (MH → customerPoller) ====================

            cb.withMethod("poll",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int,
                            ConstantDescs.CD_long, booleanSupplierDesc),
                    0,
                    mb -> {
                        mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                        mb.withCode(code -> {
                            code.getstatic(proxyDesc, mhPoll, methodHandleDesc);
                            code.aload(0);
                            code.getfield(proxyDesc, customerField, objectDesc);
                            code.iload(1);
                            code.iload(2);
                            code.lload(3);
                            code.aload(5);
                            code.invokevirtual(methodHandleDesc, "invokeExact",
                                    MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IIJLjava/util/function/BooleanSupplier;)V"));
                            code.return_();
                        });
                    });

            cb.withMethod("pollSelector",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_long),
                    0,
                    mb -> {
                        mb.with(ExceptionsAttribute.ofSymbols(ioExceptionDesc));
                        mb.withCode(code -> {
                            code.getstatic(proxyDesc, mhPollSelector, methodHandleDesc);
                            code.aload(0);
                            code.getfield(proxyDesc, customerField, objectDesc);
                            code.iload(1);
                            code.lload(2);
                            code.invokevirtual(methodHandleDesc, "invokeExact",
                                    MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;IJ)V"));
                            code.return_();
                        });
                    });

            cb.withMethodBody("start",
                    MethodTypeDesc.of(ConstantDescs.CD_void), 0,
                    code -> {
                        code.getstatic(proxyDesc, mhStart, methodHandleDesc);
                        code.aload(0);
                        code.getfield(proxyDesc, customerField, objectDesc);
                        code.invokevirtual(methodHandleDesc, "invokeExact",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)V"));
                        code.return_();
                    });

            // ==================== Scheduling methods (MH → customerPoller) ====================

            // void onStart(Thread$VirtualThreadTask task)
            cb.withMethodBody("onStart",
                    MethodTypeDesc.of(ConstantDescs.CD_void, virtualThreadTaskDesc),
                    AccessFlag.PUBLIC.mask(),
                    code -> {
                        code.getstatic(proxyDesc, mhOnStart, methodHandleDesc);
                        code.aload(0);
                        code.getfield(proxyDesc, customerField, objectDesc);
                        code.aload(1); // task
                        code.invokevirtual(methodHandleDesc, "invokeExact",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Thread$VirtualThreadTask;)V"));
                        code.return_();
                    });

            // void onContinue(Thread$VirtualThreadTask task)
            cb.withMethodBody("onContinue",
                    MethodTypeDesc.of(ConstantDescs.CD_void, virtualThreadTaskDesc),
                    AccessFlag.PUBLIC.mask(),
                    code -> {
                        code.getstatic(proxyDesc, mhOnContinue, methodHandleDesc);
                        code.aload(0);
                        code.getfield(proxyDesc, customerField, objectDesc);
                        code.aload(1); // task
                        code.invokevirtual(methodHandleDesc, "invokeExact",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;Ljava/lang/Thread$VirtualThreadTask;)V"));
                        code.return_();
                    });

            // ==================== Fallback methods (direct invokevirtual on jdk field) ====================

            cb.withMethodBody("masterPoller",
                    MethodTypeDesc.of(pollerDesc), 0,
                    code -> {
                        code.aload(0);
                        code.getfield(proxyDesc, jdkField, pollerGroupDesc);
                        code.invokevirtual(pollerGroupDesc, "masterPoller", MethodTypeDesc.of(pollerDesc));
                        code.areturn();
                    });

            cb.withMethodBody("readPollers",
                    MethodTypeDesc.of(listDesc), 0,
                    code -> {
                        code.aload(0);
                        code.getfield(proxyDesc, jdkField, pollerGroupDesc);
                        code.invokevirtual(pollerGroupDesc, "readPollers", MethodTypeDesc.of(listDesc));
                        code.areturn();
                    });

            cb.withMethodBody("writePollers",
                    MethodTypeDesc.of(listDesc), 0,
                    code -> {
                        code.aload(0);
                        code.getfield(proxyDesc, jdkField, pollerGroupDesc);
                        code.invokevirtual(pollerGroupDesc, "writePollers", MethodTypeDesc.of(listDesc));
                        code.areturn();
                    });

            cb.withMethodBody("useLazyUnpark",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean), 0,
                    code -> {
                        code.aload(0);
                        code.getfield(proxyDesc, jdkField, pollerGroupDesc);
                        code.invokevirtual(pollerGroupDesc, "useLazyUnpark", MethodTypeDesc.of(ConstantDescs.CD_boolean));
                        code.ireturn();
                    });
        });
    }

    // ==================== 4. transformVirtualThread ====================

    /**
     * Transforms {@code java.lang.VirtualThread}: replaces {@code loadCustomScheduler}
     * method body with {@code return (VirtualThreadScheduler) Poller.POLLER_GROUP;}.
     * No schema changes (no field additions/removals) — safe for retransformClasses.
     */
    public static byte[] transformVirtualThread(byte[] virtualThreadBytecode) {
        ClassFile classFile = ClassFile.of();
        ClassModel vtModel = classFile.parse(virtualThreadBytecode);
        ClassDesc vtDesc = vtModel.thisClass().asSymbol();
        ClassDesc pollerDesc = ClassDesc.of("sun.nio.ch.Poller");
        ClassDesc pollerGroupDesc = ClassDesc.of("sun.nio.ch.Poller$PollerGroup");
        ClassDesc virtualThreadSchedulerDesc = ClassDesc.ofDescriptor("Ljava/lang/Thread$VirtualThreadScheduler;");

        return classFile.build(vtDesc, classBuilder -> {
            for (ClassElement element : vtModel) {
                // --- Method: replace loadCustomScheduler body ---
                if (element instanceof MethodModel methodModel) {
                    String methodName = methodModel.methodName().stringValue();
                    if ("loadCustomScheduler".equals(methodName)) {
                        // Replace method body:
                        //   return (VirtualThreadScheduler) Poller.POLLER_GROUP;
                        classBuilder.withMethod(
                                methodName,
                                methodModel.methodTypeSymbol(),
                                methodModel.flags().flagsMask(),
                                mb -> mb.withCode(code -> {
                                    // getstatic sun/nio/ch/Poller.POLLER_GROUP : Lsun/nio/ch/Poller$PollerGroup;
                                    code.getstatic(pollerDesc, "POLLER_GROUP", pollerGroupDesc);
                                    // checkcast java/lang/Thread$VirtualThreadScheduler
                                    code.checkcast(virtualThreadSchedulerDesc);
                                    // areturn
                                    code.areturn();
                                })
                        );
                        continue;
                    }
                }

                // All other elements (including <clinit>) pass through unchanged
                classBuilder.with(element);
            }
        });
    }

    // ==================== Utility ====================

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
        ClassDesc classDescType = ClassDesc.ofDescriptor("Ljava/lang/Class;");
        ClassDesc methodTypeDesc = ClassDesc.ofDescriptor("Ljava/lang/invoke/MethodType;");
        cb.ldc(returnType);
        emitIntConst(cb, paramTypes.length);
        cb.anewarray(classDescType);
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
