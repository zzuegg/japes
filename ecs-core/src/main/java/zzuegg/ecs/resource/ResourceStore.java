package zzuegg.ecs.resource;

import java.util.HashMap;
import java.util.Map;

public final class ResourceStore {

    static final class Entry<T> {
        private T value;

        Entry(T value) { this.value = value; }

        T value() { return value; }
        void setValue(T value) { this.value = value; }
    }

    private final Map<Class<?>, Entry<?>> resources = new HashMap<>();

    public <T> void insert(T resource) {
        @SuppressWarnings("unchecked")
        var existing = (Entry<T>) resources.get(resource.getClass());
        if (existing != null) {
            existing.setValue(resource);
        } else {
            resources.put(resource.getClass(), new Entry<>(resource));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Res<T> get(Class<T> type) {
        var entry = (Entry<T>) resources.get(type);
        if (entry == null) {
            throw new IllegalArgumentException("Resource not found: " + type.getName());
        }
        return new Res<>(entry);
    }

    @SuppressWarnings("unchecked")
    public <T> ResMut<T> getMut(Class<T> type) {
        var entry = (Entry<T>) resources.get(type);
        if (entry == null) {
            throw new IllegalArgumentException("Resource not found: " + type.getName());
        }
        return new ResMut<>(entry);
    }

    public boolean contains(Class<?> type) {
        return resources.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public void setDirect(Class<?> type, Object value) {
        var entry = (Entry<Object>) resources.get(type);
        if (entry != null) {
            entry.setValue(value);
        } else {
            resources.put(type, new Entry<>(value));
        }
    }
}
