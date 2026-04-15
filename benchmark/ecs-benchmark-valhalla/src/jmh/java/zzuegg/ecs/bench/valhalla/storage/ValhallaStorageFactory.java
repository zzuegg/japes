package zzuegg.ecs.bench.valhalla.storage;

import zzuegg.ecs.storage.ComponentStorage;

/**
 * Factory that creates {@link ValhallaComponentStorage} instances.
 * Use with {@code World.builder().storageFactory(new ValhallaStorageFactory())}.
 */
public final class ValhallaStorageFactory implements ComponentStorage.Factory {
    @Override
    public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
        return new ValhallaComponentStorage<>(type, capacity);
    }
}
