package zzuegg.ecs.world;

import zzuegg.ecs.archetype.Archetype;
import zzuegg.ecs.archetype.ArchetypeId;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.io.DataInput;
import java.io.IOException;

/**
 * Pre-resolved spawn path for bulk entity restoration with known IDs.
 * Caches the target archetype, chunk, and per-component storage references
 * so repeated {@code spawnWithId} calls skip the per-entity HashSet,
 * ArchetypeId, and ComponentInfo[] allocations that the varargs
 * {@code World.spawnWithId(Entity, Record...)} pays per call.
 *
 * <p>All entities spawned through a single builder must have the exact
 * same set of component types (same archetype). This is the common case
 * for the persistence decode path where all entities in a save file
 * share the same component shape.
 *
 * <p>Usage:
 * <pre>{@code
 * var builder = world.bulkSpawnWithIdBuilder(Position.class, Velocity.class, Health.class);
 * for (...) {
 *     builder.spawnWithId(entity, new Position(...), new Velocity(...), new Health(...));
 * }
 * }</pre>
 */
public final class BulkSpawnWithIdBuilder {

    private final World world;
    private final ComponentId[] compIds;
    private final zzuegg.ecs.component.ComponentInfo[] infos;
    private final Archetype archetype;
    private Chunk cachedChunk;
    private int cachedChunkIndex = -1;
    @SuppressWarnings("rawtypes")
    private final ComponentStorage[] cachedStorages;
    /** Cached SoA field arrays per component, refreshed on chunk change. */
    private final Object[][] cachedSoaArrays;

    @SuppressWarnings("unchecked")
    BulkSpawnWithIdBuilder(World world, Class<? extends Record>[] componentTypes) {
        this.world = world;
        var registry = world.componentRegistry();
        this.compIds = new ComponentId[componentTypes.length];
        this.infos = new zzuegg.ecs.component.ComponentInfo[componentTypes.length];
        var idSet = new java.util.HashSet<ComponentId>();
        for (int i = 0; i < componentTypes.length; i++) {
            var info = registry.getOrRegisterInfo(componentTypes[i]);
            compIds[i] = info.id();
            infos[i] = info;
            idSet.add(info.id());
        }
        var archetypeId = ArchetypeId.of(idSet);
        this.archetype = world.archetypeGraph().getOrCreate(archetypeId);
        this.cachedStorages = new ComponentStorage[componentTypes.length];
        this.cachedSoaArrays = new Object[componentTypes.length][];
    }

    /** The pre-resolved component infos in declaration order. */
    public zzuegg.ecs.component.ComponentInfo[] componentInfos() {
        return infos;
    }

    /**
     * Pre-allocate enough chunk capacity for the given number of entities.
     * Call before a bulk load loop to avoid creating chunks one-by-one.
     */
    public void ensureCapacity(int entityCount) {
        archetype.ensureCapacity(entityCount);
    }

    /**
     * Spawn an entity with a pre-determined ID and components. The entity
     * allocator is told to reserve the exact index+generation. The archetype,
     * chunk, and storage lookups are all cached — the only per-entity
     * allocations are the {@code EntityLocation} record and the entity
     * slot in the chunk.
     */
    @SuppressWarnings("unchecked")
    public void spawnWithId(Entity entity, Record... components) {
        if (components.length != compIds.length) {
            throw new IllegalArgumentException(
                "Expected " + compIds.length + " components, got " + components.length);
        }
        world.entityAllocator().allocateExact(entity.index(), entity.generation());
        var location = archetype.add(entity);
        world.setEntityLocation(entity.index(), location);

        int chunkIdx = location.chunkIndex();
        if (chunkIdx != cachedChunkIndex) {
            refreshChunkCache(chunkIdx);
        }

        int slot = location.slotIndex();
        for (int i = 0; i < components.length; i++) {
            cachedStorages[i].set(slot, components[i]);
        }

        cachedChunk.markAdded(slot, world.currentTick());
    }

    /**
     * Spawn an entity with a pre-determined ID, reading component data
     * directly from a {@link DataInput} into SoA backing arrays via
     * {@link BinaryCodec#decodeDirect}. No intermediate Record objects
     * are allocated.
     *
     * <p>The codecs array must be in the same order as the component types
     * passed to the builder constructor.
     *
     * @param entity the entity with the exact ID to restore
     * @param in     the data input positioned at this entity's component data
     * @param codecs one BinaryCodec per component, same order as constructor types
     */
    @SuppressWarnings("unchecked")
    public void spawnWithIdDirect(Entity entity, DataInput in, BinaryCodec<?>[] codecs)
            throws IOException {
        if (codecs.length != compIds.length) {
            throw new IllegalArgumentException(
                "Expected " + compIds.length + " codecs, got " + codecs.length);
        }
        world.entityAllocator().allocateExact(entity.index(), entity.generation());
        var location = archetype.add(entity);
        world.setEntityLocation(entity.index(), location);

        int chunkIdx = location.chunkIndex();
        if (chunkIdx != cachedChunkIndex) {
            refreshChunkCache(chunkIdx);
        }

        int slot = location.slotIndex();
        for (int i = 0; i < codecs.length; i++) {
            codecs[i].decodeDirect(in, cachedSoaArrays[i], slot);
        }

        cachedChunk.markAdded(slot, world.currentTick());
    }

    /**
     * Allocate an entity slot without writing any component data. Returns
     * the slot index within the current chunk. The caller can then use
     * {@link #soaArrays(int)} to get the per-component SoA field arrays and
     * write directly via {@link BinaryCodec#decodeDirect}. Call
     * {@link #markAdded(int)} after all components are written.
     *
     * <p>This supports tag-based decode where the component order in the
     * stream is determined per-entity by tag bytes.
     */
    public int allocateSlot(Entity entity) {
        world.entityAllocator().allocateExact(entity.index(), entity.generation());
        var location = archetype.add(entity);
        world.setEntityLocation(entity.index(), location);

        int chunkIdx = location.chunkIndex();
        if (chunkIdx != cachedChunkIndex) {
            refreshChunkCache(chunkIdx);
        }
        return location.slotIndex();
    }

    /**
     * Returns the cached SoA field arrays for the component at the given
     * builder index (same order as the constructor's component types).
     * Only valid after the most recent {@link #allocateSlot(Entity)} or
     * {@link #spawnWithId(Entity, Record...)} call.
     */
    public Object[] soaArrays(int componentIndex) {
        return cachedSoaArrays[componentIndex];
    }

    /**
     * Mark the given slot as added in all change trackers. Call after all
     * components have been written via direct SoA access.
     */
    public void markAdded(int slot) {
        cachedChunk.markAdded(slot, world.currentTick());
    }

    private void refreshChunkCache(int chunkIdx) {
        cachedChunk = archetype.chunks().get(chunkIdx);
        cachedChunkIndex = chunkIdx;
        for (int i = 0; i < compIds.length; i++) {
            cachedStorages[i] = cachedChunk.componentStorage(compIds[i]);
            cachedSoaArrays[i] = cachedStorages[i].soaFieldArrays();
        }
    }
}
