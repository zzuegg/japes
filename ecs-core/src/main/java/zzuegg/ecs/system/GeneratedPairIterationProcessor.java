package zzuegg.ecs.system;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.relation.RelationStore;
import zzuegg.ecs.relation.TargetSlice;
import zzuegg.ecs.world.World;

import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import static java.lang.classfile.ClassFile.*;

/**
 * Tier-1 bytecode generator for {@code @ForEachPair} systems.
 *
 * <p>Emits a hidden class per system with a {@code run(long tick)}
 * method that:
 *
 * <ol>
 *   <li>Loads the {@link RelationStore}'s forward-map raw
 *       {@code long[]} keys and {@code Object[]} values from
 *       hoisted fields into locals.</li>
 *   <li>Walks the outer table slot-by-slot, skipping nulls.</li>
 *   <li>For each live source: resolves the source's
 *       {@code EntityLocation}, checks an archetype-identity
 *       cache, re-resolves source-side storages + trackers on a
 *       miss, and loads every {@code SOURCE_READ} component value
 *       into a local.</li>
 *   <li>Sets up the reusable source {@code Mut<T>} for every
 *       {@code SOURCE_WRITE} slot — one {@code setContext} +
 *       {@code resetValue} per source, not per pair.</li>
 *   <li>Inner loop walks the {@link TargetSlice}'s raw
 *       {@code long[]} target ids and {@code Object[]} payload
 *       values.</li>
 *   <li>For each pair: resolves the target's
 *       {@code EntityLocation}, checks a target-side archetype
 *       cache, re-resolves target-side storages on a miss, and
 *       loads every {@code TARGET_READ} component value.</li>
 *   <li>Invokes the user method via a direct
 *       {@code invokevirtual} — no {@code MethodHandle}, no
 *       {@code SystemInvoker}, no reflection.</li>
 *   <li>After the inner loop, flushes every {@code SOURCE_WRITE}
 *       {@code Mut} back to the source's chunk storage.</li>
 * </ol>
 *
 * <p>Unsupported cases fall back to {@link PairIterationProcessor}
 * (the reflective tier-2 path):
 * <ul>
 *   <li>More than 4 source-read component params</li>
 *   <li>More than 2 source-write component params</li>
 *   <li>More than 2 target-read component params</li>
 *   <li>Static system methods (easy to lift later)</li>
 * </ul>
 *
 * <p>These aren't fundamental — just bytecode-emission complexity
 * caps. The benchmark shape (1 source-read + 1 source-write +
 * 1 target-read + 1 payload + 1 service) fits comfortably.
 */
public final class GeneratedPairIterationProcessor {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER = new java.util.concurrent.atomic.AtomicLong();

    private GeneratedPairIterationProcessor() {}

    /** What this tier can't handle. Returns null when generation is supported. */
    static String skipReason(SystemDescriptor desc) {
        if (desc.pairIterationType() == null) return "not a @ForEachPair system";
        if (desc.method() == null) return "no method";
        if (Modifier.isStatic(desc.method().getModifiers())) return "static system method (tier-2 only)";
        int srcRead = 0, srcWrite = 0, tgtRead = 0;
        for (var access : desc.componentAccesses()) {
            if (access.fromTarget()) {
                if (access.accessType() == zzuegg.ecs.query.AccessType.READ) tgtRead++;
            } else {
                if (access.accessType() == zzuegg.ecs.query.AccessType.READ) srcRead++;
                else srcWrite++;
            }
        }
        if (srcRead > 4) return "more than 4 @Read source components";
        if (srcWrite > 2) return "more than 2 @Write source components";
        if (tgtRead > 2) return "more than 2 @FromTarget @Read components";
        return null;
    }

    /**
     * Try to emit a tier-1 runner for the given @ForEachPair system.
     * Returns null if the system shape isn't supported — the caller
     * should fall back to {@link PairIterationProcessor}.
     */
    public static PairIterationRunner tryGenerate(
            SystemDescriptor desc,
            World world,
            Object[] resolvedServiceArgs) {
        var reason = skipReason(desc);
        if (reason != null) return null;
        try {
            return generate(desc, world, resolvedServiceArgs);
        } catch (Exception e) {
            throw new RuntimeException(
                "Tier-1 @ForEachPair generation failed for " + desc.name(), e);
        }
    }

