package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.change.ChangeTracker;
import static org.junit.jupiter.api.Assertions.*;

class MutTest {

    record Health(int hp) {}

    @Test
    void getReturnsCurrentValue() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        assertEquals(new Health(100), mut.get());
    }

    @Test
    void setUpdatesValue() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        mut.set(new Health(50));
        assertEquals(new Health(50), mut.get());
    }

    @Test
    void setMarksChangedWithWriteAccessTracking() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.set(new Health(100));
        mut.flush();
        assertEquals(5, tracker.changedTick(0));
    }

    @Test
    void noSetMeansNoChange() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.flush();
        assertEquals(0, tracker.changedTick(0));
    }

    @Test
    void valueTrackedSuppressesNoOpSet() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(100));
        mut.flush();
        assertEquals(0, tracker.changedTick(0));
    }

    @Test
    void valueTrackedAllowsRealChange() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(50));
        mut.flush();
        assertEquals(5, tracker.changedTick(0));
    }

    @Test
    void isChangedReturnsTrueAfterSet() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        assertFalse(mut.isChanged());
        mut.set(new Health(50));
        assertTrue(mut.isChanged());
    }
}
