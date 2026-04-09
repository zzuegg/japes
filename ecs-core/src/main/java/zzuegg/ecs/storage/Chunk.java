package zzuegg.ecs.storage;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.HashMap;
import java.util.Map;

public final class Chunk {

    private final int capacity;
    private final Entity[] entities;
    private final Map<ComponentId, ComponentArray<?>> arrays;
    private final Map<ComponentId, ChangeTracker> changeTrackers;
    private int count = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Chunk(int capacity, Map<ComponentId, Class<? extends Record>> componentTypes) {
        this.capacity = capacity;
        this.entities = new Entity[capacity];
        this.arrays = new HashMap<>();
        this.changeTrackers = new HashMap<>();
        for (var entry : componentTypes.entrySet()) {
            arrays.put(entry.getKey(), new ComponentArray(entry.getValue(), capacity));
            changeTrackers.put(entry.getKey(), new ChangeTracker(capacity));
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
            for (var array : arrays.values()) {
                array.swapRemove(slot, count);
            }
            for (var tracker : changeTrackers.values()) {
                tracker.swapRemove(slot, count);
            }
        } else {
            for (var array : arrays.values()) {
                array.swapRemove(slot, count);
            }
            for (var tracker : changeTrackers.values()) {
                tracker.swapRemove(slot, count);
            }
        }
        entities[lastIndex] = null;
        count--;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId id, int slot) {
        return ((ComponentArray<T>) arrays.get(id)).get(slot);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> void set(ComponentId id, int slot, T value) {
        ((ComponentArray<T>) arrays.get(id)).set(slot, value);
    }

    public Entity entity(int slot) {
        return entities[slot];
    }

    public Entity[] entityArray() {
        return entities;
    }

    public ComponentArray<?> componentArray(ComponentId id) {
        return arrays.get(id);
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
