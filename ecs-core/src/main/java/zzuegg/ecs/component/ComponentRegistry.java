package zzuegg.ecs.component;

import java.util.HashMap;
import java.util.Map;

public final class ComponentRegistry {

    private final Map<Class<?>, ComponentInfo> byType = new HashMap<>();
    private final Map<ComponentId, ComponentInfo> byId = new HashMap<>();
    private int nextId = 0;

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
        var existing = byType.get(type);
        if (existing != null) {
            return existing.id();
        }
        return register(type);
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
