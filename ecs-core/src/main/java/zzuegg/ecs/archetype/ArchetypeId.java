package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import java.util.*;

/**
 * Identity of an archetype — the immutable sorted set of component types
 * that every entity in the archetype has. Used as a key in the
 * {@code ArchetypeGraph} and on {@code EntityLocation}, which means it
 * participates in a HashMap lookup on every {@code World.setComponent} call.
 *
 * <p>Backed by a sorted {@code ComponentId[]} flat array instead of a
 * {@code TreeSet}. All operations (contains, hashCode, equals, iteration)
 * are array-based: cache-linear, no pointer chasing, no virtual dispatch
 * on iterator internals, no {@code TreeMap.Entry} overhead.
 *
 * <p>Hash code is computed once and cached (the set is immutable after
 * construction).
 */
public final class ArchetypeId {

    private final ComponentId[] sorted; // sorted by ComponentId.id(), immutable
    private int cachedHash; // 0 = not yet computed (int, not Integer — avoids boxing)
    private boolean hashComputed;

    private ArchetypeId(ComponentId[] sorted) {
        this.sorted = sorted;
    }

    /**
     * Create from an arbitrary (possibly unsorted, possibly duplicated) set.
     */
    public static ArchetypeId of(Set<ComponentId> components) {
        var arr = components.toArray(ComponentId[]::new);
        Arrays.sort(arr);
        return new ArchetypeId(arr);
    }

    /**
     * True if this archetype contains the given component.
     * O(log n) binary search on the sorted backing array.
     */
    public boolean contains(ComponentId id) {
        return Arrays.binarySearch(sorted, id) >= 0;
    }

    /**
     * True if this archetype contains ALL of the given component ids.
     * Uses a merge-scan on both sorted arrays — O(n + m).
     */
    public boolean containsAll(Set<ComponentId> ids) {
        // For small query sets, linear scan + binary search is fine.
        for (var id : ids) {
            if (Arrays.binarySearch(sorted, id) < 0) return false;
        }
        return true;
    }

    /**
     * New archetype with an additional component. O(n) array copy + insert.
     */
    public ArchetypeId with(ComponentId id) {
        int pos = Arrays.binarySearch(sorted, id);
        if (pos >= 0) return this; // already present
        int insertAt = -(pos + 1);
        var result = new ComponentId[sorted.length + 1];
        System.arraycopy(sorted, 0, result, 0, insertAt);
        result[insertAt] = id;
        System.arraycopy(sorted, insertAt, result, insertAt + 1, sorted.length - insertAt);
        return new ArchetypeId(result);
    }

    /**
     * New archetype without a component. O(n) array copy + remove.
     */
    public ArchetypeId without(ComponentId id) {
        int pos = Arrays.binarySearch(sorted, id);
        if (pos < 0) return this; // not present
        var result = new ComponentId[sorted.length - 1];
        System.arraycopy(sorted, 0, result, 0, pos);
        System.arraycopy(sorted, pos + 1, result, pos, sorted.length - pos - 1);
        return new ArchetypeId(result);
    }

    /**
     * The component ids as an unmodifiable list view. Sorted by
     * {@code ComponentId.id()}. The backing array is shared — do not
     * mutate (the List is unmodifiable so callers can't anyway).
     */
    public List<ComponentId> components() {
        return List.of(sorted);
    }

    /**
     * Number of components in this archetype.
     */
    public int size() {
        return sorted.length;
    }

    @Override
    public int hashCode() {
        if (!hashComputed) {
            // Same semantics as AbstractSet.hashCode: sum of element hashes.
            int h = 0;
            for (var id : sorted) h += id.hashCode();
            cachedHash = h;
            hashComputed = true;
        }
        return cachedHash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ArchetypeId that)) return false;
        // Fast path: cached hashes differ → can't be equal.
        if (this.hashComputed && that.hashComputed
            && this.cachedHash != that.cachedHash) return false;
        return Arrays.equals(this.sorted, that.sorted);
    }

    @Override
    public String toString() {
        return "ArchetypeId[components=" + Arrays.toString(sorted) + "]";
    }
}
