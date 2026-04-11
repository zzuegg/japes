package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ArchetypeGraph {

    private final ComponentRegistry registry;
    private final int chunkCapacity;
    private final ComponentStorage.Factory storageFactory;
    private final Map<ArchetypeId, Archetype> archetypes = new HashMap<>();
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> addEdges = new HashMap<>();
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> removeEdges = new HashMap<>();
    // Memoized results of findMatching(required). Invalidated whenever a new
    // archetype is created; mutations of existing archetypes' entity contents
    // don't affect which archetypes match a given required set.
    // ConcurrentHashMap: multiple system threads may call findMatching() for
    // different required sets in the same tick, producing concurrent puts.
    private final Map<Set<ComponentId>, List<Archetype>> findMatchingCache = new ConcurrentHashMap<>();
    // Components for which ChangeTracker dirty-list bookkeeping is enabled
    // on every chunk. Mutated by World when @Filter(Added/Changed) consumers
    // register/unregister at plan build time. The set is shared by reference
    // with every Archetype created here so new chunks pick up the current
    // state at construction time without an explicit callback.
    private final Set<ComponentId> dirtyTrackedComponents = new HashSet<>();
    // Components for which ChangeTracker bookkeeping is completely
    // disabled — markAdded / markChanged become no-ops. Populated by
    // World at plan-build time: any component with zero
    // @Filter(Added/Changed) observers is fully untracked, skipping
    // the per-slot tick array writes entirely. Shared by reference
    // with Archetype/Chunk so new chunks inherit the state.
    private final Set<ComponentId> fullyUntrackedComponents = new HashSet<>();
    // Bumped every time getOrCreate actually materialises a new archetype.
    // Callers that memoise findMatching() results can compare this against
    // a stored snapshot to decide whether their cache is still valid —
    // comparing two longs is O(1), whereas hashing the required Set<ComponentId>
    // every call walks every element and was measurably hot in the profile.
    private long generation = 0;

    public ArchetypeGraph(ComponentRegistry registry, int chunkCapacity, ComponentStorage.Factory storageFactory) {
        this.registry = registry;
        this.chunkCapacity = chunkCapacity;
        this.storageFactory = storageFactory;
    }

    public long generation() {
        return generation;
    }

    public Archetype getOrCreate(ArchetypeId id) {
        var existing = archetypes.get(id);
        if (existing != null) return existing;
        var created = new Archetype(id, registry, chunkCapacity, storageFactory,
            dirtyTrackedComponents, fullyUntrackedComponents);
        archetypes.put(id, created);
        // New archetype invalidates every cached query — any previously-cached
        // match set could now be missing this archetype.
        findMatchingCache.clear();
        generation++;
        return created;
    }

    /**
     * Register a component as dirty-tracked. Walks every existing archetype
     * that contains the component and enables dirty-list bookkeeping on the
     * change trackers in each of their chunks. Archetypes created after this
     * call pick up the state via the shared set reference.
     */
    public void enableDirtyTracking(ComponentId compId) {
        if (!dirtyTrackedComponents.add(compId)) return;
        for (var archetype : archetypes.values()) {
            if (!archetype.id().contains(compId)) continue;
            for (var chunk : archetype.chunks()) {
                var tracker = chunk.changeTracker(compId);
                if (tracker != null) tracker.setDirtyTracked(true, chunk.count());
            }
        }
    }

    public Set<ComponentId> dirtyTrackedComponents() {
        return dirtyTrackedComponents;
    }

    /**
     * Set the complete "fully untracked" component set. Any tracker
     * for a component in this set becomes a no-op on
     * {@code markAdded}/{@code markChanged} — the per-slot tick
     * array writes are skipped entirely. World calls this once at
     * plan-build time after it has computed the union of all
     * observed components (filter targets + RemovedComponents
     * consumers); everything else is eligible for untracking.
     *
     * <p>Walks every existing chunk and flips the flag on every
     * matching tracker. New archetypes created after this call pick
     * up the state via the shared set reference.
     */
    public void setFullyUntrackedComponents(Set<ComponentId> untracked) {
        fullyUntrackedComponents.clear();
        fullyUntrackedComponents.addAll(untracked);
        for (var archetype : archetypes.values()) {
            for (var compId : archetype.id().components()) {
                for (var chunk : archetype.chunks()) {
                    var tracker = chunk.changeTracker(compId);
                    if (tracker != null) {
                        tracker.setFullyUntracked(untracked.contains(compId));
                    }
                }
            }
        }
    }

    public ArchetypeId addEdge(ArchetypeId source, ComponentId added) {
        return addEdges
            .computeIfAbsent(source, k -> new HashMap<>())
            .computeIfAbsent(added, k -> {
                var targetId = source.with(added);
                getOrCreate(targetId);
                return targetId;
            });
    }

    public ArchetypeId removeEdge(ArchetypeId source, ComponentId removed) {
        return removeEdges
            .computeIfAbsent(source, k -> new HashMap<>())
            .computeIfAbsent(removed, k -> {
                var targetId = source.without(removed);
                getOrCreate(targetId);
                return targetId;
            });
    }

    public List<Archetype> findMatching(Set<ComponentId> required) {
        // Cache the result keyed by the required set — most systems query the
        // same set every tick. computeIfAbsent is atomic so concurrent threads
        // querying different required sets don't corrupt the map.
        return findMatchingCache.computeIfAbsent(Set.copyOf(required), key -> {
            var result = new ArrayList<Archetype>();
            for (var entry : archetypes.entrySet()) {
                if (entry.getKey().components().containsAll(key)) {
                    result.add(entry.getValue());
                }
            }
            return List.copyOf(result);
        });
    }

    public Archetype get(ArchetypeId id) {
        return archetypes.get(id);
    }

    public int archetypeCount() {
        return archetypes.size();
    }

    public Collection<Archetype> allArchetypes() {
        return Collections.unmodifiableCollection(archetypes.values());
    }
}
