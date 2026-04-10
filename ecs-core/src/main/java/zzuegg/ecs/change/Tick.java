package zzuegg.ecs.change;

import java.util.concurrent.atomic.AtomicLong;

public final class Tick {

    // Starts at 1. Tick value 0 is reserved as the "never added/changed" sentinel
    // used by ChangeTracker — long[] default is 0 and swapRemove zeros freed slots,
    // so a real tick must never take that value.
    private final AtomicLong value = new AtomicLong(1);

    public long current() {
        return value.get();
    }

    public long advance() {
        return value.incrementAndGet();
    }
}
