package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class DeterminismTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    static class MoveSystems {
        @System
        void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void sameInputProducesSameOutput() {
        for (int run = 0; run < 10; run++) {
            var world = World.builder()
                .addResource(new DeltaTime(0.016f))
                .addSystem(MoveSystems.class)
                .build();

            var e1 = world.spawn(new Position(0, 0), new Velocity(100, 200));
            var e2 = world.spawn(new Position(50, 50), new Velocity(-10, -20));

            for (int i = 0; i < 100; i++) {
                world.tick();
            }

            var p1 = world.getComponent(e1, Position.class);
            var p2 = world.getComponent(e2, Position.class);

            assertEquals(160f, p1.x(), 0.1f);
            assertEquals(320f, p1.y(), 0.1f);
            assertEquals(34f, p2.x(), 0.1f);
            assertEquals(18f, p2.y(), 0.1f);
        }
    }
}
