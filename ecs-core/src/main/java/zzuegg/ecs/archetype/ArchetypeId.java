package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import java.util.*;

/**
 * Identity of an archetype — the immutable set of component types that every
 * entity in the archetype has. Used as a key in the {@code ArchetypeGraph}
 * and on {@code EntityLocation}, which means it participates in a HashMap
 * lookup on every {@code World.setComponent} call.
 *
 * This is a final class (not a record) specifically so it can memoise its
 * hash code. The auto-generated record hashCode delegates to the backing
 * {@code SortedSet}, whose {@code AbstractSet.hashCode} walks every element
 * on every call — that was measurably ~18 % of tick time on
 * {@code RealisticTickBenchmark} because {@code setComponent}'s archetype
 * lookup fires once per mutation. Memoising hashCode turns that hot path
 * into an identity-compare + a cached int fetch.
 */
public final class ArchetypeId {

    private final SortedSet<ComponentId> components;
    // Lazily-computed, cached forever — the set is immutable after
    // construction, so the hash is too. Stored as a boxed Integer so "not
    // yet computed" is distinguishable from a legitimate zero hash.
    private Integer cachedHash;

    public ArchetypeId(SortedSet<ComponentId> components) {
        this.components = components;
    }

    public SortedSet<ComponentId> components() {
        return components;
    }

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

    @Override
    public int hashCode() {
        var h = cachedHash;
        if (h == null) {
            h = components.hashCode();
            cachedHash = h;
        }
        return h;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ArchetypeId that)) return false;
        // Fast path: if both sides have a cached hash and they differ, the
        // sets can't be equal. Avoids O(k) TreeSet.equals traversal for the
        // common case of two distinct archetypes colliding in a HashMap.
        if (this.cachedHash != null && that.cachedHash != null
            && !this.cachedHash.equals(that.cachedHash)) return false;
        return this.components.equals(that.components);
    }

    @Override
    public String toString() {
        return "ArchetypeId[components=" + components + "]";
    }
}
