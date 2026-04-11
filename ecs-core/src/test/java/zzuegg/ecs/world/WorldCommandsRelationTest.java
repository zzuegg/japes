package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: a regular @System body calls
 * {@link Commands#setRelation(Entity, Entity, Record)} and the
 * deferred mutation lands at the stage boundary exactly like any
 * other command.
 */
class WorldCommandsRelationTest {

    record Position(float x, float y) {}
    record Marker(int tag) {}     // used to drive per-entity iteration
    record Targeting(int power) {}

    static Entity TARGET_HOLDER;

    public static class RelationWriter {
        @System
        public void retarget(@Read Marker marker, Entity self, Commands cmds) {
            cmds.setRelation(self, TARGET_HOLDER, new Targeting(marker.tag()));
        }
    }

    @Test
    void commandsSetRelationAppliesAtStageBoundary() {
        var world = World.builder()
            .addSystem(RelationWriter.class)
            .build();

        var alice   = world.spawn(new Position(0f, 0f), new Marker(10));
        var bob     = world.spawn(new Position(1f, 1f), new Marker(20));
        TARGET_HOLDER = world.spawn(new Position(9f, 9f));

        world.tick();

        assertEquals(new Targeting(10),
            world.getRelation(alice, TARGET_HOLDER, Targeting.class).orElseThrow());
        assertEquals(new Targeting(20),
            world.getRelation(bob, TARGET_HOLDER, Targeting.class).orElseThrow());
    }

    public static class RelationRemover {
        @System
        public void untarget(@Read Marker marker, Entity self, Commands cmds) {
            cmds.removeRelation(self, TARGET_HOLDER, Targeting.class);
        }
    }

    @Test
    void commandsRemoveRelationDropsAtStageBoundary() {
        var world = World.builder()
            .addSystem(RelationRemover.class)
            .build();

        var alice = world.spawn(new Position(0f, 0f), new Marker(10));
        TARGET_HOLDER = world.spawn(new Position(9f, 9f));
        world.setRelation(alice, TARGET_HOLDER, new Targeting(77));

        world.tick();

        assertTrue(world.getRelation(alice, TARGET_HOLDER, Targeting.class).isEmpty());
    }
}
