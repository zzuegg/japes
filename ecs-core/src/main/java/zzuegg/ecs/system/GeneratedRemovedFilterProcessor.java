package zzuegg.ecs.system;

import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;

import static java.lang.classfile.ClassFile.*;

/**
 * Tier-1 bytecode generator for {@code @Filter(Removed)} systems.
 * Emits a hidden class with a {@code run(long tick)} method that:
 * <ol>
 *   <li>Calls {@link RemovedFilterHelper#resolve} to get deduplicated
 *       entity + value arrays (zero per-tick allocation — reusable
 *       buffers as fields on the generated class).</li>
 *   <li>Iterates the result with inline component casts +
 *       {@code invokevirtual} to the user method.</li>
 * </ol>
 */
public final class GeneratedRemovedFilterProcessor {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER =
        new java.util.concurrent.atomic.AtomicLong();

    private GeneratedRemovedFilterProcessor() {}

    /**
     * Try to generate a tier-1 runner. Returns null if unsupported.
     */
    public static Runnable tryGenerate(
            SystemDescriptor desc,
            SystemExecutionPlan plan,
            RemovalLog log,
            ComponentId[] targetIds,
            World world,
            Object[] serviceArgs,
            ComponentId[] readCompIds,
            @SuppressWarnings("rawtypes") Class[] readTypes,
            int[] readParamIndices,
            int entityParamIdx) {
        if (desc.method() == null) return null;
        if (Modifier.isStatic(desc.method().getModifiers())) return null;
        try {
            return generate(desc, plan, log, targetIds, world, serviceArgs,
                readCompIds, readTypes, readParamIndices, entityParamIdx);
        } catch (Exception e) {
            throw new RuntimeException(
                "Tier-1 @Filter(Removed) generation failed for " + desc.name(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Runnable generate(
            SystemDescriptor desc,
            SystemExecutionPlan plan,
            RemovalLog log,
            ComponentId[] targetIds,
            World world,
            Object[] serviceArgs,
            ComponentId[] readCompIds,
            Class[] readTypes,
            int[] readParamIndices,
            int entityParamIdx) throws Exception {

        var method = desc.method();
        var params = method.getParameters();
        int paramCount = params.length;
        int readCount = readParamIndices.length;

        var systemLookup = MethodHandles.privateLookupIn(
            method.getDeclaringClass(), MethodHandles.lookup());
        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();

        var objDesc = ConstantDescs.CD_Object;
        var objArrDesc = objDesc.arrayType();
        var entityDesc = ClassDesc.of("zzuegg.ecs.entity.Entity");
        var entityArrDesc = entityDesc.arrayType();
        var runnerDesc = ClassDesc.of("java.lang.Runnable");
        var helperDesc = ClassDesc.of("zzuegg.ecs.system.RemovedFilterHelper");
        var logDesc = ClassDesc.of("zzuegg.ecs.change.RemovalLog");
        var cidDesc = ClassDesc.of("zzuegg.ecs.component.ComponentId");
        var cidArrDesc = cidDesc.arrayType();
        var worldDesc = ClassDesc.of("zzuegg.ecs.world.World");
        var planDesc = ClassDesc.of("zzuegg.ecs.system.SystemExecutionPlan");
        var classArrDesc = ClassDesc.of("java.lang.Class").arrayType();

        // User method descriptor.
        var userParamDescs = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            userParamDescs[i] = params[i].getType().describeConstable().orElseThrow();
        }
        var userMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, userParamDescs);

        // resolve() signature.
        var resolveDesc = MethodTypeDesc.of(
            ConstantDescs.CD_int,    // return: count
            logDesc,                 // RemovalLog
            cidArrDesc,              // ComponentId[] targetIds
            ConstantDescs.CD_long,   // lastSeen
            worldDesc,               // World
            cidArrDesc,              // ComponentId[] readCompIds
            classArrDesc,            // Class[] readTypes
            entityArrDesc,           // Entity[] entities
            objArrDesc,              // Object[] values
            ConstantDescs.CD_int     // stride
        );

        var sanitized = sanitize(desc.name());
        var genName = method.getDeclaringClass().getPackageName()
            + "." + "RmvProc_" + sanitized + "_" + COUNTER.incrementAndGet();
        var genDesc = ClassDesc.of(genName);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(runnerDesc));

            // Fields.
            clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("plan", planDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("log", logDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("targetIds", cidArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("world", worldDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("readCompIds", cidArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("readTypes", classArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("services", objArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            // Reusable buffers.
            clb.withField("entities", entityArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("values", objArrDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // Default ctor.
            clb.withMethodBody("<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.invokespecial(ConstantDescs.CD_Object, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    cb.return_();
                });

            // run()
            clb.withMethodBody("run",
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {

                    // local 0: this
                    // local 1: count (int)
                    // local 2: i (int)
                    // local 3: hoisted inst (systemClass)
                    // local 4+: hoisted service locals
                    int countVar = 1;
                    int iVar = 2;
                    int instLocal = 3;
                    int nextLocal = 4;
                    int[] serviceLocal = new int[paramCount];
                    // Pre-compute which params are @Read.
                    var isReadParam = new boolean[paramCount];
                    for (int r : readParamIndices) isReadParam[r] = true;
                    for (int i = 0; i < paramCount; i++) {
                        serviceLocal[i] = -1;
                        if (i != entityParamIdx && !isReadParam[i]) {
                            serviceLocal[i] = nextLocal++;
                        }
                    }

                    // int count = RemovedFilterHelper.resolve(
                    //     this.log, this.targetIds, this.plan.lastSeenTick(),
                    //     this.world, this.readCompIds, this.readTypes,
                    //     this.entities, this.values, stride);
                    cb.aload(0); cb.getfield(genDesc, "log", logDesc);
                    cb.aload(0); cb.getfield(genDesc, "targetIds", cidArrDesc);
                    cb.aload(0); cb.getfield(genDesc, "plan", planDesc);
                    cb.invokevirtual(planDesc, "lastSeenTick",
                        MethodTypeDesc.of(ConstantDescs.CD_long));
                    cb.aload(0); cb.getfield(genDesc, "world", worldDesc);
                    cb.aload(0); cb.getfield(genDesc, "readCompIds", cidArrDesc);
                    cb.aload(0); cb.getfield(genDesc, "readTypes", classArrDesc);
                    cb.aload(0); cb.getfield(genDesc, "entities", entityArrDesc);
                    cb.aload(0); cb.getfield(genDesc, "values", objArrDesc);
                    cb.ldc(readCount);
                    cb.invokestatic(helperDesc, "resolve", resolveDesc);
                    cb.istore(countVar);

                    // Hoist inst + services.
                    cb.aload(0);
                    cb.getfield(genDesc, "inst", objDesc);
                    cb.checkcast(systemClassDesc);
                    cb.astore(instLocal);
                    for (int i = 0; i < paramCount; i++) {
                        if (serviceLocal[i] < 0) continue;
                        cb.aload(0);
                        cb.getfield(genDesc, "services", objArrDesc);
                        cb.ldc(i);
                        cb.aaload();
                        cb.checkcast(userParamDescs[i]);
                        cb.astore(serviceLocal[i]);
                    }

                    // for (int i = 0; i < count; i++) {
                    cb.iconst_0();
                    cb.istore(iVar);
                    var loopStart = cb.newLabel();
                    var loopEnd = cb.newLabel();
                    cb.labelBinding(loopStart);
                    cb.iload(iVar);
                    cb.iload(countVar);
                    cb.if_icmpge(loopEnd);

                    // Load inst for invokevirtual.
                    cb.aload(instLocal);

                    // Build args from entities[i] / values[i * stride + r] / services.
                    for (int p = 0; p < paramCount; p++) {
                        if (p == entityParamIdx) {
                            // entities[i]
                            cb.aload(0);
                            cb.getfield(genDesc, "entities", entityArrDesc);
                            cb.iload(iVar);
                            cb.aaload();
                        } else if (serviceLocal[p] >= 0) {
                            cb.aload(serviceLocal[p]);
                        } else {
                            // @Read param — find its index in readParamIndices
                            int readIdx = -1;
                            for (int r = 0; r < readParamIndices.length; r++) {
                                if (readParamIndices[r] == p) { readIdx = r; break; }
                            }
                            // values[i * stride + readIdx]
                            cb.aload(0);
                            cb.getfield(genDesc, "values", objArrDesc);
                            cb.iload(iVar);
                            cb.ldc(readCount);
                            cb.imul();
                            cb.ldc(readIdx);
                            cb.iadd();
                            cb.aaload();
                            cb.checkcast(userParamDescs[p]);
                        }
                    }
                    cb.invokevirtual(systemClassDesc, method.getName(), userMethodDesc);

                    cb.iinc(iVar, 1);
                    cb.goto_(loopStart);
                    cb.labelBinding(loopEnd);
                    cb.return_();
                });
        });

        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        clazz.getField("inst").set(instance, desc.instance());
        clazz.getField("plan").set(instance, plan);
        clazz.getField("log").set(instance, log);
        clazz.getField("targetIds").set(instance, targetIds);
        clazz.getField("world").set(instance, world);
        clazz.getField("readCompIds").set(instance, readCompIds);
        clazz.getField("readTypes").set(instance, readTypes);
        clazz.getField("services").set(instance, serviceArgs);

        // Reusable buffers — 1024 entities is a generous upper bound.
        int maxEntities = 1024;
        clazz.getField("entities").set(instance, new Entity[maxEntities]);
        clazz.getField("values").set(instance, new Object[maxEntities * readCount]);

        return (Runnable) instance;
    }

    private static String sanitize(String name) {
        var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && i > 0);
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
