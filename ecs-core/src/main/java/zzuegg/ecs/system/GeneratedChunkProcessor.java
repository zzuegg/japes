package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class per system with a tight iteration loop that calls
 * the system method via invokevirtual/invokestatic — fully inlineable by the JIT.
 * Eliminates the ~1.3ns per-entity MethodHandle dispatch overhead.
 */
public final class GeneratedChunkProcessor {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER = new java.util.concurrent.atomic.AtomicLong();

    private GeneratedChunkProcessor() {}

    /**
     * Build a valid JVM class name for a hidden processor in the given package.
     * The hidden class MUST live in the same package as its {@code Lookup}
     * (i.e. the system class) — {@code defineHiddenClass} rejects cross-package
     * names, and package-private system methods are only reachable from a
     * nestmate in the same package.
     *
     * desc.name() is "Class.method" — the dot is a JVM package separator, so we
     * sanitise it (and any other illegal identifier characters) into '_' before
     * embedding the name in ClassDesc.of. A monotonic counter guarantees
     * uniqueness across rebuilds instead of relying on nanoTime() resolution.
     */
    static String generateClassName(String packageName, String descName) {
        var sb = new StringBuilder(descName.length());
        for (int i = 0; i < descName.length(); i++) {
            char c = descName.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && i > 0);
            sb.append(ok ? c : '_');
        }
        String prefix = packageName == null || packageName.isEmpty() ? "" : packageName + ".";
        return prefix + "Proc_" + sb + "_" + COUNTER.incrementAndGet();
    }

    /** Legacy shim for the existing test that asserts the generated name shape. */
    static String generateClassName(String descName) {
        return generateClassName("zzuegg.ecs.generated", descName);
    }

    /**
     * Package-private skip-reason introspection. Returns {@code null} if this
     * processor tier can handle the system; otherwise a human-readable reason
     * the system was rejected. Kept on this class so callers (e.g. future
     * diagnostics, a {@code -Dzzuegg.ecs.logGenerated} flag) can explain why
     * the slower fallback path was picked without digging through code.
     */
    static String skipReason(SystemDescriptor desc) {
        var method = desc.method();
        var params = method.getParameters();
        int componentCount = 0;
        for (var param : params) {
            if (param.isAnnotationPresent(Read.class) || param.isAnnotationPresent(Write.class)) {
                componentCount++;
            }
            // Non-component params (Entity / Commands / Res / ResMut / EventReader
            // / EventWriter / Local / RemovedComponents) compile to a
            // constant-reference field or a chunk.entity(slot) call and don't
            // block the fast path.
        }
        if (componentCount < 1) return "system has no component parameters";
        if (componentCount > 4) return "system has " + componentCount + " component parameters (tier-1 limit is 4)";
        if (params.length > 8) return "system has " + params.length + " total parameters (tier-1 limit is 8)";
        if (!desc.whereFilters().isEmpty()) return "system uses @Where filters";
        // @Filter(Removed) isn't expressible via tracker ticks — it needs a
        // separate removal log. Only Added/Changed are supported in tier-1.
        for (var f : desc.changeFilters()) {
            if (f.filterType() != Added.class && f.filterType() != Changed.class) {
                return "system uses @Filter(" + f.filterType().getSimpleName() + ") which is unsupported";
            }
        }
        return null;
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        return tryGenerate(desc, serviceArgs, true, null);
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs,
                                             boolean useDefaultStorageFactory) {
        return tryGenerate(desc, serviceArgs, useDefaultStorageFactory, null);
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs,
                                             boolean useDefaultStorageFactory,
                                             SystemExecutionPlan plan) {
        if (skipReason(desc) != null) return null;
        // Change filters need the plan reference for lastSeenTick.
        if (!desc.changeFilters().isEmpty() && plan == null) return null;
        try {
            return generateWithBytecode(desc, serviceArgs, useDefaultStorageFactory, plan);
        } catch (Exception e) {
            // Fall back to invokeExact path. The invokeExact fallback cannot
            // handle Mut/Entity/service parameters — if any such param exists,
            // the fallback would attempt a direct cast and crash at runtime
            // with a confusing ClassCastException, so surface the bytecode
            // failure with full context instead.
            for (var p : desc.method().getParameters()) {
                if (!p.isAnnotationPresent(Read.class)) {
                    throw new RuntimeException(
                        "tier-1 bytecode generation failed for " + desc.name()
                            + " and the invokeExact fallback only supports all-@Read systems", e);
                }
            }
            return generateWithInvokeExact(desc);
        }
    }

    /**
     * Generate a hidden class with invokevirtual/invokestatic to the system method.
     * This is the fastest path — the JIT can fully inline the system call.
     *
     * Supports both read (@Read Component) and write (@Write Mut&lt;Component&gt;)
     * parameters. For write params, the generated bytecode:
     *   - loads the target component's {@code ChangeTracker} once per chunk
     *   - keeps a reused {@code Mut} instance in an instance field
     *   - calls {@code Mut.reset(value, slot, tracker, tick)} per entity
     *     before passing the Mut to the user method
     *   - calls {@code Mut.flush()} after the system body and writes the
     *     returned value back to component storage; the flush itself does
     *     the value-tracked suppression and {@code ChangeTracker.markChanged}
     *     bookkeeping, so no extra logic is emitted here.
     */
    // Per-parameter classification for the generator.
    private enum ParamKind { READ, WRITE, ENTITY, SERVICE }

    private static ChunkProcessor generateWithBytecode(SystemDescriptor desc, Object[] serviceArgs,
                                                        boolean useDefaultStorageFactory,
                                                        SystemExecutionPlan plan) throws Exception {
        var method = desc.method();
        var accesses = desc.componentAccesses();
        int paramCount = method.getParameterCount();
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        var params = method.getParameters();

        // Classify each parameter. Component params (Read/Write) consume an
        // entry from desc.componentAccesses(); Entity and service params don't.
        ParamKind[] kinds = new ParamKind[paramCount];
        Class<?>[] componentTypes = new Class<?>[paramCount]; // non-null only for READ/WRITE
        int writeCount = 0;
        int componentIdx = 0;
        // Mapping from param slot to ComponentAccess index for READ/WRITE params.
        int[] paramToAccessIdx = new int[paramCount];
        for (int i = 0; i < paramCount; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                kinds[i] = ParamKind.READ;
                componentTypes[i] = accesses.get(componentIdx).type();
                paramToAccessIdx[i] = componentIdx++;
            } else if (params[i].isAnnotationPresent(Write.class)) {
                kinds[i] = ParamKind.WRITE;
                componentTypes[i] = accesses.get(componentIdx).type();
                paramToAccessIdx[i] = componentIdx++;
                writeCount++;
            } else if (params[i].getType() == zzuegg.ecs.entity.Entity.class) {
                kinds[i] = ParamKind.ENTITY;
                paramToAccessIdx[i] = -1;
            } else {
                kinds[i] = ParamKind.SERVICE;
                paramToAccessIdx[i] = -1;
            }
        }

        var systemLookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());

        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();
        var storageDesc = ClassDesc.of("zzuegg.ecs.storage.ComponentStorage");
        var chunkDesc = ClassDesc.of("zzuegg.ecs.storage.Chunk");
        var compIdDesc = ClassDesc.of("zzuegg.ecs.component.ComponentId");
        var processorDesc = ClassDesc.of("zzuegg.ecs.system.ChunkProcessor");
        var mutDesc = ClassDesc.of("zzuegg.ecs.component.Mut");
        var trackerDesc = ClassDesc.of("zzuegg.ecs.change.ChangeTracker");
        var recordDesc = ClassDesc.of("java.lang.Record");
        // The generated class must share a package with the system class so
        // the hidden class can be a nestmate and invokevirtual a
        // package-private system method.
        var genName = generateClassName(method.getDeclaringClass().getPackageName(), desc.name());
        var genDesc = ClassDesc.of(genName);
        var objDesc = ConstantDescs.CD_Object;

        // Build the JVM-level method type descriptor. For read params the
        // declared type is the component itself; for write params it's Mut,
        // erased to its raw form at the JVM level.
        var paramDescs = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            paramDescs[i] = method.getParameterTypes()[i].describeConstable().orElseThrow();
        }
        var systemMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs);

        var compIdArrayDesc = compIdDesc.arrayType();
        var mutArrayDesc = mutDesc.arrayType();
        var trackerArrayDesc = trackerDesc.arrayType();
        var objArrayDesc = objDesc.arrayType();
        var recordArrayDesc = recordDesc.arrayType();
        var defaultStorageDesc = ClassDesc.of("zzuegg.ecs.storage.DefaultComponentStorage");
        var rawArrayDesc = MethodTypeDesc.of(recordArrayDesc);
        var entityDesc = ClassDesc.of("zzuegg.ecs.entity.Entity");
        var chunkEntityDesc = MethodTypeDesc.of(entityDesc, ConstantDescs.CD_int);

        // Descriptors for Mut.setContext / Mut.resetValue / Mut.flush and
        // ComponentStorage.set / get.
        var mutSetContextDesc = MethodTypeDesc.of(ConstantDescs.CD_void, trackerDesc, ConstantDescs.CD_long);
        var mutResetValueDesc = MethodTypeDesc.of(ConstantDescs.CD_void, recordDesc, ConstantDescs.CD_int);
        var mutFlushDesc = MethodTypeDesc.of(recordDesc);
        var storageGetDesc = MethodTypeDesc.of(recordDesc, ConstantDescs.CD_int);
        var storageSetDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, recordDesc);
        var chunkChangeTrackerDesc = MethodTypeDesc.of(trackerDesc, compIdDesc);

        final boolean hasWrites = writeCount > 0;
        boolean hasServiceLocal = false;
        for (var kind : kinds) if (kind == ParamKind.SERVICE) { hasServiceLocal = true; break; }
        final boolean hasServices = hasServiceLocal;

        // Change filter setup — only populated when the system has filters.
        // The resolved filters from the plan give us ComponentIds already.
        final boolean hasFilters = plan != null && plan.hasChangeFilters();
        final SystemExecutionPlan.ResolvedChangeFilter[] resolvedFilters =
            hasFilters ? plan.resolvedChangeFilters() : new SystemExecutionPlan.ResolvedChangeFilter[0];
        // Multi-target: any @Filter group with > 1 target component.
        boolean isMultiTarget = false;
        int totalFilterTargets = 0;
        for (var f : resolvedFilters) {
            totalFilterTargets += f.targetIds().length;
            if (f.targetIds().length > 1) isMultiTarget = true;
        }
        final boolean multiTargetFilter = isMultiTarget;
        final int filterTargetCount = totalFilterTargets;
        var planClassDesc = ClassDesc.of("zzuegg.ecs.system.SystemExecutionPlan");
        var planLastSeenDesc = MethodTypeDesc.of(ConstantDescs.CD_long);
        var trackerDirtySlotsDesc = MethodTypeDesc.of(ClassDesc.ofDescriptor("[I"));
        var trackerDirtyCountDesc = MethodTypeDesc.of(ConstantDescs.CD_int);
        var trackerIsChangedSinceDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_int, ConstantDescs.CD_long);
        var trackerIsAddedSinceDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_int, ConstantDescs.CD_long);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(processorDesc));

            // Field: system instance (if non-static)
            if (!isStatic) {
                clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }

            // Field: ComponentId[] for each param
            clb.withField("cids", compIdArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // Fields for write support — only emitted if the system actually
            // has @Write params, so pure-read systems get identical bytecode
            // to before the write-path extension.
            if (hasWrites) {
                clb.withField("muts", mutArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }
            // Field for service params — resolved once at plan-build time and
            // passed as constant references into the loop. Populated from the
            // resolvedServiceArgs array we receive from the caller.
            if (hasServices) {
                clb.withField("services", objArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }
            // Fields for change-filter support: a reference to the owning
            // SystemExecutionPlan (for lastSeenTick) and an array of the
            // filter-target ComponentIds (resolved per chunk via
            // chunk.changeTracker() into trackers[]). Both are stable for
            // the processor's lifetime.
            if (hasFilters) {
                clb.withField("plan", planClassDesc, fb -> fb.withFlags(ACC_PUBLIC));
                clb.withField("filterCids", compIdArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
                if (multiTargetFilter) {
                    // Reusable per-chunk buffers — allocated once, cleared per call.
                    clb.withField("seenBitmap", ClassDesc.ofDescriptor("[J"), fb -> fb.withFlags(ACC_PUBLIC));
                    clb.withField("resultBuf", ClassDesc.ofDescriptor("[I"), fb -> fb.withFlags(ACC_PUBLIC));
                    clb.withField("filterTrackers", trackerArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
                }
            }

            // Constructor
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                cb.return_();
            });

            // process(Chunk chunk, long currentTick)
            clb.withMethodBody("process",
                MethodTypeDesc.of(ConstantDescs.CD_void, chunkDesc, ConstantDescs.CD_long),
                ACC_PUBLIC, cb -> {

                // Local var layout:
                //   0  this
                //   1  chunk
                //   2-3 tick (long)
                //   4  count
                //   5  slot
                //   6..6+paramCount-1                : storages (Record[] or ComponentStorage)
                //   6+paramCount..6+2*paramCount-1   : trackers for WRITE params (unused entries stay null)
                //   6+2*paramCount                   : tempValue for flushed write-back
                //   6+2*paramCount+1                 : dirtyIdx (filter loop index, only if hasFilters)
                //   6+2*paramCount+2                 : dirtyList (int[], only if hasFilters)
                //   6+2*paramCount+3                 : dirtyCount (int, only if hasFilters)
                //   6+2*paramCount+4..+3+filterCount : filter trackers (ChangeTracker, only if hasFilters)
                //   +(4+filterCount)                 : lastSeen (long, 2 slots, only if hasFilters)
                //   next                             : hoisted `this.inst` (instance ref, only if !isStatic)
                //   next+1..                         : hoisted service[i] per SERVICE param
                //   next                             : hoisted mut[i] per WRITE param
                //   next                             : hoisted entities[] array (only if any ENTITY param)
                int countVar = 4;
                int slotVar = 5;
                int firstStorageVar = 6;
                int firstTrackerVar = firstStorageVar + paramCount;
                int tempValueVar = firstTrackerVar + paramCount;
                int dirtyIdxVar = tempValueVar + 1;
                int dirtyListVar = dirtyIdxVar + 1;
                int dirtyCountVar = dirtyListVar + 1;
                int firstFilterTrackerVar = dirtyCountVar + 1;
                // For single-target: one tracker local per filter group (existing).
                // For multi-target: one tracker local per individual target across all groups.
                int filterTrackerLocals = multiTargetFilter ? filterTargetCount : resolvedFilters.length;
                int lastSeenVar = firstFilterTrackerVar + filterTrackerLocals;
                // lastSeen is a long → consumes 2 slots. Next free slot is
                // lastSeenVar + 2 if hasFilters, else lastSeenVar (== lastSeenVar
                // was never written). Keep it simple: reserve 2 slots always.
                int firstHoistVar = lastSeenVar + (hasFilters ? 2 : 0);
                int instLocal = !isStatic ? firstHoistVar++ : -1;
                // One local per parameter slot that we want to hoist. We only
                // hoist the SERVICE and WRITE-mut refs — storages and trackers
                // are already per-chunk locals, and READ params re-load per
                // slot intentionally (they're the values the user reads).
                int[] serviceLocal = new int[paramCount];
                int[] mutLocal = new int[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    serviceLocal[i] = -1;
                    mutLocal[i] = -1;
                    if (kinds[i] == ParamKind.SERVICE) {
                        serviceLocal[i] = firstHoistVar++;
                    } else if (kinds[i] == ParamKind.WRITE) {
                        mutLocal[i] = firstHoistVar++;
                    }
                }
                // If the system takes an Entity parameter, hoist the
                // chunk's raw entityArray() reference to a local so
                // per-slot access becomes an aaload instead of an
                // invokevirtual through Chunk.entity(int).
                boolean anyEntity = false;
                for (var k : kinds) if (k == ParamKind.ENTITY) { anyEntity = true; break; }
                int entityArrayLocal = anyEntity ? firstHoistVar++ : -1;

                cb.aload(1); // chunk
                cb.invokevirtual(chunkDesc, "count", MethodTypeDesc.of(ConstantDescs.CD_int));
                cb.istore(countVar);

                // Load storages into local vars for READ/WRITE params. When
                // the world uses the default storage factory, go a step
                // further and grab the raw Record[] backing array so the
                // per-entity access is just aaload/aastore — no
                // invokeinterface on the hot path.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.READ && kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(1); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(paramToAccessIdx[i]);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "componentStorage",
                        MethodTypeDesc.of(storageDesc, compIdDesc));
                    if (useDefaultStorageFactory) {
                        cb.checkcast(defaultStorageDesc);
                        cb.invokevirtual(defaultStorageDesc, "rawArray", rawArrayDesc);
                    }
                    cb.astore(firstStorageVar + i);
                }

                // Load trackers for WRITE params: trackerN = chunk.changeTracker(cids[accessIdx])
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(1); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(paramToAccessIdx[i]);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                    cb.astore(firstTrackerVar + i);
                }

                // Per-chunk: tracker + tick are stable. With fresh-Mut-per-entity
                // the tracker is already in firstTrackerVar[i] and tick is in
                // the method arg (slot 2). No per-chunk Mut setup needed.

                // Filter setup: load a ChangeTracker for each filter target,
                // grab the primary filter's dirty list + count, and cache
                // plan.lastSeenTick() in a local so the per-slot check is a
                // plain long comparison.
                if (hasFilters) {
                    // lastSeen = plan.lastSeenTick()  (used by both paths)
                    cb.aload(0);
                    cb.getfield(genDesc, "plan", planClassDesc);
                    cb.invokevirtual(planClassDesc, "lastSeenTick", planLastSeenDesc);
                    cb.lstore(lastSeenVar);

                    if (multiTargetFilter) {
                        // Multi-target path: build a ChangeTracker[] per chunk,
                        // clear the reusable bitmap, call the helper which fills
                        // the reusable resultBuf. Zero per-chunk allocation.
                        var helperDesc = ClassDesc.of("zzuegg.ecs.system.MultiFilterHelper");
                        var longArrDesc = ClassDesc.ofDescriptor("[J");
                        var intArrDesc = ClassDesc.ofDescriptor("[I");
                        var unionDesc = MethodTypeDesc.of(
                            ConstantDescs.CD_int,           // return: match count
                            trackerArrayDesc,               // ChangeTracker[]
                            ConstantDescs.CD_int,           // count
                            ConstantDescs.CD_long,          // lastSeen
                            ConstantDescs.CD_boolean,       // isAdded
                            longArrDesc,                    // seenBitmap
                            intArrDesc);                    // resultBuf
                        // Fill the reusable filterTrackers[] from chunk + filterCids
                        cb.aload(0);
                        cb.getfield(genDesc, "filterTrackers", trackerArrayDesc);
                        for (int ti = 0; ti < filterTargetCount; ti++) {
                            cb.dup();
                            cb.ldc(ti);
                            cb.aload(1); // chunk
                            cb.aload(0);
                            cb.getfield(genDesc, "filterCids", compIdArrayDesc);
                            cb.ldc(ti);
                            cb.aaload();
                            cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                            cb.aastore();
                        }
                        // stack: [ChangeTracker[]]
                        cb.iload(countVar);           // count
                        cb.lload(lastSeenVar);        // lastSeen
                        boolean isAdded = resolvedFilters[0].kind() == SystemExecutionPlan.FilterKind.ADDED;
                        cb.ldc(isAdded ? 1 : 0);
                        // Clear seenBitmap: Arrays.fill(seenBitmap, 0L)
                        cb.aload(0);
                        cb.getfield(genDesc, "seenBitmap", longArrDesc);
                        cb.dup();
                        cb.lconst_0();
                        cb.invokestatic(ClassDesc.of("java.util.Arrays"), "fill",
                            MethodTypeDesc.of(ConstantDescs.CD_void, longArrDesc, ConstantDescs.CD_long));
                        // stack: [ChangeTracker[], count, lastSeen, isAdded, seenBitmap]
                        cb.aload(0);
                        cb.getfield(genDesc, "resultBuf", intArrDesc);
                        // stack: [..., seenBitmap, resultBuf]
                        cb.invokestatic(helperDesc, "unionDirtySlots", unionDesc);
                        // return value is match count → store as dirtyCount
                        cb.istore(dirtyCountVar);
                        // dirtyList = this.resultBuf (the helper wrote into it)
                        cb.aload(0);
                        cb.getfield(genDesc, "resultBuf", intArrDesc);
                        cb.astore(dirtyListVar);
                        cb.iconst_0();
                        cb.istore(dirtyIdxVar);
                    } else {
                        // Single-target path: load one tracker per filter, use
                        // the primary's dirty list directly (existing code).
                        for (int fi = 0; fi < resolvedFilters.length; fi++) {
                            cb.aload(1); // chunk
                            cb.aload(0);
                            cb.getfield(genDesc, "filterCids", compIdArrayDesc);
                            cb.ldc(fi);
                            cb.aaload();
                            cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                            cb.astore(firstFilterTrackerVar + fi);
                        }
                        cb.aload(firstFilterTrackerVar);
                        cb.invokevirtual(trackerDesc, "dirtySlots", trackerDirtySlotsDesc);
                        cb.astore(dirtyListVar);
                        cb.aload(firstFilterTrackerVar);
                        cb.invokevirtual(trackerDesc, "dirtyCount", trackerDirtyCountDesc);
                        cb.istore(dirtyCountVar);
                        cb.iconst_0();
                        cb.istore(dirtyIdxVar);
                    }
                } else {
                    // int slot = 0
                    cb.iconst_0();
                    cb.istore(slotVar);
                }

                // Hoist per-instance field loads out of the loop. Without
                // this, every iteration does:
                //   aload0; getfield inst; checkcast systemClass
                //   aload0; getfield services; ldc i; aaload; checkcast P
                //   aload0; getfield muts; ldc i; aaload
                // The JIT's loop-invariant code motion can't hoist these
                // because the fields are `public` non-final and the
                // invokevirtual to the user body is treated as a potential
                // side effect. Hoisting them here in bytecode makes the hot
                // loop load from locals only.
                if (instLocal >= 0) {
                    cb.aload(0);
                    cb.getfield(genDesc, "inst", objDesc);
                    cb.checkcast(systemClassDesc);
                    cb.astore(instLocal);
                }
                for (int i = 0; i < paramCount; i++) {
                    if (serviceLocal[i] < 0) continue;
                    cb.aload(0);
                    cb.getfield(genDesc, "services", objArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.checkcast(params[i].getType().describeConstable().orElseThrow());
                    cb.astore(serviceLocal[i]);
                }
                for (int i = 0; i < paramCount; i++) {
                    if (mutLocal[i] < 0) continue;
                    cb.aload(0);
                    cb.getfield(genDesc, "muts", mutArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.astore(mutLocal[i]);
                }
                // Hoist chunk.entityArray() so per-slot Entity access
                // is a plain aaload on a local Entity[] rather than an
                // invokevirtual through Chunk.entity(int).
                if (entityArrayLocal >= 0) {
                    cb.aload(1); // chunk
                    cb.invokevirtual(chunkDesc, "entityArray",
                        MethodTypeDesc.of(entityDesc.arrayType()));
                    cb.astore(entityArrayLocal);
                }

                var loopStart = cb.newLabel();
                var loopEnd = cb.newLabel();
                var loopContinue = cb.newLabel();

                cb.labelBinding(loopStart);
                if (hasFilters) {
                    // if (dirtyIdx >= dirtyCount) goto end
                    cb.iload(dirtyIdxVar);
                    cb.iload(dirtyCountVar);
                    cb.if_icmpge(loopEnd);
                    // slot = dirty[dirtyIdx++]
                    cb.aload(dirtyListVar);
                    cb.iload(dirtyIdxVar);
                    cb.iaload();
                    cb.istore(slotVar);
                    cb.iinc(dirtyIdxVar, 1);
                    if (multiTargetFilter) {
                        // Multi-target: the helper already filtered + deduped.
                        // No per-slot check needed — every slot in the array
                        // is guaranteed to match. The helper also excluded
                        // slots >= count, so no bounds check required.
                    } else {
                        // Single-target: bounds check + per-filter AND check.
                        cb.iload(slotVar);
                        cb.iload(countVar);
                        cb.if_icmpge(loopContinue);
                        for (int fi = 0; fi < resolvedFilters.length; fi++) {
                            cb.aload(firstFilterTrackerVar + fi);
                            cb.iload(slotVar);
                            cb.lload(lastSeenVar);
                            var kind = resolvedFilters[fi].kind();
                            if (kind == SystemExecutionPlan.FilterKind.ADDED) {
                                cb.invokevirtual(trackerDesc, "isAddedSince", trackerIsAddedSinceDesc);
                            } else {
                                cb.invokevirtual(trackerDesc, "isChangedSince", trackerIsChangedSinceDesc);
                            }
                            cb.ifeq(loopContinue);
                        }
                    }
                } else {
                    cb.iload(slotVar);
                    cb.iload(countVar);
                    cb.if_icmpge(loopEnd);
                }

                // Load instance for invokevirtual (non-static only) —
                // hoisted to `instLocal` in the preamble.
                if (!isStatic) {
                    cb.aload(instLocal);
                }

                // Build the arg list
                for (int i = 0; i < paramCount; i++) {
                    switch (kinds[i]) {
                        case READ -> {
                            // Fast path: aaload from raw Record[] then checkcast.
                            // Slow path: invokeinterface ComponentStorage.get.
                            if (useDefaultStorageFactory) {
                                cb.aload(firstStorageVar + i); // Record[]
                                cb.iload(slotVar);
                                cb.aaload();
                            } else {
                                cb.aload(firstStorageVar + i);
                                cb.iload(slotVar);
                                cb.invokeinterface(storageDesc, "get", storageGetDesc);
                            }
                            cb.checkcast(componentTypes[i].describeConstable().orElseThrow());
                        }
                        case WRITE -> {
                            // Fresh Mut per entity — enables escape analysis
                            // to scalar-replace both the Mut and the record
                            // stored in it via set(). The constructor writes
                            // all fields; EA turns them into local-variable
                            // assignments that the JIT can register-allocate.
                            var mutInitDesc = MethodTypeDesc.of(
                                ConstantDescs.CD_void,
                                recordDesc,              // value
                                ConstantDescs.CD_int,    // slot
                                trackerDesc,             // tracker
                                ConstantDescs.CD_long,   // tick
                                ConstantDescs.CD_boolean  // valueTracked
                            );
                            cb.new_(mutDesc);
                            cb.dup();
                            // value from storage
                            if (useDefaultStorageFactory) {
                                cb.aload(firstStorageVar + i);
                                cb.iload(slotVar);
                                cb.aaload();
                            } else {
                                cb.aload(firstStorageVar + i);
                                cb.iload(slotVar);
                                cb.invokeinterface(storageDesc, "get", storageGetDesc);
                            }
                            cb.iload(slotVar);               // slot
                            cb.aload(firstTrackerVar + i);   // tracker
                            cb.lload(2);                     // tick
                            cb.iconst_0();                   // valueTracked = false
                            cb.invokespecial(mutDesc, "<init>", mutInitDesc);
                            // Store in the mutLocal so flush can find it
                            cb.astore(mutLocal[i]);
                            cb.aload(mutLocal[i]);
                        }
                        case ENTITY -> {
                            // entities[slot] via the hoisted raw Entity[]
                            // local — one aaload, no virtual dispatch.
                            cb.aload(entityArrayLocal);
                            cb.iload(slotVar);
                            cb.aaload();
                        }
                        case SERVICE -> {
                            // Hoisted in the preamble — just reload the
                            // cast reference from the local.
                            cb.aload(serviceLocal[i]);
                        }
                    }
                }

                // Call the system method
                if (isStatic) {
                    cb.invokestatic(systemClassDesc, method.getName(), systemMethodDesc);
                } else {
                    cb.invokevirtual(systemClassDesc, method.getName(), systemMethodDesc);
                }

                // Post-call: for each write param, flush the Mut and write the
                // result back to storage. Mut.flush() itself calls
                // ChangeTracker.markChanged (honouring @ValueTracked), so we
                // only need to do the storage write here. Uses the hoisted
                // Mut local from the preamble.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(mutLocal[i]);
                    cb.invokevirtual(mutDesc, "flush", mutFlushDesc);
                    cb.astore(tempValueVar);

                    if (useDefaultStorageFactory) {
                        // raw[slot] = tempValue — direct array store, no
                        // interface dispatch. The array's runtime component
                        // type is the concrete component class, so aastore's
                        // covariance check is satisfied cheaply.
                        cb.aload(firstStorageVar + i);
                        cb.iload(slotVar);
                        cb.aload(tempValueVar);
                        cb.aastore();
                    } else {
                        cb.aload(firstStorageVar + i);
                        cb.iload(slotVar);
                        cb.aload(tempValueVar);
                        cb.invokeinterface(storageDesc, "set", storageSetDesc);
                    }
                }

                cb.labelBinding(loopContinue);
                if (!hasFilters) {
                    cb.iinc(slotVar, 1);
                }
                cb.goto_(loopStart);
                cb.labelBinding(loopEnd);
                cb.return_();
            });
        });

        // Optional dump of generated bytecode for inspection. Set
        // -Dzzuegg.ecs.dumpGenerated=/path/to/dir to write every
        // hidden class to disk as <descName>.class. Used to audit
        // the tier-1 hot path with javap -c.
        var dumpDir = java.lang.System.getProperty("zzuegg.ecs.dumpGenerated");
        if (dumpDir != null) {
            try {
                var path = java.nio.file.Path.of(dumpDir, genName.replace('.', '_') + ".class");
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.write(path, bytes);
            } catch (Exception ignored) { /* best-effort */ }
        }

        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        if (!isStatic) {
            clazz.getField("inst").set(instance, desc.instance());
        }

        // cids is indexed by ComponentAccess order, matching
        // paramToAccessIdx[i] for READ/WRITE param slots.
        var compIds = new ComponentId[accesses.size()];
        for (int i = 0; i < accesses.size(); i++) {
            compIds[i] = accesses.get(i).componentId();
        }
        clazz.getField("cids").set(instance, compIds);

        if (hasWrites) {
            var muts = new Mut[paramCount];
            for (int i = 0; i < paramCount; i++) {
                if (kinds[i] == ParamKind.WRITE) {
                    // @ValueTracked is a type-level annotation on the record
                    // itself, so we can read it without needing the registry.
                    boolean valueTracked = componentTypes[i].isAnnotationPresent(
                        zzuegg.ecs.component.ValueTracked.class);
                    muts[i] = new Mut<>(null, 0, null, 0, valueTracked);
                }
            }
            clazz.getField("muts").set(instance, muts);
        }

        if (hasServices) {
            // services is just the same resolvedServiceArgs the plan uses —
            // indexed by parameter slot, with nulls for non-service slots.
            clazz.getField("services").set(instance, serviceArgs);
        }

        if (hasFilters) {
            clazz.getField("plan").set(instance, plan);
            // Flatten all target ComponentIds across all filter groups
            // into a single array. Multi-target @Filter groups contribute
            // multiple entries; single-target groups contribute one.
            var allIds = new java.util.ArrayList<ComponentId>();
            for (var f : resolvedFilters) {
                for (var tid : f.targetIds()) allIds.add(tid);
            }
            clazz.getField("filterCids").set(instance, allIds.toArray(ComponentId[]::new));
            if (multiTargetFilter) {
                int maxChunkSize = 1024;
                clazz.getField("seenBitmap").set(instance, new long[(maxChunkSize + 63) >>> 6]);
                clazz.getField("resultBuf").set(instance, new int[maxChunkSize]);
                clazz.getField("filterTrackers").set(instance,
                    new zzuegg.ecs.change.ChangeTracker[filterTargetCount]);
            }
        }

        return (ChunkProcessor) instance;
    }

    /**
     * Fallback: uses invokeExact with arity specialization.
     */
    private static ChunkProcessor generateWithInvokeExact(SystemDescriptor desc) {
        try {
            var method = desc.method();
            var accesses = desc.componentAccesses();
            int paramCount = method.getParameterCount();

            var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            var mh = lookup.unreflect(method);
            if (desc.instance() != null) {
                mh = mh.bindTo(desc.instance());
            }

            var objectTypes = new Class<?>[paramCount];
            java.util.Arrays.fill(objectTypes, Object.class);
            var genericMh = mh.asType(MethodType.methodType(void.class, objectTypes));

            var compIds = new ComponentId[paramCount];
            for (int i = 0; i < paramCount; i++) {
                compIds[i] = accesses.get(i).componentId();
            }

            final var finalMh = genericMh;
            final var finalCompIds = compIds;

            return switch (paramCount) {
                case 1 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 2 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 3 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    var s2 = chunk.componentStorage(finalCompIds[2]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 4 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    var s2 = chunk.componentStorage(finalCompIds[2]);
                    var s3 = chunk.componentStorage(finalCompIds[3]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot), (Object) s3.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
