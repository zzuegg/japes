package zzuegg.ecs.change;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickInitialValueTest {

    @Test
    void initialTickIsOneSoZeroCanMeanNever() {
        // 0 must be reserved as the "never added/changed" sentinel:
        // - long[] default is 0
        // - ChangeTracker.swapRemove zeroes out freed slots
        // If a real tick ever takes the value 0, isAddedSince(slot, 0) returns
        // false for that entity and "never added" becomes indistinguishable
        // from "added at tick 0". The cleanest fix is to reserve 0.
        var tick = new Tick();
        assertEquals(1L, tick.current(),
            "initial tick must be 1 so 0 stays reserved as a 'never' sentinel");
    }

    @Test
    void advanceStillMonotonic() {
        var tick = new Tick();
        assertEquals(1L, tick.current());
        assertEquals(2L, tick.advance());
        assertEquals(2L, tick.current());
        assertEquals(3L, tick.advance());
    }
}
