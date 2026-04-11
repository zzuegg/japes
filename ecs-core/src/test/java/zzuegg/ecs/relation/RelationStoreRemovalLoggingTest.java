package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link RelationStore#remove} drives the
 * {@link PairRemovalLog} so {@code RemovedRelations<T>} observers can
 * see every dropped pair and its last value.
 *
 * <p>The store is the only code that atomically knows "this pair
 * existed, now it doesn't," so the removal log must be fed from inside
 * {@code remove} — not from the caller.
 */
class RelationStoreRemovalLoggingTest {

    record Distance(float meters) {}

    @Test
    void removeOfUnobservedPairIsNoOp() {
        var store = new RelationStore<>(Distance.class);
        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);

        store.set(src, tgt, new Distance(5f), 5L);
        // No consumer registered — log must stay empty.
        store.remove(src, tgt, /*tick=*/10L);

        assertTrue(store.removalLog().snapshot(0L).isEmpty());
    }

    @Test
    void removeRecordsEntryWhenConsumerPresent() {
        var store = new RelationStore<>(Distance.class);
        store.removalLog().registerConsumer();

        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);
        store.set(src, tgt, new Distance(5f), 5L);

        store.remove(src, tgt, /*tick=*/10L);

        var snap = store.removalLog().snapshot(0L);
        assertEquals(1, snap.size());
        assertEquals(src, snap.getFirst().source());
        assertEquals(tgt, snap.getFirst().target());
        assertEquals(new Distance(5f), snap.getFirst().value(),
            "log must carry the last known value so observers can inspect it");
        assertEquals(10L, snap.getFirst().tick());
    }

    @Test
    void removeOfMissingPairDoesNotLog() {
        var store = new RelationStore<>(Distance.class);
        store.removalLog().registerConsumer();

        // Pair never existed.
        store.remove(Entity.of(1, 0), Entity.of(2, 0), 10L);

        assertTrue(store.removalLog().snapshot(0L).isEmpty());
    }

    @Test
    void legacyTicklessRemoveStillWorks() {
        // The original three-arg-set tests use store.remove(src, tgt)
        // without a tick. Keep that path green for the non-tracking
        // unit tests until we migrate them all.
        var store = new RelationStore<>(Distance.class);
        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);
        store.set(src, tgt, new Distance(5f));

        var prev = store.remove(src, tgt);

        assertEquals(new Distance(5f), prev);
        assertNull(store.get(src, tgt));
    }
}
