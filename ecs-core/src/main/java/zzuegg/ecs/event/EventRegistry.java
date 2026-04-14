package zzuegg.ecs.event;

import java.util.HashMap;
import java.util.Map;

public final class EventRegistry {

    private final Map<Class<?>, EventStore<?>> stores = new HashMap<>();

    public <T extends Record> void register(Class<T> type) {
        stores.putIfAbsent(type, new EventStore<T>());
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> EventStore<T> store(Class<T> type) {
        var store = (EventStore<T>) stores.get(type);
        if (store == null) {
            throw new IllegalArgumentException("Event type not registered: " + type.getName());
        }
        return store;
    }

    public boolean isEmpty() {
        return stores.isEmpty();
    }

    public void swapAll() {
        for (var store : stores.values()) {
            store.swap();
        }
    }
}
