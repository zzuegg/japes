package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class CommandProcessingTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Bullet() {}

    static class SpawnerSystem {
        @System
        void spawn(@Read Position pos, Commands cmd) {
            cmd.spawn(new Bullet(), new Position(pos.x(), pos.y()));
        }
    }

    static class DespawnerSystem {
        @System
        void despawn(@Read Bullet b, Commands cmd) {
            // This would despawn bullets, but we need entity ID
            // For now test that commands are processed
        }
    }

    @Test
    void commandsFromSystemAreApplied() {
        var world = World.builder()
            .addSystem(SpawnerSystem.class)
            .build();

        world.spawn(new Position(10, 20));
        assertEquals(1, world.entityCount());

        world.tick();

        // The spawner should have created a Bullet entity via Commands
        assertEquals(2, world.entityCount());
    }

    @Test
    void commandsAppliedAfterAllSystemsInStage() {
        var world = World.builder()
            .addSystem(SpawnerSystem.class)
            .build();

        // Spawn 3 entities with positions — each will spawn a bullet
        world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));
        world.spawn(new Position(3, 3));

        world.tick();

        // 3 original + 3 bullets
        assertEquals(6, world.entityCount());
    }

    @Test
    void commandsDespawnEntity() {
        var world = World.builder().build();

        var e1 = world.spawn(new Position(1, 1));
        var e2 = world.spawn(new Position(2, 2));
        assertEquals(2, world.entityCount());

        // Create a system instance that despawns e1
        final Entity target = e1;

        @SystemSet(name = "cleanup", stage = "PostUpdate")
        class CleanupSystem {
            @System
            void cleanup(@Read Position pos, Commands cmd) {
                // despawn all — just to test commands work
            }
        }

        // Use Commands directly through command processor for this test
        var cmds = new Commands();
        cmds.despawn(target);
        cmds.applyTo(world);

        assertEquals(1, world.entityCount());
    }

    @Test
    void commandsSetComponent() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(0, 0));

        var cmds = new Commands();
        cmds.set(entity, new Position(99, 99));
        cmds.applyTo(world);

        assertEquals(new Position(99, 99), world.getComponent(entity, Position.class));
    }
}