    private static PairIterationRunner generate(
            SystemDescriptor desc,
            World world,
            Object[] resolvedServiceArgs) throws Exception {

        var method = desc.method();
        var params = method.getParameters();
        int paramCount = params.length;
        var accesses = desc.componentAccesses();

        // ------- classify params once -------
        var kinds = new ParamKind[paramCount];
        var paramComponentId = new ComponentId[paramCount];
        @SuppressWarnings("rawtypes")
        var paramRecordClass = new Class[paramCount];
        int ci = 0;
        for (int i = 0; i < paramCount; i++) {
            var p = params[i];
            var pt = p.getType();
            if (p.isAnnotationPresent(Read.class)) {
                var access = accesses.get(ci++);
                kinds[i] = access.fromTarget() ? ParamKind.TARGET_READ : ParamKind.SOURCE_READ;
                paramComponentId[i] = access.componentId();
                paramRecordClass[i] = access.type();
            } else if (p.isAnnotationPresent(Write.class)) {
                var access = accesses.get(ci++);
                kinds[i] = ParamKind.SOURCE_WRITE;
                paramComponentId[i] = access.componentId();
                paramRecordClass[i] = access.type();
            } else if (pt == zzuegg.ecs.entity.Entity.class) {
                kinds[i] = desc.targetEntityParamSlots().contains(i)
                    ? ParamKind.TARGET_ENTITY : ParamKind.SOURCE_ENTITY;
            } else if (desc.pairValueParamSlot() == i) {
                kinds[i] = ParamKind.PAYLOAD;
                paramRecordClass[i] = pt;
            } else {
                kinds[i] = ParamKind.SERVICE;
            }
        }

        // Detect whether we need Entity objects for source/target.
        boolean hasSrcEntity0 = false, hasTgtEntity0 = false;
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] == ParamKind.SOURCE_ENTITY) hasSrcEntity0 = true;
            if (kinds[i] == ParamKind.TARGET_ENTITY) hasTgtEntity0 = true;
        }
        final boolean hasSrcEntity = hasSrcEntity0, hasTgtEntity = hasTgtEntity0;

        // SoA detection per component param.
        boolean[] isSoA = new boolean[paramCount];
        java.lang.reflect.RecordComponent[][] soaComps = new java.lang.reflect.RecordComponent[paramCount][];
        for (int i = 0; i < paramCount; i++) {
            if ((kinds[i] == ParamKind.SOURCE_READ || kinds[i] == ParamKind.SOURCE_WRITE || kinds[i] == ParamKind.TARGET_READ)
                && paramRecordClass[i] != null
                && Record.class.isAssignableFrom(paramRecordClass[i])
                && zzuegg.ecs.storage.SoAComponentStorage.isEligible((Class<? extends Record>) paramRecordClass[i])) {
                isSoA[i] = true;
                soaComps[i] = paramRecordClass[i].getRecordComponents();
            }
        }

        var systemLookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();

        // ------- JVM-level descriptors -------
        var storeDesc = ClassDesc.of("zzuegg.ecs.relation.RelationStore");
        var targetSliceDesc = ClassDesc.of("zzuegg.ecs.relation.TargetSlice");
        var entityDesc = ClassDesc.of("zzuegg.ecs.entity.Entity");
        var entityLocationDesc = ClassDesc.of("zzuegg.ecs.archetype.EntityLocation");
        var archetypeDesc = ClassDesc.of("zzuegg.ecs.archetype.Archetype");
        var chunkDesc = ClassDesc.of("zzuegg.ecs.storage.Chunk");
        var storageDesc = ClassDesc.of("zzuegg.ecs.storage.ComponentStorage");
        var componentIdDesc = ClassDesc.of("zzuegg.ecs.component.ComponentId");
        var mutDesc = ClassDesc.of("zzuegg.ecs.component.Mut");
        var trackerDesc = ClassDesc.of("zzuegg.ecs.change.ChangeTracker");
        var recordDesc = ClassDesc.of("java.lang.Record");
        var listDesc = ClassDesc.of("java.util.List");
        var arrayListDesc = ClassDesc.of("java.util.ArrayList");
        var runnerDesc = ClassDesc.of("zzuegg.ecs.system.PairIterationRunner");
        var objDesc = ConstantDescs.CD_Object;

        var objArrDesc = objDesc.arrayType();
        var longArrDesc = ClassDesc.ofDescriptor("[J");
        var cidArrDesc = componentIdDesc.arrayType();


        var sliceTargetIdsArrayDesc = MethodTypeDesc.of(longArrDesc);
        var sliceValuesArrayDesc = MethodTypeDesc.of(objArrDesc);
        var sliceSizeDesc = MethodTypeDesc.of(ConstantDescs.CD_int);
        var storeForwardKeysArrayDesc = MethodTypeDesc.of(longArrDesc);
        var storeForwardValuesArrayDesc = MethodTypeDesc.of(objArrDesc);
        var listGetDesc = MethodTypeDesc.of(objDesc, ConstantDescs.CD_int);
        var entityInit = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long);
        var entityIndex = MethodTypeDesc.of(ConstantDescs.CD_int);
        var locArchetype = MethodTypeDesc.of(archetypeDesc);
        var locChunkIdx = MethodTypeDesc.of(ConstantDescs.CD_int);
        var locSlotIdx = MethodTypeDesc.of(ConstantDescs.CD_int);
        var archChunks = MethodTypeDesc.of(listDesc);
        var chunkStorage = MethodTypeDesc.of(storageDesc, componentIdDesc);
        var chunkTracker = MethodTypeDesc.of(trackerDesc, componentIdDesc);
        var storageGet = MethodTypeDesc.of(recordDesc, ConstantDescs.CD_int);
        var storageSet = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, recordDesc);
        var mutFlush = MethodTypeDesc.of(recordDesc);

        // Build the JVM-level method type for the user's method.
        var userMethodParams = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            userMethodParams[i] = method.getParameterTypes()[i].describeConstable().orElseThrow();
        }
        var userMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, userMethodParams);

        // ------- generate class name + descriptor -------
        var sanitized = sanitize(desc.name());
        var genName = method.getDeclaringClass().getPackageName()
            + "." + "PairProc_" + sanitized + "_" + COUNTER.incrementAndGet();
        var genDesc = ClassDesc.of(genName);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(runnerDesc));

            // ------- instance fields -------
            clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("store", storeDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("entityLocations", arrayListDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("cids", cidArrDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("services", objArrDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // ------- ctor -------
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                cb.return_();
            });

            // ------- run(long tick) -------
            clb.withMethodBody("run",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long),
                ACC_PUBLIC, cb -> emitRun(cb,
                    genDesc, systemClassDesc, userMethodDesc, method.getName(),
                    kinds, paramCount, paramRecordClass,
                    hasSrcEntity, hasTgtEntity,
                    // descriptors
                    storeDesc, targetSliceDesc, entityDesc, entityLocationDesc,
                    archetypeDesc, chunkDesc, storageDesc, componentIdDesc,
                    mutDesc, trackerDesc, recordDesc, listDesc, arrayListDesc,
                    objDesc, objArrDesc, longArrDesc, cidArrDesc,
                    // methods
                    sliceTargetIdsArrayDesc, sliceValuesArrayDesc, sliceSizeDesc,
                    storeForwardKeysArrayDesc, storeForwardValuesArrayDesc,
                    listGetDesc, entityInit, entityIndex, locArchetype,
                    locChunkIdx, locSlotIdx, archChunks, chunkStorage,
                    chunkTracker, storageGet, storageSet,
                    mutFlush,
                    isSoA, soaComps));
        });

        // Define hidden class + populate fields.
        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        clazz.getField("inst").set(instance, desc.instance());
        @SuppressWarnings("unchecked")
        var store = (RelationStore<Record>) world.componentRegistry()
            .relationStore((Class<? extends Record>) desc.pairIterationType());
        clazz.getField("store").set(instance, store);
        clazz.getField("entityLocations").set(instance, world.entityLocationsView());

        var cids = new ComponentId[paramCount];
        for (int i = 0; i < paramCount; i++) cids[i] = paramComponentId[i];
        clazz.getField("cids").set(instance, cids);

        clazz.getField("services").set(instance, resolvedServiceArgs);

        return (PairIterationRunner) instance;
    }

    private enum ParamKind {
        SOURCE_READ, SOURCE_WRITE, TARGET_READ,
        SOURCE_ENTITY, TARGET_ENTITY, PAYLOAD, SERVICE
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

    // ----------------------------------------------------------------
    // The actual bytecode emission for run(long tick)
    // ----------------------------------------------------------------

    private static void emitRun(
            java.lang.classfile.CodeBuilder cb,
            ClassDesc genDesc,
            ClassDesc systemClassDesc,
            MethodTypeDesc userMethodDesc,
            String userMethodName,
            ParamKind[] kinds,
            int paramCount,
            @SuppressWarnings("rawtypes") Class[] paramRecordClass,
            boolean hasSrcEntity, boolean hasTgtEntity,
            // descriptors
            ClassDesc storeDesc, ClassDesc targetSliceDesc, ClassDesc entityDesc,
            ClassDesc entityLocationDesc, ClassDesc archetypeDesc, ClassDesc chunkDesc,
            ClassDesc storageDesc, ClassDesc componentIdDesc, ClassDesc mutDesc,
            ClassDesc trackerDesc, ClassDesc recordDesc, ClassDesc listDesc,
            ClassDesc arrayListDesc,
            ClassDesc objDesc, ClassDesc objArrDesc, ClassDesc longArrDesc,
            ClassDesc cidArrDesc,
            // method type descriptors
            MethodTypeDesc sliceTargetIdsArrayDesc, MethodTypeDesc sliceValuesArrayDesc,
            MethodTypeDesc sliceSizeDesc, MethodTypeDesc storeForwardKeysArrayDesc,
            MethodTypeDesc storeForwardValuesArrayDesc, MethodTypeDesc listGetDesc,
            MethodTypeDesc entityInit, MethodTypeDesc entityIndex,
            MethodTypeDesc locArchetype, MethodTypeDesc locChunkIdx,
            MethodTypeDesc locSlotIdx, MethodTypeDesc archChunks,
            MethodTypeDesc chunkStorage, MethodTypeDesc chunkTracker,
            MethodTypeDesc storageGet, MethodTypeDesc storageSet,
            MethodTypeDesc mutFlush,
            boolean[] isSoA, java.lang.reflect.RecordComponent[][] soaComps) {

        // ------- local variable layout -------
        // 0  this
        // 1-2 tick (long, 2 slots)
        // 3  outerKeys (long[])
        // 4  outerVals (Object[])
        // 5  entityLocations (List)
        // 6  outerLen (int) — cached outerKeys.length
        // 7  si (int) — outer loop index
        // 8  sliceRaw (Object, typed TargetSlice)
        // 9  source (Entity)
        // 10 srcLoc (EntityLocation)
        // 11 srcArch (Archetype)
        // 12 srcChunkIdx (int)
        // 13 srcChunk (Chunk)
        // 14 srcSlot (int)
        // 15 instLocal (systemClass)
        // 16..16+srcReadCount-1  source-read storage locals (Object → ComponentStorage)
        // 16..   source-read VALUE locals (Record subtype)
        // ...
        // We'll allocate dynamically as we go — a small helper tracks
        // the next free index.

        int slotLocal = 15;  // skip 0..14 reserved above
        // Pre-allocate per-param locals:
        //   param i:
        //     srcReadValueLocal[i] : Record   — reused across inner loop
        //     srcWriteStorageLocal[i] : ComponentStorage — resolved per source
        //     srcWriteTrackerLocal[i] : ChangeTracker — resolved per source
        //     tgtReadStorageLocal[i] : ComponentStorage — cached per target chunk
        int[] srcReadStorageLocal = new int[paramCount];
        int[] srcReadValueLocal = new int[paramCount];
        int[] srcWriteStorageLocal = new int[paramCount];
        int[] srcWriteTrackerLocal = new int[paramCount];
        int[] srcWriteMutLocal = new int[paramCount];
        int[] tgtReadStorageLocal = new int[paramCount];
        int[] serviceLocal = new int[paramCount];
        // Per-field array locals for SoA params — cached per archetype miss
        int[][] soaFieldLocals = new int[paramCount][];
        int instLocal = slotLocal++;
        for (int i = 0; i < paramCount; i++) {
            srcReadStorageLocal[i] = -1;
            srcReadValueLocal[i] = -1;
            srcWriteStorageLocal[i] = -1;
            srcWriteTrackerLocal[i] = -1;
            srcWriteMutLocal[i] = -1;
            tgtReadStorageLocal[i] = -1;
            serviceLocal[i] = -1;
            switch (kinds[i]) {
                case SOURCE_READ -> {
                    srcReadStorageLocal[i] = slotLocal++;
                    srcReadValueLocal[i] = slotLocal++;
                    if (isSoA[i]) {
                        soaFieldLocals[i] = new int[soaComps[i].length];
                        for (int f = 0; f < soaComps[i].length; f++)
                            soaFieldLocals[i][f] = slotLocal++;
                    }
                }
                case SOURCE_WRITE -> {
                    srcWriteStorageLocal[i] = slotLocal++;
                    srcWriteTrackerLocal[i] = slotLocal++;
                    srcWriteMutLocal[i] = slotLocal++;
                    if (isSoA[i]) {
                        soaFieldLocals[i] = new int[soaComps[i].length];
                        for (int f = 0; f < soaComps[i].length; f++)
                            soaFieldLocals[i][f] = slotLocal++;
                    }
                }
                case TARGET_READ -> {
                    tgtReadStorageLocal[i] = slotLocal++;
                    if (isSoA[i]) {
                        soaFieldLocals[i] = new int[soaComps[i].length];
                        for (int f = 0; f < soaComps[i].length; f++)
                            soaFieldLocals[i][f] = slotLocal++;
                    }
                }
                case SERVICE -> serviceLocal[i] = slotLocal++;
                default -> { /* no local */ }
            }
        }
        int outerKeysVar = 3;
        int outerValsVar = 4;
        int entityLocationsVar = 5;
        int outerLenVar = 6;
        int siVar = 7;
        int sliceVar = 8;
        int sourceVar = 9;
        int srcLocVar = 10;
        int srcSlotVar = 14;
        // source archetype cache (for re-resolving storages only on transition)
        int srcArchCacheVar = slotLocal++;
        int srcChunkIdxCacheVar = slotLocal++;
        // target archetype cache
        int tgtArchCacheVar = slotLocal++;
        int tgtChunkIdxCacheVar = slotLocal++;
        // inner loop state
        int targetIdsVar = slotLocal++;
        int payloadValsVar = slotLocal++;
        int nVar = slotLocal++;
        int piVar = slotLocal++;
        int targetVar = slotLocal++;
        int tgtLocVar = slotLocal++;
        int tgtSlotTempVar = slotLocal++;   // temp for target slot index (SoA reads)
        int tempRecordVar = slotLocal++;    // temp for record value during SoA set
        int srcIdVar = slotLocal++;         // raw long id — used when no SOURCE_ENTITY param (2 slots)
        slotLocal++;                        // long takes 2 slots
        int tgtIdVar = slotLocal++;         // raw long id — used when no TARGET_ENTITY param (2 slots)
        slotLocal++;                        // long takes 2 slots

        // --------------- emit preamble ---------------

        // Hoist this.inst (cast to systemClass) into instLocal.
        cb.aload(0);
        cb.getfield(genDesc, "inst", objDesc);
        cb.checkcast(systemClassDesc);
        cb.astore(instLocal);

        // Load store.forwardKeysArray / forwardValuesArray once.
        cb.aload(0);
        cb.getfield(genDesc, "store", storeDesc);
        cb.invokevirtual(storeDesc, "forwardKeysArray", storeForwardKeysArrayDesc);
        cb.astore(outerKeysVar);
        cb.aload(0);
        cb.getfield(genDesc, "store", storeDesc);
        cb.invokevirtual(storeDesc, "forwardValuesArray", storeForwardValuesArrayDesc);
        cb.astore(outerValsVar);

        // outerLen = outerKeys.length
        cb.aload(outerKeysVar);
        cb.arraylength();
        cb.istore(outerLenVar);

        // entityLocations = this.entityLocations (ArrayList — invokevirtual, not invokeinterface)
        cb.aload(0);
        cb.getfield(genDesc, "entityLocations", arrayListDesc);
        cb.astore(entityLocationsVar);

        // Hoist services into per-slot locals. We look up via
        // `this.services[i]` once and cast to the declared type.
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SERVICE) continue;
            cb.aload(0);
            cb.getfield(genDesc, "services", objArrDesc);
            cb.ldc(i);
            cb.aaload();
            // checkcast to declared type (from paramRecordClass... but services can be any type)
            // We stored the Class in paramRecordClass only for certain kinds. For SERVICE
            // we need the param type from the user method. Fall back to the method's
            // param class — already a ClassDesc in userMethodDesc.parameterType(i).
            cb.checkcast(userMethodDesc.parameterType(i));
            cb.astore(serviceLocal[i]);
        }

        // Initialize cache keys to "miss" values.
        cb.aconst_null();
        cb.astore(srcArchCacheVar);
        cb.ldc(-1);
        cb.istore(srcChunkIdxCacheVar);
        cb.aconst_null();
        cb.astore(tgtArchCacheVar);
        cb.ldc(-1);
        cb.istore(tgtChunkIdxCacheVar);

        // Initialise every conditionally-assigned local to null so
        // the stackmap verifier sees a consistent reference type on
        // both branches of the cache-check if. Without this, locals
        // written only inside the "miss" branch appear as {@code top}
        // at the merge point and verification fails.
        for (int i = 0; i < paramCount; i++) {
            if (srcReadStorageLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(srcReadStorageLocal[i]);
            }
            if (srcReadValueLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(srcReadValueLocal[i]);
            }
            if (srcWriteStorageLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(srcWriteStorageLocal[i]);
            }
            if (srcWriteTrackerLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(srcWriteTrackerLocal[i]);
            }
            if (srcWriteMutLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(srcWriteMutLocal[i]);
            }
            if (tgtReadStorageLocal[i] >= 0) {
                cb.aconst_null();
                cb.astore(tgtReadStorageLocal[i]);
            }
            // SoA per-field array locals — null-init for verifier
            if (soaFieldLocals[i] != null) {
                for (int f = 0; f < soaFieldLocals[i].length; f++) {
                    cb.aconst_null();
                    cb.astore(soaFieldLocals[i][f]);
                }
            }
        }

        // --------------- outer loop: si = 0; si < outerLen; si++ ---------------
        cb.ldc(0);
        cb.istore(siVar);

        var outerLoopStart = cb.newLabel();
        var outerLoopEnd = cb.newLabel();
        var outerContinue = cb.newLabel();

        cb.labelBinding(outerLoopStart);
        cb.iload(siVar);
        cb.iload(outerLenVar);
        cb.if_icmpge(outerLoopEnd);

        // sliceRaw = outerVals[si]
        cb.aload(outerValsVar);
        cb.iload(siVar);
        cb.aaload();
        cb.astore(sliceVar);
        // if sliceRaw == null: continue
        cb.aload(sliceVar);
        cb.ifnull(outerContinue);

        // Cast to TargetSlice
        cb.aload(sliceVar);
        cb.checkcast(targetSliceDesc);
        cb.astore(sliceVar);

        // Load raw source id from the forward table.
        cb.aload(outerKeysVar);
        cb.iload(siVar);
        cb.laload();
        cb.lstore(srcIdVar);

        if (hasSrcEntity) {
            // Only allocate Entity if the user method has a SOURCE_ENTITY param.
            cb.new_(entityDesc);
            cb.dup();
            cb.lload(srcIdVar);
            cb.invokespecial(entityDesc, "<init>", entityInit);
            cb.astore(sourceVar);
        }

        // Inline Entity.index(): (int)(id >>> 32) — avoids Entity allocation for lookup.
        // srcLoc = (EntityLocation) entityLocations.get((int)(srcId >>> 32))
        cb.aload(entityLocationsVar);
        cb.lload(srcIdVar);
        cb.ldc(32);
        cb.lushr();
        cb.l2i();
        cb.invokevirtual(arrayListDesc, "get", listGetDesc);
        cb.checkcast(entityLocationDesc);
        cb.astore(srcLocVar);
        // if srcLoc == null: continue
        cb.aload(srcLocVar);
        cb.ifnull(outerContinue);

        // srcArch = srcLoc.archetype()
        cb.aload(srcLocVar);
        cb.invokevirtual(entityLocationDesc, "archetype", locArchetype);
        // Stack: [srcArch] — we want to DUP so we can both cache-compare and keep for resolve
        // But simpler: store in a temp.
        int srcArchNewVar = slotLocal++;
        int srcChunkIdxNewVar = slotLocal++;
        int srcChunkTempVar = slotLocal++;
        cb.astore(srcArchNewVar);
        // srcChunkIdxNew = srcLoc.chunkIndex()
        cb.aload(srcLocVar);
        cb.invokevirtual(entityLocationDesc, "chunkIndex", locChunkIdx);
        cb.istore(srcChunkIdxNewVar);
        // srcSlot = srcLoc.slotIndex()
        cb.aload(srcLocVar);
        cb.invokevirtual(entityLocationDesc, "slotIndex", locSlotIdx);
        cb.istore(srcSlotVar);

        // if srcArchNew != srcArchCache || srcChunkIdxNew != srcChunkIdxCache { ... }
        var srcCacheHit = cb.newLabel();
        cb.aload(srcArchNewVar);
        cb.aload(srcArchCacheVar);
        var maybeHit = cb.newLabel();
        cb.if_acmpne(maybeHit);
        cb.iload(srcChunkIdxNewVar);
        cb.iload(srcChunkIdxCacheVar);
        cb.if_icmpeq(srcCacheHit);
        cb.labelBinding(maybeHit);

        // Cache miss: re-resolve srcChunk + every per-param storage/tracker.
        // ComponentStorage refs are chunk-bound, so as long as (archetype,
        // chunkIdx) is stable we can reuse the same storages across sources —
        // we only need to re-read the actual value per source because the
        // slot index changes.
        cb.aload(srcArchNewVar);
        cb.invokevirtual(archetypeDesc, "chunks", archChunks);
        cb.iload(srcChunkIdxNewVar);
        cb.invokeinterface(listDesc, "get", listGetDesc);
        cb.checkcast(chunkDesc);
        cb.astore(srcChunkTempVar);
        // SOURCE_READ params: cache the ComponentStorage ref per archetype.
        // For SoA params, also extract per-field arrays into locals.
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SOURCE_READ) continue;
            cb.aload(srcChunkTempVar);
            cb.aload(0);
            cb.getfield(genDesc, "cids", cidArrDesc);
            cb.ldc(i);
            cb.aaload();
            cb.invokevirtual(chunkDesc, "componentStorage", chunkStorage);
            cb.astore(srcReadStorageLocal[i]);
            if (isSoA[i]) {
                extractSoAFieldArrays(cb, srcReadStorageLocal[i], storageDesc,
                    soaComps[i], soaFieldLocals[i]);
            }
        }
        // SOURCE_WRITE params: cache both storage and tracker.
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SOURCE_WRITE) continue;
            // storage
            cb.aload(srcChunkTempVar);
            cb.aload(0);
            cb.getfield(genDesc, "cids", cidArrDesc);
            cb.ldc(i);
            cb.aaload();
            cb.invokevirtual(chunkDesc, "componentStorage", chunkStorage);
            cb.astore(srcWriteStorageLocal[i]);
            if (isSoA[i]) {
                extractSoAFieldArrays(cb, srcWriteStorageLocal[i], storageDesc,
                    soaComps[i], soaFieldLocals[i]);
            }
            // tracker
            cb.aload(srcChunkTempVar);
            cb.aload(0);
            cb.getfield(genDesc, "cids", cidArrDesc);
            cb.ldc(i);
            cb.aaload();
            cb.invokevirtual(chunkDesc, "changeTracker", chunkTracker);
            cb.astore(srcWriteTrackerLocal[i]);
        }
        // update cache keys
        cb.aload(srcArchNewVar);
        cb.astore(srcArchCacheVar);
        cb.iload(srcChunkIdxNewVar);
        cb.istore(srcChunkIdxCacheVar);

        cb.labelBinding(srcCacheHit);

        // Per-source SOURCE_READ values: use the cached storage ref, just
        // re-read the new slot. Cache hit or miss, the storage is valid —
        // we no longer re-resolve srcChunk or componentStorage() per source.
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SOURCE_READ) continue;
            if (isSoA[i]) {
                emitSoAGet(cb, srcSlotVar, soaComps[i],
                    soaFieldLocals[i], paramRecordClass[i]);
            } else {
                cb.aload(srcReadStorageLocal[i]);
                cb.iload(srcSlotVar);
                cb.invokeinterface(storageDesc, "get", storageGet);
                cb.checkcast((ClassDesc) paramRecordClass[i].describeConstable().orElseThrow());
            }
            cb.astore(srcReadValueLocal[i]);
        }

        // Fresh Mut per source — enables escape analysis.
        // The Mut is allocated, used across the inner loop (user method is inlined),
        // flushed after the inner loop, and discarded. It never escapes → EA
        // scalar-replaces it → the 5-6 field writes per source become registers.
        var mutInitDesc = MethodTypeDesc.of(
            ConstantDescs.CD_void,
            recordDesc,              // value
            ConstantDescs.CD_int,    // slot
            trackerDesc,             // tracker
            ConstantDescs.CD_long,   // tick
            ConstantDescs.CD_boolean // valueTracked
        );
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SOURCE_WRITE) continue;
            cb.new_(mutDesc);
            cb.dup();
            // value: storage.get(srcSlot) or SoA reconstruct
            if (isSoA[i]) {
                emitSoAGet(cb, srcSlotVar, soaComps[i],
                    soaFieldLocals[i], paramRecordClass[i]);
            } else {
                cb.aload(srcWriteStorageLocal[i]);
                cb.iload(srcSlotVar);
                cb.invokeinterface(storageDesc, "get", storageGet);
            }
            cb.iload(srcSlotVar);                // slot
            cb.aload(srcWriteTrackerLocal[i]);   // tracker
            cb.lload(1);                         // tick
            cb.iconst_0();                       // valueTracked = false
            cb.invokespecial(mutDesc, "<init>", mutInitDesc);
            cb.astore(srcWriteMutLocal[i]);
        }

        // --------------- inner loop setup ---------------
        // targetIds = slice.targetIdsArray()
        cb.aload(sliceVar);
        cb.invokevirtual(targetSliceDesc, "targetIdsArray", sliceTargetIdsArrayDesc);
        cb.astore(targetIdsVar);
        // payloadVals = slice.valuesArray()
        cb.aload(sliceVar);
        cb.invokevirtual(targetSliceDesc, "valuesArray", sliceValuesArrayDesc);
        cb.astore(payloadValsVar);
        // n = slice.size()
        cb.aload(sliceVar);
        cb.invokevirtual(targetSliceDesc, "size", sliceSizeDesc);
        cb.istore(nVar);

        // pi = 0
        cb.ldc(0);
        cb.istore(piVar);

        var innerLoopStart = cb.newLabel();
        var innerLoopEnd = cb.newLabel();
        var innerContinue = cb.newLabel();

        cb.labelBinding(innerLoopStart);
        cb.iload(piVar);
        cb.iload(nVar);
        cb.if_icmpge(innerLoopEnd);

        // Load raw target id.
        cb.aload(targetIdsVar);
        cb.iload(piVar);
        cb.laload();
        cb.lstore(tgtIdVar);

        if (hasTgtEntity) {
            // Only allocate Entity if user method has a TARGET_ENTITY param.
            cb.new_(entityDesc);
            cb.dup();
            cb.lload(tgtIdVar);
            cb.invokespecial(entityDesc, "<init>", entityInit);
            cb.astore(targetVar);
        }

        // Inline Entity.index(): (int)(id >>> 32) — avoids Entity allocation for lookup.
        cb.aload(entityLocationsVar);
        cb.lload(tgtIdVar);
        cb.ldc(32);
        cb.lushr();
        cb.l2i();
        cb.invokevirtual(arrayListDesc, "get", listGetDesc);
        cb.checkcast(entityLocationDesc);
        cb.astore(tgtLocVar);
        // if null: continue
        cb.aload(tgtLocVar);
        cb.ifnull(innerContinue);

        // Target archetype cache check
        int tgtArchNewVar = slotLocal++;
        int tgtChunkIdxNewVar = slotLocal++;
        int tgtChunkTempVar = slotLocal++;
        cb.aload(tgtLocVar);
        cb.invokevirtual(entityLocationDesc, "archetype", locArchetype);
        cb.astore(tgtArchNewVar);
        cb.aload(tgtLocVar);
        cb.invokevirtual(entityLocationDesc, "chunkIndex", locChunkIdx);
        cb.istore(tgtChunkIdxNewVar);

        var tgtCacheHit = cb.newLabel();
        cb.aload(tgtArchNewVar);
        cb.aload(tgtArchCacheVar);
        var maybeTgtHit = cb.newLabel();
        cb.if_acmpne(maybeTgtHit);
        cb.iload(tgtChunkIdxNewVar);
        cb.iload(tgtChunkIdxCacheVar);
        cb.if_icmpeq(tgtCacheHit);
        cb.labelBinding(maybeTgtHit);

        // Cache miss: re-resolve tgt storages
        cb.aload(tgtArchNewVar);
        cb.invokevirtual(archetypeDesc, "chunks", archChunks);
        cb.iload(tgtChunkIdxNewVar);
        cb.invokeinterface(listDesc, "get", listGetDesc);
        cb.checkcast(chunkDesc);
        cb.astore(tgtChunkTempVar);
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.TARGET_READ) continue;
            cb.aload(tgtChunkTempVar);
            cb.aload(0);
            cb.getfield(genDesc, "cids", cidArrDesc);
            cb.ldc(i);
            cb.aaload();
            cb.invokevirtual(chunkDesc, "componentStorage", chunkStorage);
            cb.astore(tgtReadStorageLocal[i]);
            if (isSoA[i]) {
                extractSoAFieldArrays(cb, tgtReadStorageLocal[i], storageDesc,
                    soaComps[i], soaFieldLocals[i]);
            }
        }
        cb.aload(tgtArchNewVar);
        cb.astore(tgtArchCacheVar);
        cb.iload(tgtChunkIdxNewVar);
        cb.istore(tgtChunkIdxCacheVar);
        cb.labelBinding(tgtCacheHit);

        // Build arg list and call the user method.
        cb.aload(instLocal);
        for (int i = 0; i < paramCount; i++) {
            switch (kinds[i]) {
                case SOURCE_READ -> cb.aload(srcReadValueLocal[i]);
                case SOURCE_WRITE -> cb.aload(srcWriteMutLocal[i]);
                case TARGET_READ -> {
                    if (isSoA[i]) {
                        // SoA: get slot index into temp, construct from cached per-field arrays
                        cb.aload(tgtLocVar);
                        cb.invokevirtual(entityLocationDesc, "slotIndex", locSlotIdx);
                        cb.istore(tgtSlotTempVar);
                        emitSoAGet(cb, tgtSlotTempVar, soaComps[i],
                            soaFieldLocals[i], paramRecordClass[i]);
                    } else {
                        cb.aload(tgtReadStorageLocal[i]);
                        cb.aload(tgtLocVar);
                        cb.invokevirtual(entityLocationDesc, "slotIndex", locSlotIdx);
                        cb.invokeinterface(storageDesc, "get", storageGet);
                        cb.checkcast((ClassDesc) paramRecordClass[i].describeConstable().orElseThrow());
                    }
                }
                case SOURCE_ENTITY -> cb.aload(sourceVar);
                case TARGET_ENTITY -> cb.aload(targetVar);
                case PAYLOAD -> {
                    cb.aload(payloadValsVar);
                    cb.iload(piVar);
                    cb.aaload();
                    cb.checkcast((ClassDesc) paramRecordClass[i].describeConstable().orElseThrow());
                }
                case SERVICE -> cb.aload(serviceLocal[i]);
            }
        }
        cb.invokevirtual(systemClassDesc, userMethodName, userMethodDesc);

        cb.labelBinding(innerContinue);
        cb.iinc(piVar, 1);
        cb.goto_(innerLoopStart);
        cb.labelBinding(innerLoopEnd);

        // --------------- post-inner: flush source writes ---------------
        var mutIsChanged = MethodTypeDesc.of(ConstantDescs.CD_boolean);
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] != ParamKind.SOURCE_WRITE) continue;
            var skipFlush = cb.newLabel();
            cb.aload(srcWriteMutLocal[i]);
            cb.invokevirtual(mutDesc, "isChanged", mutIsChanged);
            cb.ifeq(skipFlush);

            if (isSoA[i]) {
                cb.aload(srcWriteMutLocal[i]);
                cb.invokevirtual(mutDesc, "flush", mutFlush);
                emitSoASet(cb, srcSlotVar, tempRecordVar,
                    soaComps[i], soaFieldLocals[i], paramRecordClass[i]);
            } else {
                cb.aload(srcWriteStorageLocal[i]);
                cb.iload(srcSlotVar);
                cb.aload(srcWriteMutLocal[i]);
                cb.invokevirtual(mutDesc, "flush", mutFlush);
                cb.invokeinterface(storageDesc, "set", storageSet);
            }
            cb.labelBinding(skipFlush);
        }

        cb.labelBinding(outerContinue);
        cb.iinc(siVar, 1);
        cb.goto_(outerLoopStart);
        cb.labelBinding(outerLoopEnd);

        cb.return_();
    }

    /**
     * On archetype cache-miss: extract per-field primitive arrays from
     * the SoA storage's cached {@code soaFieldArrays()} into individual
     * locals. These locals are then used in the per-entity read/write
     * path — no invokeinterface per entity, enabling EA.
     */
    private static void extractSoAFieldArrays(
            java.lang.classfile.CodeBuilder cb, int storageLocal,
            ClassDesc storageDesc,
            java.lang.reflect.RecordComponent[] comps, int[] fieldLocals) {
        var objArrDesc = ConstantDescs.CD_Object.arrayType();
        // Object[] fa = storage.soaFieldArrays();
        cb.aload(storageLocal);
        cb.invokeinterface(storageDesc, "soaFieldArrays",
            MethodTypeDesc.of(objArrDesc));
        // For each field: fieldLocals[f] = (PrimType[]) fa[f];
        for (int f = 0; f < comps.length; f++) {
            if (f < comps.length - 1) cb.dup(); // keep fa on stack for next field
            cb.ldc(f);
            cb.aaload();
            cb.checkcast((ClassDesc) comps[f].getType().arrayType()
                .describeConstable().orElseThrow());
            cb.astore(fieldLocals[f]);
        }
    }

    /**
     * Emit SoA get: construct a record from cached per-field array locals.
     * Leaves the constructed record on the stack.
     * {@code fieldLocals[f]} holds the primitive array for field f;
     * {@code slotVar} is the iload source for the entity slot index.
     */
    private static void emitSoAGet(
            java.lang.classfile.CodeBuilder cb, int slotVar,
            java.lang.reflect.RecordComponent[] comps, int[] fieldLocals,
            @SuppressWarnings("rawtypes") Class recordClass) {
        var recDesc = (ClassDesc) recordClass.describeConstable().orElseThrow();
        cb.new_(recDesc);
        cb.dup();
        var ctorParams = new ClassDesc[comps.length];
        for (int f = 0; f < comps.length; f++) {
            ctorParams[f] = (ClassDesc) comps[f].getType().describeConstable().orElseThrow();
            cb.aload(fieldLocals[f]);
            cb.iload(slotVar);
            zzuegg.ecs.storage.SoAComponentStorage.emitArrayLoad(cb, comps[f].getType());
        }
        cb.invokespecial(recDesc, "<init>",
            MethodTypeDesc.of(ConstantDescs.CD_void, ctorParams));
    }

    /**
     * Emit SoA set: decompose a record on the stack into per-field array stores.
     * Expects the Record value on top of the stack; consumes it.
     * Uses cached per-field array locals — no invokeinterface per entity.
     */
    private static void emitSoASet(
            java.lang.classfile.CodeBuilder cb, int slotVar, int tempRecordLocal,
            java.lang.reflect.RecordComponent[] comps, int[] fieldLocals,
            @SuppressWarnings("rawtypes") Class recordClass) {
        var recDesc = (ClassDesc) recordClass.describeConstable().orElseThrow();
        // Record value is on the stack. Store in temp.
        cb.checkcast(recDesc);
        cb.astore(tempRecordLocal);
        for (int f = 0; f < comps.length; f++) {
            cb.aload(fieldLocals[f]); // field array
            cb.iload(slotVar);        // slot
            cb.aload(tempRecordLocal); // record value
            cb.invokevirtual(recDesc, comps[f].getName(),
                MethodTypeDesc.of((ClassDesc) comps[f].getType().describeConstable().orElseThrow()));
            zzuegg.ecs.storage.SoAComponentStorage.emitArrayStore(cb, comps[f].getType());
        }
    }
}
