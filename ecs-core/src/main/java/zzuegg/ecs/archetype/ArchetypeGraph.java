package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.*;

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
    private final Map<Set<ComponentId>, List<Archetype>> findMatchingCache = new HashMap<>();

    public ArchetypeGraph(ComponentRegistry registry, int chunkCapacity, ComponentStorage.Factory storageFactory) {
        this.registry = registry;
        this.chunkCapacity = chunkCapacity;
        this.storageFactory = storageFactory;
    }

    public Archetype getOrCreate(ArchetypeId id) {
        var existing = archetypes.get(id);
        if (existing != null) return existing;
        var created = new Archetype(id, registry, chunkCapacity, storageFactory);
        archetypes.put(id, created);
        // New archetype invalidates every cached query — any previously-cached
        // match set could now be missing this archetype.
        findMatchingCache.clear();
        return created;
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
        // same set every tick. Copying the set for the key keeps the map safe
        // against later mutation of the caller's argument.
        var cached = findMatchingCache.get(required);
        if (cached != null) return cached;

        var result = new ArrayList<Archetype>();
        for (var entry : archetypes.entrySet()) {
            if (entry.getKey().components().containsAll(required)) {
                result.add(entry.getValue());
            }
        }
        var immutable = List.copyOf(result);
        findMatchingCache.put(Set.copyOf(required), immutable);
        return immutable;
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
