package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunIfTest {

    record Counter(int value) {}

    static final AtomicInteger invocationCount = new AtomicInteger(0);
    static boolean enabled = true;

    static class ConditionalSystems {
        boolean isEnabled() {
            return enabled;
        }

        @System
        @RunIf("isEnabled")
        void conditionalSystem(@Write Mut<Counter> counter) {
            invocationCount.incrementAndGet();
            counter.set(new Counter(counter.get().value() + 1));
        }
    }

    @Test
    void systemRunsWhenConditionTrue() {
        enabled = true;
        invocationCount.set(0);
        var world = World.builder()
            .addSystem(ConditionalSystems.class)
            .build();

        world.spawn(new Counter(0));
        world.tick();

        assertEquals(1, invocationCount.get());
    }

    @Test
    void systemSkippedWhenConditionFalse() {
        enabled = false;
        invocationCount.set(0);
        var world = World.builder()
            .addSystem(ConditionalSystems.class)
            .build();

        world.spawn(new Counter(0));
        world.tick();

        assertEquals(0, invocationCount.get());
    }

    @Test
    void conditionCheckedEveryTick() {
        invocationCount.set(0);
        var world = World.builder()
            .addSystem(ConditionalSystems.class)
            .build();

        world.spawn(new Counter(0));

        enabled = true;
        world.tick();
        assertEquals(1, invocationCount.get());

        enabled = false;
        world.tick();
        assertEquals(1, invocationCount.get()); // no change

        enabled = true;
        world.tick();
        assertEquals(2, invocationCount.get());
    }
}
