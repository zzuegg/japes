package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/**
 * Generates a hidden class via ClassFile API that contains an invokevirtual
 * to the system method, eliminating MethodHandle dispatch.
 */
public final class BytecodeChunkProcessor {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private BytecodeChunkProcessor() {}

    /**
     * Try to generate a direct-call processor. Returns null if generation fails
     * (e.g., method is not accessible for direct invocation).
     */
    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        try {
            return doGenerate(desc, serviceArgs);
        } catch (Exception e) {
            return null; // Fall back to reflective path
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor doGenerate(SystemDescriptor desc, Object[] serviceArgs) throws Exception {
        var method = desc.method();
        var params = method.getParameters();
        int paramCount = params.length;

        // Build param metadata
        boolean[] isRead = new boolean[paramCount];
        boolean[] isWrite = new boolean[paramCount];
        boolean[] isValueTracked = new boolean[paramCount];
        ComponentId[] compIds = new ComponentId[paramCount];
        int compIdx = 0;

        for (int i = 0; i < paramCount; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                isRead[i] = true;
                compIds[i] = desc.componentAccesses().get(compIdx++).componentId();
            } else if (params[i].isAnnotationPresent(Write.class)) {
                isWrite[i] = true;
                compIds[i] = desc.componentAccesses().get(compIdx++).componentId();
            }
        }

        // Generate a Runnable-like wrapper class with defineHiddenClass.
        // The hidden class captures the system instance and service args,
        // and calls the system method via an invokedynamic or invokevirtual
        // that the JIT can devirtualize.

        // For now, use MethodHandles.Lookup.defineHiddenClass with a
        // generated class that has a single method calling through a
        // pre-bound MethodHandle stored as a static final field.

        // Actually, the simplest high-impact approach:
        // Use MethodHandles.Lookup to create a direct MethodHandle, then
        // use MethodHandle.invokeExact with proper type signatures.
        // The key: invokeExact with exact types avoids the spreader overhead.

        var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
        var mh = lookup.unreflect(method);
        if (desc.instance() != null) {
            mh = mh.bindTo(desc.instance());
        }

        // Convert all params to Object for a uniform call signature
        var objectParamTypes = new Class<?>[paramCount];
        java.util.Arrays.fill(objectParamTypes, Object.class);
        var genericMh = mh.asType(MethodType.methodType(void.class, objectParamTypes));

        // Now genericMh.invokeExact(Object, Object, Object...) avoids
        // the spreader's array copy. But we need to call with exact arity.

        // Generate arity-specific invoker
        return switch (paramCount) {
            case 1 -> createProcessor1(genericMh, isRead, isWrite, isValueTracked, compIds, serviceArgs);
            case 2 -> createProcessor2(genericMh, isRead, isWrite, isValueTracked, compIds, serviceArgs);
            case 3 -> createProcessor3(genericMh, isRead, isWrite, isValueTracked, compIds, serviceArgs);
            case 4 -> createProcessor4(genericMh, isRead, isWrite, isValueTracked, compIds, serviceArgs);
            default -> null; // Fall back to spreader for 5+ params
        };
    }

