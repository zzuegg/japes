package zzuegg.ecs.storage;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.Map;
import java.util.Set;

public final class Chunk {

    private final int capacity;
    private final Entity[] entities;
    // Both component lookups used to go through HashMap<ComponentId, ...>.get,
    // which dominated World.setComponent's hot path (two lookups per call on
    // a record-typed key). Now indexed by ComponentId.id() directly — a
    // plain array load + cast. Sized to maxGlobalComponentId + 1. Slots
    // for components this chunk does not carry stay null.
    private final ComponentStorage<?>[] storagesById;
    private final ChangeTracker[] changeTrackersById;
    // Linear list of trackers for whole-chunk sweeps (remove, swapRemove,
    // markAdded). Separate from the id-indexed array so we don't iterate
    // nulls.
    private final ChangeTracker[] trackerList;
    // Same, for storages — used by remove() to swap-remove every component
    // at once. Iterating storagesById directly would touch null slots.
    private final ComponentStorage<?>[] storageList;
    private int count = 0;

    public Chunk(int capacity, Map<ComponentId, Class<? extends Record>> componentTypes,
                 ComponentStorage.Factory factory, Set<ComponentId> dirtyTrackedComponents) {
        this.capacity = capacity;
        this.entities = new Entity[capacity];
        // Find the max id so we can size the flat lookup arrays.
        int maxId = -1;
        for (var id : componentTypes.keySet()) {
            if (id.id() > maxId) maxId = id.id();
        }
        this.storagesById = new ComponentStorage<?>[maxId + 1];
        this.changeTrackersById = new ChangeTracker[maxId + 1];
        this.storageList = new ComponentStorage<?>[componentTypes.size()];
        this.trackerList = new ChangeTracker[componentTypes.size()];
        int listIdx = 0;
        for (var entry : componentTypes.entrySet()) {
            var storage = factory.create(entry.getValue(), capacity);
            storagesById[entry.getKey().id()] = storage;
            storageList[listIdx] = storage;
            var tracker = new ChangeTracker(capacity);
            // Enable dirty-list bookkeeping only for components that have a
            // @Filter(Added/Changed) consumer — pure-write components pay
            // nothing on markChanged.
            if (dirtyTrackedComponents.contains(entry.getKey())) {
                tracker.setDirtyTracked(true);
            }
            changeTrackersById[entry.getKey().id()] = tracker;
            trackerList[listIdx] = tracker;
            listIdx++;
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
        for (var storage : storageList) {
            storage.swapRemove(slot, count);
        }
        for (var tracker : trackerList) {
            tracker.swapRemove(slot, count);
        }
        entities[lastIndex] = null;
        count--;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId id, int slot) {
        return ((ComponentStorage<T>) storagesById[id.id()]).get(slot);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> void set(ComponentId id, int slot, T value) {
        ((ComponentStorage<T>) storagesById[id.id()]).set(slot, value);
    }

    public Entity entity(int slot) {
        return entities[slot];
    }

    public Entity[] entityArray() {
        return entities;
    }

    public ComponentStorage<?> componentStorage(ComponentId id) {
        return storagesById[id.id()];
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
        return changeTrackersById[id.id()];
    }

    public void markAdded(int slot, long tick) {
        for (var tracker : trackerList) {
            tracker.markAdded(slot, tick);
        }
    }
}
