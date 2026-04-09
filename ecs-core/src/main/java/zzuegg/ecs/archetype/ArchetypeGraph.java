package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;

import java.util.*;

public final class ArchetypeGraph {

    private final ComponentRegistry registry;
    private final int chunkCapacity;
    private final Map<ArchetypeId, Archetype> archetypes = new HashMap<>();
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> addEdges = new HashMap<>();
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> removeEdges = new HashMap<>();

    public ArchetypeGraph(ComponentRegistry registry, int chunkCapacity) {
        this.registry = registry;
        this.chunkCapacity = chunkCapacity;
    }

    public Archetype getOrCreate(ArchetypeId id) {
        return archetypes.computeIfAbsent(id, k -> new Archetype(k, registry, chunkCapacity));
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
        var result = new ArrayList<Archetype>();
        for (var entry : archetypes.entrySet()) {
            if (entry.getKey().components().containsAll(required)) {
                result.add(entry.getValue());
            }
        }
        return result;
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
