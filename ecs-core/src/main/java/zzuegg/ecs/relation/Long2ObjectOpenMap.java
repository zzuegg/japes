package zzuegg.ecs.relation;

import java.util.Arrays;

/**
 * Minimal primitive long-keyed open-addressing hash map used by
 * {@link RelationStore} for the forward and reverse indices. Two
 * parallel arrays — {@code long[] keys} + {@code Object[] values} —
 * with linear probing and a doubling policy. Much cheaper than
 * {@code HashMap<Entity, T>} because:
 *
 * <ul>
 *   <li>Keys are primitive longs, no {@code Entity.hashCode()}
 *       virtual call and no object equality chain.</li>
 *   <li>No {@code Node} objects — the map is just two arrays.</li>
 *   <li>Bulk iteration is a tight index scan that skips empty
 *       slots, rather than walking a linked-node chain.</li>
 * </ul>
 *
 * <p>Empty-slot convention: {@code values[i] == null} marks an empty
 * slot. {@code keys[i]} is unused when the slot is empty — it may
 * hold any stale value from a prior occupant. This means {@code 0L}
 * is a valid key, which matters because {@code Entity.NULL} is
 * packed as a non-zero id and all real Entity ids are unique.
 *
 * <p>Not thread-safe — the containing {@code RelationStore} is
 * single-writer.
 *
 * @param <V> the value type
 */
final class Long2ObjectOpenMap<V> {

    private static final int INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private Object[] values;
    private int size;
    private int resizeThreshold;

    Long2ObjectOpenMap() {
        this.keys = new long[INITIAL_CAPACITY];
        this.values = new Object[INITIAL_CAPACITY];
        this.size = 0;
        this.resizeThreshold = (int) (INITIAL_CAPACITY * LOAD_FACTOR);
    }

    int size() { return size; }

    boolean isEmpty() { return size == 0; }

    /**
     * Raw backing key array. Package-private so
     * {@link RelationStore#forEachPair} can walk the table without
     * going through a {@link LongObjConsumer} lambda on every live
     * slot. Values in empty slots are undefined — callers must
     * check {@code valuesArray()[i] != null} before using
     * {@code keysArray()[i]}.
     */
    long[] keysArray() { return keys; }

    /** Raw backing value array — same contract as {@link #keysArray()}. */
    Object[] valuesArray() { return values; }

    /** Mask for the current table size (must be power of two). */
    private int mask() { return keys.length - 1; }

    /**
     * Mixing hash — standard splitmix64-style finalizer to avoid the
     * low-bit clustering that plain identity hashing on packed
     * {@code (index, generation)} longs would produce.
     */
    private static int index(long key, int mask) {
        long h = key;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return (int) h & mask;
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        var ks = keys;
        var vs = values;
        int mask = ks.length - 1;
        int idx = index(key, mask);
        while (true) {
            var v = vs[idx];
            if (v == null) return null;
            if (ks[idx] == key) return (V) v;
            idx = (idx + 1) & mask;
        }
    }

    boolean containsKey(long key) {
        return get(key) != null;
    }

    /**
     * Insert or overwrite. Returns the previous value, or
     * {@code null} if this is a new entry.
     */
    @SuppressWarnings("unchecked")
    V put(long key, V value) {
        if (value == null) throw new IllegalArgumentException("null values not allowed");
        var ks = keys;
        var vs = values;
        int mask = ks.length - 1;
        int idx = index(key, mask);
        while (true) {
            var existing = vs[idx];
            if (existing == null) {
                ks[idx] = key;
                vs[idx] = value;
                size++;
                if (size > resizeThreshold) resize();
                return null;
            }
            if (ks[idx] == key) {
                var old = (V) existing;
                vs[idx] = value;
                return old;
            }
            idx = (idx + 1) & mask;
        }
    }

    /**
     * Remove. Returns the removed value, or {@code null} if the key
     * wasn't present. Uses Robin-Hood-style deletion: after removing
     * a slot, walks forward and re-inserts any displaced entries
     * until an empty slot is hit, preserving the probe invariant.
     */
    @SuppressWarnings("unchecked")
    V remove(long key) {
        var ks = keys;
        var vs = values;
        int mask = ks.length - 1;
        int idx = index(key, mask);
        while (true) {
            var v = vs[idx];
            if (v == null) return null;
            if (ks[idx] == key) {
                vs[idx] = null;
                size--;
                // Shift-back to maintain contiguous probe chains.
                int nextIdx = (idx + 1) & mask;
                while (vs[nextIdx] != null) {
                    int desired = index(ks[nextIdx], mask);
                    // Does nextIdx want to be at idx or earlier?
                    if (((nextIdx - desired) & mask) > ((idx - desired) & mask)) {
                        ks[idx] = ks[nextIdx];
                        vs[idx] = vs[nextIdx];
                        vs[nextIdx] = null;
                        idx = nextIdx;
                    }
                    nextIdx = (nextIdx + 1) & mask;
                }
                return (V) v;
            }
            idx = (idx + 1) & mask;
        }
    }

    /** Compute-if-absent shortcut. Returns the resulting value (new or existing). */
    @SuppressWarnings("unchecked")
    V computeIfAbsent(long key, java.util.function.LongFunction<? extends V> factory) {
        var existing = get(key);
        if (existing != null) return existing;
        var created = factory.apply(key);
        put(key, created);
        return created;
    }

    /**
     * Specialized consumer for {@link #forEach}: takes a primitive
     * long key and a generic value, so iteration over the map does
     * not pay for autoboxing the long into a {@code Long} wrapper
     * on every call. {@code BiConsumer<Long, V>} would force
     * boxing at every slot and became visible as the single
     * hottest method in profiling.
     */
    @FunctionalInterface
    interface LongObjConsumer<V> {
        void accept(long key, V value);
    }

    /**
     * Bulk walk over every {@code (key, value)} entry. Skips empty
     * slots via a {@code null} check on {@code values[i]} — no
     * intermediate iterator object, no boxing of the key.
     */
    @SuppressWarnings("unchecked")
    void forEach(LongObjConsumer<V> consumer) {
        var ks = keys;
        var vs = values;
        for (int i = 0; i < ks.length; i++) {
            var v = vs[i];
            if (v != null) consumer.accept(ks[i], (V) v);
        }
    }

    void clear() {
        Arrays.fill(values, null);
        size = 0;
    }

    private void resize() {
        var oldKeys = keys;
        var oldValues = values;
        int newCap = oldKeys.length * 2;
        keys = new long[newCap];
        values = new Object[newCap];
        resizeThreshold = (int) (newCap * LOAD_FACTOR);
        int mask = newCap - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            var v = oldValues[i];
            if (v == null) continue;
            long key = oldKeys[i];
            int idx = index(key, mask);
            while (values[idx] != null) {
                idx = (idx + 1) & mask;
            }
            keys[idx] = key;
            values[idx] = v;
        }
    }
}
