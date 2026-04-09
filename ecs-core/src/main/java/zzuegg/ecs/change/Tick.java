package zzuegg.ecs.change;

import java.util.concurrent.atomic.AtomicLong;

public final class Tick {

    private final AtomicLong value = new AtomicLong(0);

    public long current() {
        return value.get();
    }

    public long advance() {
        return value.incrementAndGet();
    }
}
