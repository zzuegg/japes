package zzuegg.ecs.world;

import zzuegg.ecs.archetype.Archetype;
import zzuegg.ecs.archetype.ArchetypeId;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentInfo;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

/**
 * Pre-resolved spawn path for bulk entity creation. Caches the target
 * archetype, chunk, and per-component storage references so repeated
 * spawns of the same component shape skip the archetype graph lookup,
 * HashSet allocation, and ComponentInfo resolution that the varargs
 * {@code world.spawn(Record...)} pays per call.
 *
 * <p>Each {@link #spawn(Record...)} call writes components through
 * the cached storage references. When the cached chunk fills up, the
 * builder transparently resolves the next chunk.
 *
 * <p>Usage:
 * <pre>{@code
 * var builder = world.spawnBuilder(State.class, Health.class, Mana.class);
 * for (int i = 0; i < 1000; i++) {
 *     builder.spawn(new State(i), new Health(1000), new Mana(0));
 * }
 * }</pre>
 */
public final class SpawnBuilder {

    private final World world;
    private final ComponentId[] compIds;
    private final Archetype archetype;
    private Chunk cachedChunk;
    private int cachedChunkIndex = -1;
    @SuppressWarnings("rawtypes")
    private final ComponentStorage[] cachedStorages;

    @SuppressWarnings("unchecked")
    SpawnBuilder(World world, Class<? extends Record>[] componentTypes) {
        this.world = world;
        var registry = world.componentRegistry();
        this.compIds = new ComponentId[componentTypes.length];
        var idSet = new java.util.HashSet<ComponentId>();
        for (int i = 0; i < componentTypes.length; i++) {
            compIds[i] = registry.getOrRegister(componentTypes[i]);
            idSet.add(compIds[i]);
        }
        var archetypeId = ArchetypeId.of(idSet);
        this.archetype = world.archetypeGraph().getOrCreate(archetypeId);
        this.cachedStorages = new ComponentStorage[componentTypes.length];
    }

    @SuppressWarnings("unchecked")
    public Entity spawn(Record... components) {
        if (components.length != compIds.length) {
            throw new IllegalArgumentException(
                "Expected " + compIds.length + " components, got " + components.length);
        }
        var entity = world.entityAllocator().allocate();
        var location = archetype.add(entity);
        world.setEntityLocation(entity.index(), location);

        int chunkIdx = location.chunkIndex();
        if (chunkIdx != cachedChunkIndex) {
            cachedChunk = archetype.chunks().get(chunkIdx);
            cachedChunkIndex = chunkIdx;
            for (int i = 0; i < compIds.length; i++) {
                cachedStorages[i] = cachedChunk.componentStorage(compIds[i]);
            }
        }

        int slot = location.slotIndex();
        for (int i = 0; i < components.length; i++) {
            cachedStorages[i].set(slot, components[i]);
        }

        cachedChunk.markAdded(slot, world.currentTick());
        return entity;
    }
}
