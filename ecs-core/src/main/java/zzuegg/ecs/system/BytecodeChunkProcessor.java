package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.storage.ComponentStorage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Fallback processor tier built on arity-specialised MethodHandle.invokeExact
 * lambdas — handles writes and @Where filters for 1..4 component parameters.
 * Faster than the spreader path in {@link SystemExecutionPlan} because
 * invokeExact avoids the argument-array copy, slower than
 * {@link GeneratedChunkProcessor} because dispatch still goes through a
 * MethodHandle. The name is historical: the initial plan was to emit real
 * bytecode here; the actual implementation uses specialised lambdas.
 */
public final class BytecodeChunkProcessor {

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

        // Build a private lookup + MethodHandle once. Uniform Object-typed
        // signature lets invokeExact avoid the spreader's array copy while
        // still handling any primitive/boxing adaptation uniformly.
        var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
        var mh = lookup.unreflect(method);
        if (desc.instance() != null) {
            mh = mh.bindTo(desc.instance());
        }

        var objectParamTypes = new Class<?>[paramCount];
        java.util.Arrays.fill(objectParamTypes, Object.class);
        var genericMh = mh.asType(MethodType.methodType(void.class, objectParamTypes));

        // Dispatch to an arity-specialised lambda so each invokeExact site
        // has a fixed signature the JIT can fully inline.
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
        // Lifted out of the lambda: these arrays held only per-chunk state, but
        // were re-allocated on every process() call — undoing half the point of
        // the 'direct' processor. Lifting them here makes the lambda captures
        // stable references that JIT escape analysis can scalarise.
        var stores = new ComponentStorage[1];
        var trackers = new ChangeTracker[1];
        var muts = new Mut[1];
        return (chunk, tick) -> {
            if (isRead[0] || isWrite[0]) stores[0] = chunk.componentStorage(compIds[0]);
            if (isWrite[0]) trackers[0] = chunk.changeTracker(compIds[0]);
            int count = chunk.count();
            try { for (int slot = 0; slot < count; slot++) {
                Object a0 = resolveArg(0, slot, tick, stores[0], trackers[0], isRead, isWrite, isValueTracked, serviceArgs, muts[0]); if (isWrite[0]) muts[0] = (Mut) a0;
                if (hasFilters && !checkFilters(whereFilters, new Object[]{a0}, isRead, isWrite, compTypes, muts, 1)) { continue; }
                mh.invokeExact(a0);
                if (isWrite[0]) ((ComponentStorage) stores[0]).set(muts[0].slot(), muts[0].flush());
            }} catch (Throwable e) { throw new RuntimeException(e); }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor createProcessor2(java.lang.invoke.MethodHandle mh, boolean[] isRead, boolean[] isWrite, boolean[] isValueTracked, ComponentId[] compIds, Class<?>[] compTypes, Object[] serviceArgs, java.util.Map<Integer, zzuegg.ecs.query.FieldFilter> whereFilters) {
        boolean hasFilters = !whereFilters.isEmpty();
        var stores = new ComponentStorage[2];
        var trackers = new ChangeTracker[2];
        var muts = new Mut[2];
        return (chunk, tick) -> {
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
        var stores = new ComponentStorage[3];
        var trackers = new ChangeTracker[3];
        var muts = new Mut[3];
        return (chunk, tick) -> {
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
        var stores = new ComponentStorage[4];
        var trackers = new ChangeTracker[4];
        var muts = new Mut[4];
        return (chunk, tick) -> {
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
