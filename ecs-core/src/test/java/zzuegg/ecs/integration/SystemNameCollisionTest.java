package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SystemNameCollisionTest {

    record Health(int hp) {}
    record Mana(int mp) {}

    static final AtomicInteger healthUpdates = new AtomicInteger(0);
    static final AtomicInteger manaUpdates = new AtomicInteger(0);

    static class HealthSystem {
        @System
        void update(@Write Mut<Health> health) {
            health.set(new Health(health.get().hp() + 1));
            healthUpdates.incrementAndGet();
        }
    }

    static class ManaSystem {
        @System
        void update(@Write Mut<Mana> mana) {
            mana.set(new Mana(mana.get().mp() + 1));
            manaUpdates.incrementAndGet();
        }
    }

    @Test
    void twoSystemsWithSameMethodNameBothRun() {
        healthUpdates.set(0);
        manaUpdates.set(0);

        var world = World.builder()
            .addSystem(HealthSystem.class)
            .addSystem(ManaSystem.class)
            .build();

        world.spawn(new Health(100), new Mana(50));
        world.tick();

        assertEquals(1, healthUpdates.get());
        assertEquals(1, manaUpdates.get());
    }

    @Test
    void bothSystemsModifyCorrectComponent() {
        var world = World.builder()
            .addSystem(HealthSystem.class)
            .addSystem(ManaSystem.class)
            .build();

        var entity = world.spawn(new Health(100), new Mana(50));
        world.tick();

        assertEquals(new Health(101), world.getComponent(entity, Health.class));
        assertEquals(new Mana(51), world.getComponent(entity, Mana.class));
    }
}
