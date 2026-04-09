package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import java.util.*;

public record ArchetypeId(SortedSet<ComponentId> components) {

    public static ArchetypeId of(Set<ComponentId> components) {
        return new ArchetypeId(Collections.unmodifiableSortedSet(new TreeSet<>(components)));
    }

    public boolean contains(ComponentId id) {
        return components.contains(id);
    }

    public ArchetypeId with(ComponentId id) {
        var set = new TreeSet<>(components);
        set.add(id);
        return new ArchetypeId(Collections.unmodifiableSortedSet(set));
    }

    public ArchetypeId without(ComponentId id) {
        var set = new TreeSet<>(components);
        set.remove(id);
        return new ArchetypeId(Collections.unmodifiableSortedSet(set));
    }
}
