package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-entity systems can take an {@code Entity} parameter to receive the
 * current iteration entity's handle. The value is the same handle returned
 * by {@code chunk.entity(slot)} — a stable reference safe to pass to
 * Commands.despawn / add / remove / set.
 *
 * Entity injection does NOT introduce cross-entity access: the handle is
 * always the entity whose components the system is reading/writing via
 * normal per-entity dispatch.
 */
class EntityInjectionTest {

    record Position(float x, float y) {}
    record Health(int hp) {}

    static class Reaper {
        final List<Entity> seen = new ArrayList<>();

        @System
        void reap(@Read Health h, Entity self, Commands cmds) {
            seen.add(self);
            if (h.hp() <= 0) {
                cmds.despawn(self);
            }
        }
    }

    @Test
    void reaperDespawnsDeadEntities() {
        var reaper = new Reaper();
        var world = World.builder().addSystem(reaper).build();
        var alive = world.spawn(new Health(5));
        var dead  = world.spawn(new Health(0));
        var alsoAlive = world.spawn(new Health(10));

        world.tick();

        assertEquals(3, reaper.seen.size(), "reaper must see every entity once");
        assertTrue(world.isAlive(alive));
        assertFalse(world.isAlive(dead));
        assertTrue(world.isAlive(alsoAlive));
    }

    @Test
    void entityHandleMatchesSpawnedHandle() {
        var reaper = new Reaper();
        var world = World.builder().addSystem(reaper).build();
        var e1 = world.spawn(new Health(1));
        var e2 = world.spawn(new Health(2));

        world.tick();

        // The handles the system saw must match the handles spawn returned,
        // not some fresh entity. Order is archetype-chunk order which happens
        // to match spawn order for a single archetype.
        assertTrue(reaper.seen.contains(e1));
        assertTrue(reaper.seen.contains(e2));
    }

    static class MoverAndReaper {
        @System
        void move(@Read Position p, @Write Mut<Health> h, Entity self, Commands cmds) {
            // Entity injection works alongside writes and service params.
            if (p.x() > 100) {
                cmds.despawn(self);
            } else {
                h.set(new Health(h.get().hp() - 1));
            }
        }
    }

    @Test
    void entityInjectionWorksWithWrites() {
        var world = World.builder().addSystem(MoverAndReaper.class).build();
        var inBounds = world.spawn(new Position(50, 0), new Health(10));
        var outOfBounds = world.spawn(new Position(150, 0), new Health(10));

        world.tick();

        assertTrue(world.isAlive(inBounds));
        assertEquals(9, world.getComponent(inBounds, Health.class).hp());
        assertFalse(world.isAlive(outOfBounds));
    }

    static class EntityOnly {
        int count;
        @System
        void tag(@Read Position p, Entity self) {
            count++;
        }
    }

    @Test
    void entityInjectionParticipatesInNormalParallelism() {
        var world = World.builder()
            .addSystem(EntityOnly.class)
            .addSystem(Reaper.class)   // writes Health via cmds; reads Health
            .build();

        // Both should run in the same tick. The reader (EntityOnly) and the
        // reaper (Read Health) operate on different component sets so the
        // scheduler can run them in parallel — Entity injection adds no new
        // conflicts because it's a framework-provided handle.
        world.spawn(new Position(1, 1), new Health(5));
        world.spawn(new Position(2, 2), new Health(0));

        world.tick();
        // No exception, both systems ran, reaper actually despawned one.
        assertEquals(1, world.entityCount());
    }
}
