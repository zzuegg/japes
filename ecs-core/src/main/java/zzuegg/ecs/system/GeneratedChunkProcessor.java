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
        if (!desc.changeFilters().isEmpty()) return "system uses @Filter(Added/Changed/Removed)";
        return null;
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        if (skipReason(desc) != null) return null;
        try {
            return generateWithBytecode(desc, serviceArgs);
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

    private static ChunkProcessor generateWithBytecode(SystemDescriptor desc, Object[] serviceArgs) throws Exception {
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
                //   6..6+paramCount-1            : storages
                //   6+paramCount..6+2*paramCount-1: trackers (only used for write slots; unused entries stay null)
                //   6+2*paramCount               : temporary for flushed write-back value
                int countVar = 4;
                int slotVar = 5;
                int firstStorageVar = 6;
                int firstTrackerVar = firstStorageVar + paramCount;
                int tempValueVar = firstTrackerVar + paramCount;

                cb.aload(1); // chunk
                cb.invokevirtual(chunkDesc, "count", MethodTypeDesc.of(ConstantDescs.CD_int));
                cb.istore(countVar);

                // Load storages into local vars for READ/WRITE params only.
                // cids is indexed by ComponentAccess order, so we use
                // paramToAccessIdx[i] to read the right slot.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.READ && kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(1); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(paramToAccessIdx[i]);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "componentStorage",
                        MethodTypeDesc.of(storageDesc, compIdDesc));
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

                // Per-chunk Mut setup: tracker and tick are stable across every
                // entity in this chunk, so push them into each Mut once here
                // instead of passing them as args to reset() per entity.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(0);
                    cb.getfield(genDesc, "muts", mutArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.aload(firstTrackerVar + i);
                    cb.lload(2); // tick
                    cb.invokevirtual(mutDesc, "setContext", mutSetContextDesc);
                }

                // int slot = 0
                cb.iconst_0();
                cb.istore(slotVar);

                var loopStart = cb.newLabel();
                var loopEnd = cb.newLabel();

                cb.labelBinding(loopStart);
                cb.iload(slotVar);
                cb.iload(countVar);
                cb.if_icmpge(loopEnd);

                // Load instance for invokevirtual (non-static only)
                if (!isStatic) {
                    cb.aload(0);
                    cb.getfield(genDesc, "inst", objDesc);
                    cb.checkcast(systemClassDesc);
                }

                // Build the arg list
                for (int i = 0; i < paramCount; i++) {
                    switch (kinds[i]) {
                        case READ -> {
                            // (Type) storageN.get(slot)
                            cb.aload(firstStorageVar + i);
                            cb.iload(slotVar);
                            cb.invokeinterface(storageDesc, "get", storageGetDesc);
                            cb.checkcast(componentTypes[i].describeConstable().orElseThrow());
                        }
                        case WRITE -> {
                            // push muts[i], dup, call resetValue(value, slot);
                            // tracker+tick were already set per-chunk via
                            // setContext, so the per-entity path only pushes
                            // the two variables that actually change per slot.
                            // The duplicated Mut remains on the stack as the
                            // method argument after resetValue returns void.
                            cb.aload(0);
                            cb.getfield(genDesc, "muts", mutArrayDesc);
                            cb.ldc(i);
                            cb.aaload();
                            cb.dup();
                            cb.aload(firstStorageVar + i);
                            cb.iload(slotVar);
                            cb.invokeinterface(storageDesc, "get", storageGetDesc);
                            cb.iload(slotVar);
                            cb.invokevirtual(mutDesc, "resetValue", mutResetValueDesc);
                        }
                        case ENTITY -> {
                            // chunk.entity(slot) — returns Entity directly, no cast needed
                            cb.aload(1);
                            cb.iload(slotVar);
                            cb.invokevirtual(chunkDesc, "entity", chunkEntityDesc);
                        }
                        case SERVICE -> {
                            // (ParamType) services[i]
                            cb.aload(0);
                            cb.getfield(genDesc, "services", objArrayDesc);
                            cb.ldc(i);
                            cb.aaload();
                            cb.checkcast(params[i].getType().describeConstable().orElseThrow());
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
                // only need to do the storage write here.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(0);
                    cb.getfield(genDesc, "muts", mutArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.invokevirtual(mutDesc, "flush", mutFlushDesc);
                    cb.astore(tempValueVar);

                    cb.aload(firstStorageVar + i);
                    cb.iload(slotVar);
                    cb.aload(tempValueVar);
                    cb.invokeinterface(storageDesc, "set", storageSetDesc);
                }

                cb.iinc(slotVar, 1);
                cb.goto_(loopStart);
                cb.labelBinding(loopEnd);
                cb.return_();
            });
        });

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
