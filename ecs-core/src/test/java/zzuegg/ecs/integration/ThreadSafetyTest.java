package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSafetyTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Health(int hp) {}
    record DeltaTime(float dt) {}

    // Two independent systems that write different components — should run in parallel
    static class IndependentWriteSystems {
        @System
        void movePositions(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }

        @System
        void damageHealth(@Write Mut<Health> health) {
            health.set(new Health(health.get().hp() - 1));
        }
    }

    @Test
    void multiThreadedProducesSameResultAsSingleThreaded() {
        for (int run = 0; run < 20; run++) {
            // Single-threaded reference
            var stWorld = World.builder()
                .addResource(new DeltaTime(0.016f))
                .addSystem(IndependentWriteSystems.class)
                .executor(Executors.singleThreaded())
                .build();

            // Multi-threaded
            var mtWorld = World.builder()
                .addResource(new DeltaTime(0.016f))
                .addSystem(IndependentWriteSystems.class)
                .executor(Executors.multiThreaded())
                .build();

            var stEntities = new zzuegg.ecs.entity.Entity[100];
            var mtEntities = new zzuegg.ecs.entity.Entity[100];

            for (int i = 0; i < 100; i++) {
                stEntities[i] = stWorld.spawn(
                    new Position(i, i), new Velocity(1, 2), new Health(100));
                mtEntities[i] = mtWorld.spawn(
                    new Position(i, i), new Velocity(1, 2), new Health(100));
            }

            for (int tick = 0; tick < 50; tick++) {
                stWorld.tick();
                mtWorld.tick();
            }

            for (int i = 0; i < 100; i++) {
                var stPos = stWorld.getComponent(stEntities[i], Position.class);
                var mtPos = mtWorld.getComponent(mtEntities[i], Position.class);
                assertEquals(stPos, mtPos, "Position mismatch at entity " + i + " on run " + run);

                var stHp = stWorld.getComponent(stEntities[i], Health.class);
                var mtHp = mtWorld.getComponent(mtEntities[i], Health.class);
                assertEquals(stHp, mtHp, "Health mismatch at entity " + i + " on run " + run);
            }
        }
    }

    @Test
    void multiThreadedStressTest100kEntities() {
        var world = World.builder()
            .addResource(new DeltaTime(0.016f))
            .addSystem(IndependentWriteSystems.class)
            .executor(Executors.fixed(8))
            .build();

        for (int i = 0; i < 100_000; i++) {
            world.spawn(new Position(0, 0), new Velocity(1, 1), new Health(1000));
        }

        // Run 10 ticks — should not crash or corrupt data
        for (int tick = 0; tick < 10; tick++) {
            assertDoesNotThrow(world::tick);
        }

        assertEquals(100_000, world.entityCount());
    }

    // Multiple readers of the same component should run in parallel safely
    static class MultipleReaders {
        @System
        void readA(@Read Position pos) {}

        @System
        void readB(@Read Position pos) {}
    }

    @Test
    void multipleReadersRunConcurrently() {
        var world = World.builder()
            .addSystem(MultipleReaders.class)
            .executor(Executors.fixed(4))
            .build();

        for (int i = 0; i < 1000; i++) {
            world.spawn(new Position(i, i));
        }

        for (int tick = 0; tick < 100; tick++) {
            assertDoesNotThrow(world::tick);
        }
    }
}
