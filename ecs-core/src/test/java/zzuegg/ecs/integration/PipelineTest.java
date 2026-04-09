package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Gravity(float g) {}
    record DeltaTime(float dt) {}

    @SystemSet(name = "physics", stage = "Update")
    static class PhysicsSystems {
        @System
        void applyGravity(@Write Mut<Velocity> vel, Res<Gravity> g, Res<DeltaTime> dt) {
            var v = vel.get();
            vel.set(new Velocity(v.dx(), v.dy() + g.get().g() * dt.get().dt()));
        }

        @System(after = "applyGravity")
        void integrate(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void gravityIntegrationPipeline() {
        var world = World.builder()
            .addResource(new Gravity(-9.81f))
            .addResource(new DeltaTime(1.0f))
            .addSystem(PhysicsSystems.class)
            .build();

        var entity = world.spawn(new Position(0, 100), new Velocity(10, 0));
        world.tick();

        var pos = world.getComponent(entity, Position.class);
        var vel = world.getComponent(entity, Velocity.class);

        assertEquals(-9.81f, vel.dy(), 0.01f);
        assertEquals(90.19f, pos.y(), 0.01f);
        assertEquals(10f, pos.x(), 0.01f);
    }

    @Test
    void multipleEntitiesProcessedCorrectly() {
        var world = World.builder()
            .addResource(new Gravity(0f))
            .addResource(new DeltaTime(1.0f))
            .addSystem(PhysicsSystems.class)
            .build();

        var e1 = world.spawn(new Position(0, 0), new Velocity(1, 0));
        var e2 = world.spawn(new Position(0, 0), new Velocity(0, 1));

        world.tick();

        assertEquals(new Position(1, 0), world.getComponent(e1, Position.class));
        assertEquals(new Position(0, 1), world.getComponent(e2, Position.class));
    }
}
