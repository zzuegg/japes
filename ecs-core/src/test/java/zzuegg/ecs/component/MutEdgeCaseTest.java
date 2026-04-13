package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.change.ChangeTracker;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case tests for Mut<T> — probes current/pending split, valueTracked
 * suppression semantics, flush idempotency, and null handling.
 */
class MutEdgeCaseTest {

    record Health(int hp) {}
    record Position(float x, float y, float z) {}
    record Named(String name) {}

    // ---------------------------------------------------------------
    // 1. get() after set() after get() — pending value visible
    // ---------------------------------------------------------------

    @Test
    void getReturnsPendingAfterSet() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        assertEquals(new Health(100), mut.get());
        mut.set(new Health(42));
        assertEquals(new Health(42), mut.get());
    }

    @Test
    void multipleSetCallsLastOneWins() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        mut.set(new Health(1));
        mut.set(new Health(2));
        mut.set(new Health(3));
        assertEquals(new Health(3), mut.get());
    }

    // ---------------------------------------------------------------
    // 2. Multi-field records (3 fields)
    // ---------------------------------------------------------------

    @Test
    void threeFieldRecordSetGetFlush() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Position(1f, 2f, 3f), 0, tracker, 5, false);
        mut.set(new Position(4f, 5f, 6f));
        assertEquals(new Position(4f, 5f, 6f), mut.get());
        var flushed = mut.flush();
        assertEquals(new Position(4f, 5f, 6f), flushed);
        assertEquals(5, tracker.changedTick(0));
    }

    // ---------------------------------------------------------------
    // 3. Non-primitive record fields — String (not SoA-eligible)
    // ---------------------------------------------------------------

    @Test
    void nonPrimitiveRecordSetGetFlush() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Named("Alice"), 0, tracker, 5, false);
        mut.set(new Named("Bob"));
        assertEquals(new Named("Bob"), mut.get());
        var flushed = mut.flush();
        assertEquals(new Named("Bob"), flushed);
        assertEquals(5, tracker.changedTick(0));
    }

    // ---------------------------------------------------------------
    // 4. flush() when not changed returns current
    // ---------------------------------------------------------------

    @Test
    void flushWhenNotChangedReturnsCurrent() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        var flushed = mut.flush();
        assertEquals(new Health(100), flushed);
        // Changed tick should NOT be updated
        assertEquals(0, tracker.changedTick(0));
    }

    // ---------------------------------------------------------------
    // 5. flush() idempotency — calling flush() twice should NOT
    //    double-mark the change tracker
    // ---------------------------------------------------------------

    @Test
    void flushCalledTwiceDoesNotDoubleMarkChangeTracker() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.set(new Health(50));
        mut.flush();
        assertEquals(5, tracker.changedTick(0));

        // Reset tick to 0 to detect a second markChanged call
        // We can't reset changedTick directly, so check that flush()
        // doesn't call markChanged again when isChanged should be false
        // after a flush.
        //
        // BUG PROBE: flush() does not reset `changed` to false.
        // So a second flush() will enter the changed path again
        // and call markChanged a second time.
        assertFalse(mut.isChanged(),
            "flush() should reset changed flag so subsequent flush() is idempotent");
    }

    // ---------------------------------------------------------------
    // 6. ValueTracked: set to identical value suppresses change
    // ---------------------------------------------------------------

    @Test
    void valueTrackedSuppressesNoOpSetAndReturnsCurrent() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(100)); // same as original
        var flushed = mut.flush();
        // Should suppress the change
        assertEquals(0, tracker.changedTick(0));
        // flush() should return current value (which equals the original)
        assertEquals(new Health(100), flushed);
    }

    @Test
    void valueTrackedAllowsRealChangeAndResetsFlagAfterFlush() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(50));
        var flushed = mut.flush();
        assertEquals(new Health(50), flushed);
        assertEquals(5, tracker.changedTick(0));
        // After a real change flush, changed should be cleared
        // BUG PROBE: flush() only clears changed on the suppressed path,
        // not on the real-change path.
        assertFalse(mut.isChanged(),
            "flush() should reset changed flag after a real change too");
    }

    @Test
    void valueTrackedNoOpFlushResetsChangedFlag() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(100)); // no-op
        mut.flush();
        // After suppressed flush, changed should be false
        assertFalse(mut.isChanged());
        // And get() should now return the current value
        assertEquals(new Health(100), mut.get());
    }

    // ---------------------------------------------------------------
    // 7. set(null) — edge case
    // ---------------------------------------------------------------

    @Test
    void setNullThenGetReturnsNull() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        mut.set(null);
        assertNull(mut.get(), "get() should return null after set(null)");
    }

    @Test
    void flushAfterSetNullReturnsNull() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.set(null);
        var flushed = mut.flush();
        assertNull(flushed, "flush() should return null after set(null)");
        assertEquals(5, tracker.changedTick(0));
    }

    @Test
    void setNullWithValueTrackedDoesNotNPE() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(null);
        // BUG PROBE: flush() calls original.equals(pending) where pending=null.
        // Record.equals(null) returns false, so this should NOT NPE and should
        // count as a real change. But if there's a latent NPE, this will catch it.
        assertDoesNotThrow(() -> mut.flush(),
            "flush() should not NPE when pending is null with valueTracked");
        // null != Health(100), so this is a real change
        assertEquals(5, tracker.changedTick(0));
    }

    // ---------------------------------------------------------------
    // 8. resetValue() clears pending and changed
    // ---------------------------------------------------------------

    @Test
    void resetValueClearsPendingAndChanged() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        mut.set(new Health(50));
        assertTrue(mut.isChanged());
        mut.resetValue(new Health(200), 1);
        assertFalse(mut.isChanged());
        assertEquals(new Health(200), mut.get());
    }

    // ---------------------------------------------------------------
    // 9. get() after flush() returns flushed value
    // ---------------------------------------------------------------

    @Test
    void getAfterFlushReturnsPendingNotCurrent() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.set(new Health(50));
        mut.flush();
        // After flush, get() should still return the pending value (50),
        // not the original current value (100).
        // BUG PROBE: If flush() doesn't reset changed, get() returns pending (50) — correct.
        // If flush() DOES reset changed (if they fix the above bug), get() returns current (100) — wrong,
        // because current was never updated to the new value.
        // This creates a conflict: flush() needs to either:
        //   a) reset changed AND update current to pending, OR
        //   b) not reset changed (current behavior, but breaks idempotency)
        assertEquals(new Health(50), mut.get(),
            "get() after flush() should return the value that was set, not the old current");
    }
}
