package zzuegg.ecs.util;

import java.util.Arrays;

/**
 * Primitive-long growable list — a minimal alternative to
 * {@code ArrayList<Long>} for hot-path bulk collection where
 * autoboxing the {@code long} into a {@code Long} wrapper shows up
 * in profiling.
 *
 * <p>Typical usage: accumulating entity ids during a relation-store
 * bulk walk (e.g., {@code forEachPairLong}) and acting on them after
 * iteration. The raw backing array is exposed via {@link #rawArray()}
 * with a length bound of {@link #size()}, so inner loops can walk
 * the store directly without going through {@link #get(int)} and
 * its bounds check.
 *
 * <p>Thread-safety: none. Single-writer by contract.
 */
public final class LongArrayList {

    private static final int DEFAULT_INITIAL_CAPACITY = 8;

    private long[] data;
    private int size;

    public LongArrayList() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    public LongArrayList(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException(
                "initial capacity must be non-negative: " + initialCapacity);
        }
        this.data = new long[Math.max(initialCapacity, 1)];
        this.size = 0;
    }

    public int size() { return size; }

    public boolean isEmpty() { return size == 0; }

    /**
     * Append {@code value}. Grows the backing array with doubling
     * policy if the current capacity is exhausted.
     */
    public void add(long value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = value;
    }

    /**
     * Bounds-checked element access. Callers on a hot path should
     * prefer {@link #rawArray()} + their own index loop.
     */
    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                "index " + index + " out of bounds for size " + size);
        }
        return data[index];
    }

    /**
     * Reset size to zero. The backing array is kept — reusing the
     * same {@link LongArrayList} across iterations avoids re-growing
     * on the second tick.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Return a defensive copy of exactly the live elements. Safe for
     * callers that want to iterate after the list is mutated.
     */
    public long[] toArray() {
        return Arrays.copyOf(data, size);
    }

    /**
     * Raw backing array. Length may exceed {@link #size()}; callers
     * must bound iteration with {@code size()}. Do not mutate the
     * returned array — it is shared with the list.
     */
    public long[] rawArray() {
        return data;
    }
}
