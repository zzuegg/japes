package zzuegg.ecs.component;

import java.util.HashMap;
import java.util.Map;

public final class ComponentRegistry {

    private final Map<Class<?>, ComponentInfo> byType = new HashMap<>();
    private final Map<ComponentId, ComponentInfo> byId = new HashMap<>();
    private int nextId = 0;
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

        var id = new ComponentId(nextId++);
        var info = new ComponentInfo(id, recordType, true, false, valueTracked);
        byType.put(type, info);
        byId.put(id, info);
        return id;
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
