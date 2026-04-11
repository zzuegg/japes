package zzuegg.ecs.command;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that relation mutations recorded via {@link Commands}
 * flush through {@link CommandProcessor} into the correct
 * {@code World.setRelation} / {@code World.removeRelation} calls,
 * preserving insertion order (so {@code set → remove} lands as a
 * clean removal, not the other way around).
 */
class CommandProcessorRelationTest {

    record Position(float x, float y) {}
    record Targeting(int power) {}

    @Test
    void setRelationCommandAppliesTheRelation() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        var cmds = new Commands();
        cmds.setRelation(alice, bob, new Targeting(42));

        CommandProcessor.process(cmds.drain(), world);

        assertEquals(new Targeting(42),
            world.getRelation(alice, bob, Targeting.class).orElseThrow());
    }

    @Test
    void removeRelationCommandDropsThePair() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.setRelation(alice, bob, new Targeting(42));

        var cmds = new Commands();
        cmds.removeRelation(alice, bob, Targeting.class);
        CommandProcessor.process(cmds.drain(), world);

        assertTrue(world.getRelation(alice, bob, Targeting.class).isEmpty());
    }

    @Test
    void insertionOrderIsPreserved() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        var cmds = new Commands();
        cmds.setRelation(alice, bob, new Targeting(42));
        cmds.removeRelation(alice, bob, Targeting.class);

        CommandProcessor.process(cmds.drain(), world);

        assertTrue(world.getRelation(alice, bob, Targeting.class).isEmpty(),
            "set then remove must land in order — the final state has no pair");
    }

    @Test
    void commandOnDeadSourceIsSkippedNotThrown() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.despawn(alice);

        var cmds = new Commands();
        cmds.setRelation(alice, bob, new Targeting(42));

        // Matches the despawn/add/remove path — deferred mutations on
        // entities that died between enqueue and flush silently skip,
        // they do not throw.
        assertDoesNotThrow(() -> CommandProcessor.process(cmds.drain(), world));
    }

    @Test
    void removeCommandOnMissingPairIsNoOp() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        var cmds = new Commands();
        cmds.removeRelation(alice, bob, Targeting.class);

        assertDoesNotThrow(() -> CommandProcessor.process(cmds.drain(), world));
    }
}
