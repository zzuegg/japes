package zzuegg.ecs.change;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChangeTrackerTest {

    @Test
    void tickIncrementsMonotonically() {
        var tick = new Tick();
        assertEquals(0, tick.current());
        tick.advance();
        assertEquals(1, tick.current());
        tick.advance();
        assertEquals(2, tick.current());
    }

    @Test
    void newSlotHasAddedTick() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 5);
        assertEquals(5, tracker.addedTick(0));
    }

    @Test
    void markChangedUpdatesTick() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 3);
        assertEquals(3, tracker.changedTick(0));
    }

    @Test
    void isAddedSinceDetectsNewEntities() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 5);
        assertTrue(tracker.isAddedSince(0, 4));
        assertFalse(tracker.isAddedSince(0, 5));
        assertFalse(tracker.isAddedSince(0, 6));
    }

    @Test
    void isChangedSinceDetectsModifications() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 5);
        assertTrue(tracker.isChangedSince(0, 4));
        assertFalse(tracker.isChangedSince(0, 5));
    }

    @Test
    void swapRemoveMovesTickData() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 10);
        tracker.markAdded(2, 3);
        tracker.markChanged(2, 20);

        tracker.swapRemove(0, 3);

        assertEquals(3, tracker.addedTick(0));
        assertEquals(20, tracker.changedTick(0));
    }

    @Test
    void capacity() {
        var tracker = new ChangeTracker(1024);
        assertEquals(1024, tracker.capacity());
    }
}
