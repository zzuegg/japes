package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.*;

public final class Archetype {

    private final ArchetypeId id;
    private final Map<ComponentId, Class<? extends Record>> componentTypes;
    private final int chunkCapacity;
    private final ComponentStorage.Factory storageFactory;
    private final java.util.Set<ComponentId> dirtyTrackedComponents;
    private final List<Chunk> chunks = new ArrayList<>();

    public Archetype(ArchetypeId id, ComponentRegistry registry, int chunkCapacity,
                     ComponentStorage.Factory storageFactory,
                     java.util.Set<ComponentId> dirtyTrackedComponents) {
        this.id = id;
        this.chunkCapacity = chunkCapacity;
        this.storageFactory = storageFactory;
        this.dirtyTrackedComponents = dirtyTrackedComponents;
        this.componentTypes = new LinkedHashMap<>();
        for (var compId : id.components()) {
            componentTypes.put(compId, registry.info(compId).type());
        }
    }

    public EntityLocation add(Entity entity) {
        int chunkIndex = findOrCreateChunkIndex();
        Chunk chunk = chunks.get(chunkIndex);
        int slot = chunk.add(entity);
        return new EntityLocation(id, chunkIndex, slot);
    }

    public <T extends Record> void set(ComponentId compId, EntityLocation loc, T value) {
        chunks.get(loc.chunkIndex()).set(compId, loc.slotIndex(), value);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId compId, EntityLocation loc) {
        return (T) chunks.get(loc.chunkIndex()).get(compId, loc.slotIndex());
    }

    public Optional<Entity> remove(EntityLocation loc) {
        Chunk chunk = chunks.get(loc.chunkIndex());
        int slot = loc.slotIndex();
        int lastSlot = chunk.count() - 1;

        Entity swapped = null;
        if (slot < lastSlot) {
            swapped = chunk.entity(lastSlot);
        }

        chunk.remove(slot);

        return Optional.ofNullable(swapped);
    }

    public Entity entity(EntityLocation loc) {
        return chunks.get(loc.chunkIndex()).entity(loc.slotIndex());
    }

    public int entityCount() {
        int total = 0;
        for (var chunk : chunks) {
            total += chunk.count();
        }
        return total;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<Chunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    public ArchetypeId id() {
        return id;
    }

    private int findOrCreateChunkIndex() {
        for (int i = 0; i < chunks.size(); i++) {
            if (!chunks.get(i).isFull()) {
                return i;
            }
        }
        chunks.add(new Chunk(chunkCapacity, componentTypes, storageFactory, dirtyTrackedComponents));
        return chunks.size() - 1;
    }
}
