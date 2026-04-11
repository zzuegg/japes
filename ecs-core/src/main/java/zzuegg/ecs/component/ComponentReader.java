package zzuegg.ecs.component;

import zzuegg.ecs.archetype.Archetype;
import zzuegg.ecs.archetype.EntityLocation;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.List;

/**
 * Fast cross-entity component reader. Declared as a system service
 * parameter, resolved once at plan-build time with the target
 * component's {@link ComponentId} pre-cached and the world reference
 * bound. Each {@code get(entity)} call does a single
 * {@code entityLocations} array read plus a direct storage lookup —
 * no {@code ClassValue.get}, no {@code isAlive} check, no
 * {@code HashMap.get} on the component registry, no virtual dispatch
 * through the {@code World} boundary.
 *
 * <p>Intended for hot-path cross-entity reads inside {@code @Pair}
 * systems and the like, where the user needs to fetch components on
 * <em>other</em> entities than {@code self}. Compared to
 * {@code world.getComponent(entity, Class)} — which is the fallback
 * path — this is usually 3–5× faster on the steady-state lookup.
 *
 * <p>Also caches the most recent {@code Archetype → ComponentStorage}
 * mapping, so repeated lookups of entities in the same archetype
 * (the common case — a whole chunk of prey in one archetype) hit a
 * single identity compare plus an array load. Archetype changes
 * (entity migration, respawns) invalidate the cache and the next
 * lookup re-fills it.
 *
 * <p>The reader is thread-unsafe by design. Each system gets its
 * own instance resolved at plan build; systems running on different
 * threads must not share the same reader.
 *
 * <p>Declared as a service parameter:
 * <pre>{@code
 * @System
 * @Pair(Targeting.class)
 * void pursuit(
 *     @Read Position selfPos,
 *     @Write Mut<Velocity> selfVel,
 *     Entity self,
 *     PairReader<Targeting> reader,
 *     ComponentReader<Position> posReader
 * ) {
 *     for (var pair : reader.fromSource(self)) {
 *         var targetPos = posReader.get(pair.target());
 *         // ...
 *     }
 * }
 * }</pre>
 */
public final class ComponentReader<T extends Record> {

    private final ComponentId compId;
    // Direct reference to the World's entityLocations list. Avoids
    // the virtual {@code IntFunction.apply} call that a lambda would
    // require — for a hot-path reader called 1000+ times per tick
    // the saved ~2 ns per call adds up. The trade-off: this reader
    // now depends on the World's private list remaining stable, so
    // it's invalidated if the world rebuilds that list (which it
    // never does today).
    private final List<EntityLocation> entityLocations;

    // Per-call cache: the last (archetype, chunkIndex) we served.
    // Hitting the same chunk again skips the archetype.get(compId)
    // indirection and reuses the cached storage pointer.
    // componentStorage is chunk-specific, so the cache key is both
    // archetype and chunkIndex.
    private Archetype cachedArchetype;
    private int cachedChunkIndex = -1;
    private ComponentStorage<T> cachedStorage;

    /**
     * Framework entry point — not intended for user code. World
     * calls this at plan build with the pre-resolved
     * {@link ComponentId} and a direct reference to the world's
     * {@code entityLocations} list.
     */
    public ComponentReader(ComponentId compId, List<EntityLocation> entityLocations) {
        this.compId = compId;
        this.entityLocations = entityLocations;
    }

    /**
     * Fast lookup for {@code entity}'s {@code T} component. Returns
     * {@code null} if the entity has no location (rare — typically
     * means the entity has been despawned since the last lookup).
     *
     * <p>Does <em>not</em> throw for missing or dead entities — the
     * hot path is expected to have already validated aliveness via
     * {@code world.isAlive} or via trusted relation-store pairs.
     * Returning null lets the caller handle the rare dead-entity
     * case without an exception.
     */
    @SuppressWarnings("unchecked")
    public T get(Entity entity) {
        int idx = entity.index();
        if (idx < 0 || idx >= entityLocations.size()) return null;
        var loc = entityLocations.get(idx);
        if (loc == null) return null;
        var arch = loc.archetype();
        int chunkIdx = loc.chunkIndex();
        var storage = cachedStorage;
        if (arch != cachedArchetype || chunkIdx != cachedChunkIndex) {
            // Cache miss: re-resolve the storage pointer for this
            // (archetype, chunk). Every subsequent lookup in the
            // same chunk takes the fast path above.
            storage = (ComponentStorage<T>) arch.chunks().get(chunkIdx)
                .componentStorage(compId);
            cachedArchetype = arch;
            cachedChunkIndex = chunkIdx;
            cachedStorage = storage;
        }
        return storage.get(loc.slotIndex());
    }
}
