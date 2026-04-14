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
    private final java.util.Set<ComponentId> fullyUntrackedComponents;
    private final List<Chunk> chunks = new ArrayList<>();
    // Index of the chunk that currently has open slots, or -1 when all
    // chunks are full and the next add must create a new one. Maintained
    // lazily so the common steady-state (one open chunk) costs a single
    // integer check instead of a linear scan over all chunks.
    private int openChunkIndex = -1;

    public Archetype(ArchetypeId id, ComponentRegistry registry, int chunkCapacity,
                     ComponentStorage.Factory storageFactory,
                     java.util.Set<ComponentId> dirtyTrackedComponents) {
        this(id, registry, chunkCapacity, storageFactory, dirtyTrackedComponents, java.util.Set.of());
    }

    public Archetype(ArchetypeId id, ComponentRegistry registry, int chunkCapacity,
                     ComponentStorage.Factory storageFactory,
                     java.util.Set<ComponentId> dirtyTrackedComponents,
                     java.util.Set<ComponentId> fullyUntrackedComponents) {
        this.id = id;
        this.chunkCapacity = chunkCapacity;
        this.storageFactory = storageFactory;
        this.dirtyTrackedComponents = dirtyTrackedComponents;
        this.fullyUntrackedComponents = fullyUntrackedComponents;
        this.componentTypes = new LinkedHashMap<>();
        for (var compId : id.components()) {
            componentTypes.put(compId, registry.info(compId).type());
        }
    }

    /**
     * Pre-allocate enough chunks to hold {@code entityCount} entities.
     * Call before a bulk insert to avoid creating chunks one-by-one.
     */
    public void ensureCapacity(int entityCount) {
        int existingCapacity = 0;
        for (var chunk : chunks) {
            existingCapacity += chunkCapacity - chunk.count();
        }
        int needed = entityCount - existingCapacity;
        while (needed > 0) {
            chunks.add(new Chunk(chunkCapacity, componentTypes, storageFactory,
                dirtyTrackedComponents, fullyUntrackedComponents));
            needed -= chunkCapacity;
        }
        if (openChunkIndex < 0 && !chunks.isEmpty()) {
            for (int i = 0; i < chunks.size(); i++) {
                if (!chunks.get(i).isFull()) {
                    openChunkIndex = i;
                    break;
                }
            }
        }
    }

    public EntityLocation add(Entity entity) {
        int chunkIndex = findOrCreateChunkIndex();
        Chunk chunk = chunks.get(chunkIndex);
        int slot = chunk.add(entity);
        return new EntityLocation(this, chunkIndex, slot);
    }

    public <T extends Record> void set(ComponentId compId, EntityLocation loc, T value) {
        chunks.get(loc.chunkIndex()).set(compId, loc.slotIndex(), value);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId compId, EntityLocation loc) {
        return (T) chunks.get(loc.chunkIndex()).get(compId, loc.slotIndex());
    }

    /**
     * Remove the entity at the given location via swap-remove.
     * Returns the entity that was swapped into the vacated slot,
     * or null if the removed entity was already the last in the chunk.
     */
    public Entity remove(EntityLocation loc) {
        Chunk chunk = chunks.get(loc.chunkIndex());
        int slot = loc.slotIndex();
        int lastSlot = chunk.count() - 1;

        Entity swapped = null;
        if (slot < lastSlot) {
            swapped = chunk.entity(lastSlot);
        }

        chunk.remove(slot);
        openChunkIndex = loc.chunkIndex();

        return swapped;
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

    /**
     * Returns the internal chunk list directly. Callers must not mutate it.
     * This avoids allocating a {@code Collections.unmodifiableList} wrapper
     * on every call — critical because this is invoked per system per
     * archetype per tick.
     */
    public List<Chunk> chunks() {
        return chunks;
    }

    public ArchetypeId id() {
        return id;
    }


    public zzuegg.ecs.storage.Chunk createChunk() {
        var chunk = new zzuegg.ecs.storage.Chunk(chunkCapacity, componentTypes, storageFactory,
            dirtyTrackedComponents, fullyUntrackedComponents);
        chunks.add(chunk);
        return chunk;
    }

    public int chunkCapacity() {
        return chunkCapacity;
    }

    private int findOrCreateChunkIndex() {
        if (openChunkIndex >= 0 && !chunks.get(openChunkIndex).isFull()) {
            return openChunkIndex;
        }
        chunks.add(new Chunk(chunkCapacity, componentTypes, storageFactory,
            dirtyTrackedComponents, fullyUntrackedComponents));
        openChunkIndex = chunks.size() - 1;
        return openChunkIndex;
    }
}
