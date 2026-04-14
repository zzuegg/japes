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
    // Edge caches: archetype → flat array indexed by ComponentId.id().
    // Replaces HashMap<ArchetypeId, HashMap<ComponentId, ArchetypeId>> —
    // the inner HashMap had ~5 entries per archetype; a flat array is
    // ~10× faster per lookup (array index vs hash + bucket + equals).
    private final Map<ArchetypeId, ArchetypeId[]> addEdges = new HashMap<>();
    private final Map<ArchetypeId, ArchetypeId[]> removeEdges = new HashMap<>();
    private int maxComponentId = 16; // grows as needed
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
        var edges = addEdges.get(source);
        int id = added.id();
        if (edges != null && id < edges.length && edges[id] != null) {
            return edges[id];
        }
        // Cache miss — compute and store.
        var targetId = source.with(added);
        getOrCreate(targetId);
        ensureEdgeCapacity(id);
        if (edges == null || id >= edges.length) {
            edges = java.util.Arrays.copyOf(
                edges != null ? edges : new ArchetypeId[0],
                Math.max(id + 1, maxComponentId));
            addEdges.put(source, edges);
        }
        edges[id] = targetId;
        return targetId;
    }

    public ArchetypeId removeEdge(ArchetypeId source, ComponentId removed) {
        var edges = removeEdges.get(source);
        int id = removed.id();
        if (edges != null && id < edges.length && edges[id] != null) {
            return edges[id];
        }
        var targetId = source.without(removed);
        getOrCreate(targetId);
        ensureEdgeCapacity(id);
        if (edges == null || id >= edges.length) {
            edges = java.util.Arrays.copyOf(
                edges != null ? edges : new ArchetypeId[0],
                Math.max(id + 1, maxComponentId));
            removeEdges.put(source, edges);
        }
        edges[id] = targetId;
        return targetId;
    }

    private void ensureEdgeCapacity(int id) {
        if (id >= maxComponentId) maxComponentId = id + 8;
    }

    public List<Archetype> findMatching(Set<ComponentId> required) {
        // Fast path: check existing cache entry without allocating a
        // Set.copyOf defensive copy. The cache is keyed by immutable sets,
        // so an equals() check against the caller's (possibly mutable) set
        // is safe for lookup. Only on a miss do we copy + insert.
        var cached = findMatchingCache.get(required);
        if (cached != null) return cached;
        var immutableKey = Set.copyOf(required);
        return findMatchingCache.computeIfAbsent(immutableKey, key -> {
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

    /**
     * Remove all archetypes and their entities. Edge caches and query
     * caches are cleared. The registry and storage factory are preserved.
     */
    public void clear() {
        archetypes.clear();
        addEdges.clear();
        removeEdges.clear();
        findMatchingCache.clear();
        generation++;
    }
}