    private static ChunkProcessor createProcessor1(
            java.lang.invoke.MethodHandle mh,
            boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked,
            ComponentId[] compIds, Object[] serviceArgs) {

        return (chunk, tick) -> {
            var s0 = (isRead[0] || isWrite[0]) ? chunk.componentStorage(compIds[0]) : null;
            var t0 = isWrite[0] ? chunk.changeTracker(compIds[0]) : null;
            Mut m0 = null;
            int count = chunk.count();
            try {
                for (int slot = 0; slot < count; slot++) {
                    Object a0;
                    if (isRead[0]) {
                        a0 = s0.get(slot);
                    } else if (isWrite[0]) {
                        if (m0 == null) { m0 = new Mut(s0.get(slot), slot, t0, tick, isValueTracked[0]); }
                        else { m0.reset(s0.get(slot), slot, t0, tick); }
                        a0 = m0;
                    } else {
                        a0 = serviceArgs[0];
                    }
                    mh.invokeExact(a0);
                    if (isWrite[0]) { ((ComponentStorage) s0).set(m0.slot(), m0.flush()); }
                }
            } catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor2(
            java.lang.invoke.MethodHandle mh,
            boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked,
            ComponentId[] compIds, Object[] serviceArgs) {

        return (chunk, tick) -> {
            var s0 = (isRead[0] || isWrite[0]) ? chunk.componentStorage(compIds[0]) : null;
            var s1 = (isRead[1] || isWrite[1]) ? chunk.componentStorage(compIds[1]) : null;
            var t0 = isWrite[0] ? chunk.changeTracker(compIds[0]) : null;
            var t1 = isWrite[1] ? chunk.changeTracker(compIds[1]) : null;
            Mut m0 = null, m1 = null;
            int count = chunk.count();
            try {
                for (int slot = 0; slot < count; slot++) {
                    Object a0 = resolveArg(0, slot, tick, s0, t0, isRead, isWrite, isValueTracked, serviceArgs, m0);
                    if (isWrite[0]) m0 = (Mut) a0;
                    Object a1 = resolveArg(1, slot, tick, s1, t1, isRead, isWrite, isValueTracked, serviceArgs, m1);
                    if (isWrite[1]) m1 = (Mut) a1;

                    mh.invokeExact(a0, a1);

                    if (isWrite[0]) { ((ComponentStorage) s0).set(m0.slot(), m0.flush()); }
                    if (isWrite[1]) { ((ComponentStorage) s1).set(m1.slot(), m1.flush()); }
                }
            } catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor3(
            java.lang.invoke.MethodHandle mh,
            boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked,
            ComponentId[] compIds, Object[] serviceArgs) {

        return (chunk, tick) -> {
            ComponentStorage[] stores = new ComponentStorage[3];
            ChangeTracker[] trackers = new ChangeTracker[3];
            Mut[] muts = new Mut[3];
            for (int i = 0; i < 3; i++) {
                if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]);
                if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]);
            }
            int count = chunk.count();
            try {
                for (int slot = 0; slot < count; slot++) {
                    Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]);
                    if (isWrite[0]) muts[0] = (Mut) a0;
                    Object a1 = resolveArg(1, slot, tick, stores[1], trackers[1], isRead, isWrite, isValueTracked, serviceArgs, muts[1]);
                    if (isWrite[1]) muts[1] = (Mut) a1;
                    Object a2 = resolveArg(2, slot, tick, stores[2], trackers[2], isRead, isWrite, isValueTracked, serviceArgs, muts[2]);
                    if (isWrite[2]) muts[2] = (Mut) a2;

                    mh.invokeExact(a0, a1, a2);

                    for (int i = 0; i < 3; i++) {
                        if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush());
                    }
                }
            } catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor4(
            java.lang.invoke.MethodHandle mh,
            boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked,
            ComponentId[] compIds, Object[] serviceArgs) {

        return (chunk, tick) -> {
            ComponentStorage[] stores = new ComponentStorage[4];
            ChangeTracker[] trackers = new ChangeTracker[4];
            Mut[] muts = new Mut[4];
            for (int i = 0; i < 4; i++) {
                if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]);
                if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]);
            }
            int count = chunk.count();
            try {
                for (int slot = 0; slot < count; slot++) {
                    Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]);
                    if (isWrite[0]) muts[0] = (Mut) a0;
                    Object a1 = resolveArg(1, slot, tick, stores[1], trackers[1], isRead, isWrite, isValueTracked, serviceArgs, muts[1]);
                    if (isWrite[1]) muts[1] = (Mut) a1;
                    Object a2 = resolveArg(2, slot, tick, stores[2], trackers[2], isRead, isWrite, isValueTracked, serviceArgs, muts[2]);
                    if (isWrite[2]) muts[2] = (Mut) a2;
                    Object a3 = resolveArg(3, slot, tick, stores[3], trackers[3], isRead, isWrite, isValueTracked, serviceArgs, muts[3]);
                    if (isWrite[3]) muts[3] = (Mut) a3;

                    mh.invokeExact(a0, a1, a2, a3);

                    for (int i = 0; i < 4; i++) {
                        if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush());
                    }
                }
            } catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveArg(int i, int slot, long tick,
                                      ComponentStorage store, ChangeTracker tracker,
                                      boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked,
                                      Object[] serviceArgs, Mut existing) {
        if (isRead[i]) {
            return store.get(slot);
        } else if (isWrite[i]) {
            if (existing == null) {
                return new Mut(store.get(slot), slot, tracker, tick, isValueTracked[i]);
            } else {
                existing.reset(store.get(slot), slot, tracker, tick);
                return existing;
            }
        } else {
            return serviceArgs[i];
        }
    }
}
