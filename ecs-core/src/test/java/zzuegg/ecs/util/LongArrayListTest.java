package zzuegg.ecs.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Primitive-long growable list — a minimal alternative to
 * {@code ArrayList<Long>} for hot-path bulk collection where
 * autoboxing the long shows up in profiling.
 *
 * <p>Used primarily by relation-heavy systems that want to collect
 * a batch of entity ids during a {@code forEachPairLong} walk and
 * act on them after iteration without paying for {@code Long}
 * wrapper objects.
 */
class LongArrayListTest {

    @Test
    void newListIsEmpty() {
        var list = new LongArrayList();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }

    @Test
    void addAppendsValues() {
        var list = new LongArrayList();
        list.add(10L);
        list.add(20L);
        list.add(30L);

        assertEquals(3, list.size());
        assertFalse(list.isEmpty());
        assertEquals(10L, list.get(0));
        assertEquals(20L, list.get(1));
        assertEquals(30L, list.get(2));
    }

    @Test
    void growsBeyondInitialCapacity() {
        var list = new LongArrayList();
        for (int i = 0; i < 100; i++) list.add((long) i * 7);
        assertEquals(100, list.size());
        for (int i = 0; i < 100; i++) {
            assertEquals((long) i * 7, list.get(i),
                "value at index " + i + " must survive grow");
        }
    }

    @Test
    void clearResetsSize() {
        var list = new LongArrayList();
        list.add(1L);
        list.add(2L);
        list.clear();
        assertEquals(0, list.size());
        assertTrue(list.isEmpty());
    }

    @Test
    void toArrayReturnsDefensiveCopy() {
        var list = new LongArrayList();
        list.add(5L);
        list.add(6L);
        long[] snap = list.toArray();
        assertArrayEquals(new long[]{5L, 6L}, snap);

        // Mutating the snapshot must not touch the list.
        snap[0] = 999L;
        assertEquals(5L, list.get(0));
    }

    @Test
    void rawArrayExposesBackingStoreForHotPathScans() {
        // The hot-path contract: callers can walk the raw backing
        // array directly, bounded by size(), without going through
        // get(int). Same contract as TargetSlice.targetIdsArray().
        var list = new LongArrayList();
        list.add(100L);
        list.add(200L);
        list.add(300L);

        long[] raw = list.rawArray();
        assertTrue(raw.length >= list.size());
        long sum = 0;
        for (int i = 0, n = list.size(); i < n; i++) sum += raw[i];
        assertEquals(600L, sum);
    }

    @Test
    void getThrowsForOutOfRange() {
        var list = new LongArrayList();
        list.add(42L);
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(1));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(100));
    }

    @Test
    void initialCapacityConstructor() {
        var list = new LongArrayList(64);
        assertEquals(0, list.size());
        assertTrue(list.rawArray().length >= 64);
    }

    @Test
    void initialCapacityRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> new LongArrayList(-1));
    }
}
