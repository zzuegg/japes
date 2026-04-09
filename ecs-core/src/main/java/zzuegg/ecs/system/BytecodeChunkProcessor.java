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

        Class<?>[] compTypes = new Class<?>[paramCount];
        for (int i = 0; i < paramCount; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                isRead[i] = true;
                var access = desc.componentAccesses().get(compIdx++);
                compIds[i] = access.componentId();
                compTypes[i] = access.type();
            } else if (params[i].isAnnotationPresent(Write.class)) {
                isWrite[i] = true;
                var access = desc.componentAccesses().get(compIdx++);
                compIds[i] = access.componentId();
                compTypes[i] = access.type();
            }
        }

        var whereFilters = desc.whereFilters();

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
            case 1 -> createProcessor1(genericMh, isRead, isWrite, isValueTracked, compIds, compTypes, serviceArgs, whereFilters);
            case 2 -> createProcessor2(genericMh, isRead, isWrite, isValueTracked, compIds, compTypes, serviceArgs, whereFilters);
            case 3 -> createProcessor3(genericMh, isRead, isWrite, isValueTracked, compIds, compTypes, serviceArgs, whereFilters);
            case 4 -> createProcessor4(genericMh, isRead, isWrite, isValueTracked, compIds, compTypes, serviceArgs, whereFilters);
            default -> null; // Fall back to spreader for 5+ params
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor1(java.lang.invoke.MethodHandle mh, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, ComponentId[] compIds, Class<?>[] compTypes, Object[] serviceArgs, java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters) {
        boolean hasFilters = !whereFilters.isEmpty();
        return (chunk, tick) -> {
            var stores = new ComponentStorage[1];
            var trackers = new ChangeTracker[1];
            var muts = new Mut[1];
            for (int i = 0; i < 1; i++) { if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]); if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]); }
            int count = chunk.count();
            try { for (int slot = 0; slot < count; slot++) {
                Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]); if (isWrite[0]) muts[0] = (Mut) a0;
                if (hasFilters && !checkFilters(whereFilters, new Object[]{a0}, isRead, isWrite, compTypes, muts, 1)) { continue; }
                mh.invokeExact(a0);
                for (int i = 0; i < 1; i++) { if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush()); }
            }} catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor2(java.lang.invoke.MethodHandle mh, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, ComponentId[] compIds, Class<?>[] compTypes, Object[] serviceArgs, java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters) {
        boolean hasFilters = !whereFilters.isEmpty();
        return (chunk, tick) -> {
            var stores = new ComponentStorage[2]; var trackers = new ChangeTracker[2]; var muts = new Mut[2];
            for (int i = 0; i < 2; i++) { if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]); if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]); }
            int count = chunk.count();
            try { for (int slot = 0; slot < count; slot++) {
                Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]); if (isWrite[0]) muts[0] = (Mut) a0;
                Object a1 = resolveArg(1, slot, tick, stores[1], trackers[1], isRead, isWrite, isValueTracked, serviceArgs, muts[1]); if (isWrite[1]) muts[1] = (Mut) a1;
                if (hasFilters && !checkFilters(whereFilters, new Object[]{a0, a1}, isRead, isWrite, compTypes, muts, 2)) { continue; }
                mh.invokeExact(a0, a1);
                for (int i = 0; i < 2; i++) { if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush()); }
            }} catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor3(java.lang.invoke.MethodHandle mh, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, ComponentId[] compIds, Class<?>[] compTypes, Object[] serviceArgs, java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters) {
        boolean hasFilters = !whereFilters.isEmpty();
        return (chunk, tick) -> {
            var stores = new ComponentStorage[3]; var trackers = new ChangeTracker[3]; var muts = new Mut[3];
            for (int i = 0; i < 3; i++) { if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]); if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]); }
            int count = chunk.count();
            try { for (int slot = 0; slot < count; slot++) {
                Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]); if (isWrite[0]) muts[0] = (Mut) a0;
                Object a1 = resolveArg(1, slot, tick, stores[1], trackers[1], isRead, isWrite, isValueTracked, serviceArgs, muts[1]); if (isWrite[1]) muts[1] = (Mut) a1;
                Object a2 = resolveArg(2, slot, tick, stores[2], trackers[2], isRead, isWrite, isValueTracked, serviceArgs, muts[2]); if (isWrite[2]) muts[2] = (Mut) a2;
                if (hasFilters && !checkFilters(whereFilters, new Object[]{a0, a1, a2}, isRead, isWrite, compTypes, muts, 3)) { continue; }
                mh.invokeExact(a0, a1, a2);
                for (int i = 0; i < 3; i++) { if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush()); }
            }} catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor4(java.lang.invoke.MethodHandle mh, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, ComponentId[] compIds, Class<?>[] compTypes, Object[] serviceArgs, java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters) {
        boolean hasFilters = !whereFilters.isEmpty();
        return (chunk, tick) -> {
            var stores = new ComponentStorage[4]; var trackers = new ChangeTracker[4]; var muts = new Mut[4];
            for (int i = 0; i < 4; i++) { if (isRead[i] || isWrite[i]) stores[i] = chunk.componentStorage(compIds[i]); if (isWrite[i]) trackers[i] = chunk.changeTracker(compIds[i]); }
            int count = chunk.count();
            try { for (int slot = 0; slot < count; slot++) {
                Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]); if (isWrite[0]) muts[0] = (Mut) a0;
                Object a1 = resolveArg(1, slot, tick, stores[1], trackers[1], isRead, isWrite, isValueTracked, serviceArgs, muts[1]); if (isWrite[1]) muts[1] = (Mut) a1;
                Object a2 = resolveArg(2, slot, tick, stores[2], trackers[2], isRead, isWrite, isValueTracked, serviceArgs, muts[2]); if (isWrite[2]) muts[2] = (Mut) a2;
                Object a3 = resolveArg(3, slot, tick, stores[3], trackers[3], isRead, isWrite, isValueTracked, serviceArgs, muts[3]); if (isWrite[3]) muts[3] = (Mut) a3;
                if (hasFilters && !checkFilters(whereFilters, new Object[]{a0, a1, a2, a3}, isRead, isWrite, compTypes, muts, 4)) { continue; }
                mh.invokeExact(a0, a1, a2, a3);
                for (int i = 0; i < 4; i++) { if (isWrite[i]) ((ComponentStorage) stores[i]).set(muts[i].slot(), muts[i].flush()); }
            }} catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean checkFilters(java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters, Object[] argValues, boolean[] isRead, boolean[] isWrite, Class<?>[] compTypes, Mut[] muts, int n) {
        var componentMap = new java.util.HashMap<Class<?>, Record>();
        for (int i = 0; i < n; i++) {
            if (isRead[i]) {
                componentMap.put(compTypes[i], (Record) argValues[i]);
            } else if (isWrite[i]) {
                componentMap.put(compTypes[i], (Record) muts[i].get());
            }
        }
        for (var filter : whereFilters.values()) {
            if (!filter.test(componentMap)) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveArg(int i, int slot, long tick, ComponentStorage store, ChangeTracker tracker, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, Object[] serviceArgs, Mut existing) {
        if (isRead[i]) { return store.get(slot); }
        else if (isWrite[i]) {
            if (existing == null) { return new Mut(store.get(slot), slot, tracker, tick, isValueTracked[i]); }
            else { existing.reset(store.get(slot), slot, tracker, tick); return existing; }
        }
        else { return serviceArgs[i]; }
    }
}
