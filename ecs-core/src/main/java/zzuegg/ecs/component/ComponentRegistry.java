package zzuegg.ecs.component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

public final class ComponentRegistry {

    private final Map<Class<?>, ComponentInfo> byType = new ConcurrentHashMap<>();
    private final Map<ComponentId, ComponentInfo> byId = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);
    // Per-class cache that HotSpot special-cases: ClassValue.get has a JIT
    // intrinsic that lets the JIT fold the lookup to a direct field read on
    // the Class object when the call site is monomorphic. Previously the
    // hot path {@code getOrRegisterInfo(component.getClass())} went through
    // a HashMap<Class,?>.get — noticeable on setComponent-heavy workloads.
    // ClassValue's computeValue is called exactly once per Class and the
    // result is cached on the Class itself, thread-safe by construction.
    private final ClassValue<ComponentInfo> infoByClass = new ClassValue<>() {
        @Override
        protected ComponentInfo computeValue(Class<?> type) {
            // Populate the HashMaps as a side-effect of the first resolve —
            // other entry points still read from them, and this also assigns
            // the ComponentId.
            register(type);
            return byType.get(type);
        }
    };

    public ComponentId register(Class<?> type) {
        var existing = byType.get(type);
        if (existing != null) {
            return existing.id();
        }
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Components must be records: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        var recordType = (Class<? extends Record>) type;

        boolean sparse = type.isAnnotationPresent(SparseStorage.class);
        if (sparse) {
            // Sparse storage was scaffolded (annotation + ComponentArray / SparseSet)
            // but never wired into the archetype/chunk pipeline. Silently falling
            // back to table storage would mask the gap; a hard failure is clearer
            // until the sparse path is actually implemented.
            throw new UnsupportedOperationException(
                "@SparseStorage is not yet implemented; mark " + type.getName()
                    + " with @TableStorage (default) instead");
        }
        boolean valueTracked = type.isAnnotationPresent(ValueTracked.class);

        // Use compute to atomically assign an id and populate both maps so
        // concurrent first-registrations of different component types don't
        // produce duplicate ids or leave byId inconsistent with byType.
        ComponentInfo[] result = new ComponentInfo[1];
        byType.compute(type, (k, prev) -> {
            if (prev != null) { result[0] = prev; return prev; }
            var id = new ComponentId(nextId.getAndIncrement());
            var info = new ComponentInfo(id, recordType, true, false, valueTracked);
            byId.put(id, info);
            result[0] = info;
            return info;
        });
        return result[0].id();
    }

    public ComponentId getOrRegister(Class<?> type) {
        return getOrRegisterInfo(type).id();
    }

    /**
     * One-lookup variant used by hot paths like {@code World.setComponent}
     * that need both the {@code ComponentId} and the {@code ComponentInfo}
     * for the same class. Backed by {@link ClassValue}, which HotSpot has
     * a JIT intrinsic for — the JIT can fold this lookup to a direct field
     * load on the {@code Class} object when the call site sees a stable
     * component type.
     */
    public ComponentInfo getOrRegisterInfo(Class<?> type) {
        return infoByClass.get(type);
    }

    public ComponentInfo info(Class<?> type) {
        var info = byType.get(type);
        if (info == null) {
            throw new IllegalArgumentException("Component not registered: " + type.getName());
        }
        return info;
    }

    public ComponentInfo info(ComponentId id) {
        var info = byId.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown component id: " + id);
        }
        return info;
    }

    public int count() {
        return byType.size();
    }
}
