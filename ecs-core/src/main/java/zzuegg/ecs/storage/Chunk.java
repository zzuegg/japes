package zzuegg.ecs.storage;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Chunk {

    private final int capacity;
    private final Entity[] entities;
    private final Map<ComponentId, ComponentStorage<?>> storages;
    private final Map<ComponentId, ChangeTracker> changeTrackers;
    private int count = 0;

    public Chunk(int capacity, Map<ComponentId, Class<? extends Record>> componentTypes,
                 ComponentStorage.Factory factory, Set<ComponentId> dirtyTrackedComponents) {
        this.capacity = capacity;
        this.entities = new Entity[capacity];
        this.storages = new HashMap<>();
        this.changeTrackers = new HashMap<>();
        for (var entry : componentTypes.entrySet()) {
            storages.put(entry.getKey(), factory.create(entry.getValue(), capacity));
            var tracker = new ChangeTracker(capacity);
            // Enable dirty-list bookkeeping only for components that have a
            // @Filter(Added/Changed) consumer — pure-write components pay
            // nothing on markChanged.
            if (dirtyTrackedComponents.contains(entry.getKey())) {
                tracker.setDirtyTracked(true);
            }
            changeTrackers.put(entry.getKey(), tracker);
        }
    }

    public int add(Entity entity) {
        if (isFull()) {
            throw new IllegalStateException("Chunk is full (capacity=" + capacity + ")");
        }
        int slot = count;
        entities[slot] = entity;
        count++;
        return slot;
    }

    public void remove(int slot) {
        int lastIndex = count - 1;
        if (slot < lastIndex) {
            entities[slot] = entities[lastIndex];
        }
        for (var storage : storages.values()) {
            storage.swapRemove(slot, count);
        }
        for (var tracker : changeTrackers.values()) {
            tracker.swapRemove(slot, count);
        }
        entities[lastIndex] = null;
        count--;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId id, int slot) {
        return ((ComponentStorage<T>) storages.get(id)).get(slot);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> void set(ComponentId id, int slot, T value) {
        ((ComponentStorage<T>) storages.get(id)).set(slot, value);
    }

    public Entity entity(int slot) {
        return entities[slot];
    }

    public Entity[] entityArray() {
        return entities;
    }

    public ComponentStorage<?> componentStorage(ComponentId id) {
        return storages.get(id);
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isFull() {
        return count >= capacity;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public ChangeTracker changeTracker(ComponentId id) {
        return changeTrackers.get(id);
    }

    public void markAdded(int slot, long tick) {
        for (var tracker : changeTrackers.values()) {
            tracker.markAdded(slot, tick);
        }
    }
}
