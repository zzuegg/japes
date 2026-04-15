package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;

import java.util.Arrays;

/**
 * Small-set of source {@link Entity}s pointing at one target — the
 * per-target half of a non-fragmenting relation store's reverse
 * index. Flat {@code long[]} backing with linear-scan membership,
 * same rationale as {@link TargetSlice} on the forward side:
 * profiling showed the stock {@code HashSet$HashIterator.next} and
 * {@code HashMap.getNode} paths dominating the hot reverse walk for
 * typical "1–5 hunters per prey" workloads, and a flat array beats
 * the Node-chain walker hands-down at those sizes.
 *
 * <p>Not thread-safe; the containing {@link RelationStore} is
 * single-writer.
 */
final class SourceSlice {

    private static final int INITIAL_CAPACITY = 2;

    private long[] sourceIds;
    private int size;

    SourceSlice() {
        this.sourceIds = new long[INITIAL_CAPACITY];
        this.size = 0;
    }

    int size() { return size; }

    boolean isEmpty() { return size == 0; }

    long[] sourceIdsArray() { return sourceIds; }

    /**
     * Add {@code source} if not already present. Returns {@code true}
     * iff this was a new addition.
     */
    boolean add(Entity source) {
        long sid = source.id();
        var ids = sourceIds;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (ids[i] == sid) return false;
        }
        if (n == ids.length) {
            sourceIds = Arrays.copyOf(ids, ids.length * 2);
            ids = sourceIds;
        }
        ids[n] = sid;
        size = n + 1;
        return true;
    }

    /**
     * Remove {@code source}. Uses swap-remove so ordering is not
     * preserved. Returns {@code true} iff the source was actually
     * present.
     */
    boolean remove(Entity source) {
        long sid = source.id();
        var ids = sourceIds;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (ids[i] == sid) {
                int last = n - 1;
                if (i != last) ids[i] = ids[last];
                ids[last] = 0L;
                size = last;
                return true;
            }
        }
        return false;
    }
}
