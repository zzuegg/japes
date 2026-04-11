package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the non-fragmenting relation store drives its
 * {@link PairChangeTracker} from the write path, matching the
 * component-level change-tracking contract.
 *
 * <p>The store is the only place that knows when a pair is
 * inserted / overwritten / removed, so the tracker must be updated
 * from {@code set} and {@code remove} — not by the caller.
 */
class RelationStoreChangeTrackingTest {

    record Distance(float meters) {}

    @Test
    void newPairSetMarksAdded() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f), /*tick=*/7L);

        var key = new PairKey(alice, bob);
        assertEquals(7L, store.tracker().addedTick(key));
        assertEquals(0L, store.tracker().changedTick(key));
        assertEquals(1, store.tracker().dirtyCount());
    }

    @Test
    void overwriteMarksChangedNotAdded() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f), 7L);
        store.set(alice, bob, new Distance(9f), 12L);

        var key = new PairKey(alice, bob);
        assertEquals(7L,  store.tracker().addedTick(key),
            "addedTick stays pinned to the first insertion");
        assertEquals(12L, store.tracker().changedTick(key),
            "overwrite bumps changedTick");
        assertEquals(1, store.tracker().dirtyCount(),
            "dedup: same pair dirty twice must not duplicate");
    }

    @Test
    void removeClearsTrackerEntry() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f), 7L);
        store.remove(alice, bob);

        var key = new PairKey(alice, bob);
        assertEquals(0L, store.tracker().addedTick(key));
        assertEquals(0L, store.tracker().changedTick(key));
        assertEquals(0, store.tracker().dirtyCount());
    }

    @Test
    void multiplePairsIndependent() {
        var store = new RelationStore<>(Distance.class);
        var alice   = Entity.of(1, 0);
        var bob     = Entity.of(2, 0);
        var charlie = Entity.of(3, 0);

        store.set(alice, bob,     new Distance(1f), 5L);
        store.set(alice, charlie, new Distance(2f), 6L);

        var seen = new ArrayList<PairKey>();
        store.tracker().forEachDirty(seen::add);
        assertEquals(2, seen.size());
    }

}
