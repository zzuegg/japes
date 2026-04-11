package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-pair change tracker for one relation type. Parallel to
 * {@link zzuegg.ecs.change.ChangeTracker} but keyed on
 * {@code (source, target)} pair identity instead of archetype slot.
 *
 * <p>Tick semantics match the component-level tracker:
 * <ul>
 *   <li>{@code markAdded} sets the added-tick and appends to the
 *       dirty list (deduplicated).</li>
 *   <li>{@code markChanged} sets the changed-tick and appends to
 *       the dirty list (deduplicated).</li>
 *   <li>{@code pruneDirtyList(minWatermark)} drops entries whose
 *       tick is {@code <= minWatermark}, same rules as
 *       {@code ChangeTracker.pruneDirtyList}.</li>
 * </ul>
 */
class PairChangeTrackerTest {

    @Test
    void newTrackerIsEmpty() {
        var tracker = new PairChangeTracker();
        assertEquals(0, tracker.dirtyCount());
        assertEquals(0, tracker.addedTick(pair(1, 2)));
        assertEquals(0, tracker.changedTick(pair(1, 2)));
    }

    @Test
    void markAddedBumpsTickAndAppendsToDirtyList() {
        var tracker = new PairChangeTracker();
        var key = pair(1, 2);

        tracker.markAdded(key, 5);

        assertEquals(5, tracker.addedTick(key));
        assertEquals(0, tracker.changedTick(key));
        assertEquals(1, tracker.dirtyCount());
    }

    @Test
    void markChangedBumpsTickAndAppendsToDirtyList() {
        var tracker = new PairChangeTracker();
        var key = pair(1, 2);

        tracker.markChanged(key, 5);

        assertEquals(0, tracker.addedTick(key));
        assertEquals(5, tracker.changedTick(key));
        assertEquals(1, tracker.dirtyCount());
    }

    @Test
    void markChangedTwiceSameKeyDoesNotDuplicateInDirtyList() {
        var tracker = new PairChangeTracker();
        var key = pair(1, 2);

        tracker.markChanged(key, 5);
        tracker.markChanged(key, 6);

        assertEquals(6, tracker.changedTick(key));
        assertEquals(1, tracker.dirtyCount(),
            "dedup via membership bitmap must prevent duplicate dirty-list entries");
    }

    @Test
    void pruneDropsExpiredEntries() {
        var tracker = new PairChangeTracker();
        var k1 = pair(1, 2);
        var k2 = pair(1, 3);
        var k3 = pair(1, 4);

        tracker.markChanged(k1, 5);
        tracker.markChanged(k2, 10);
        tracker.markChanged(k3, 15);

        tracker.pruneDirtyList(10);  // drop entries with tick <= 10

        var remaining = new ArrayList<PairKey>();
        tracker.forEachDirty(remaining::add);
        assertEquals(1, remaining.size());
        assertEquals(k3, remaining.getFirst());
    }

    @Test
    void pruneSurvivesWhenNothingExpired() {
        var tracker = new PairChangeTracker();
        var k1 = pair(1, 2);
        tracker.markChanged(k1, 10);
        tracker.pruneDirtyList(5);
        assertEquals(1, tracker.dirtyCount());
    }

    @Test
    void forEachDirtyVisitsEveryLiveKey() {
        var tracker = new PairChangeTracker();
        tracker.markChanged(pair(1, 2), 10);
        tracker.markChanged(pair(1, 3), 11);
        tracker.markChanged(pair(2, 3), 12);

        var seen = new ArrayList<PairKey>();
        tracker.forEachDirty(seen::add);
        assertEquals(3, seen.size());
    }

    @Test
    void removeClearsBothTicksAndRemovesFromDirtyList() {
        var tracker = new PairChangeTracker();
        var key = pair(1, 2);

        tracker.markChanged(key, 10);
        tracker.remove(key);

        assertEquals(0, tracker.addedTick(key));
        assertEquals(0, tracker.changedTick(key));
        assertEquals(0, tracker.dirtyCount());
    }

    private static PairKey pair(int source, int target) {
        return new PairKey(Entity.of(source, 0), Entity.of(target, 0));
    }
}
