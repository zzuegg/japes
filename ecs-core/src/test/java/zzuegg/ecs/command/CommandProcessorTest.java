package zzuegg.ecs.command;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class CommandProcessorTest {

    record Position(float x, float y) {}

    @Test
    void processSpawnCommand() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2));
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(1, world.entityCount());
    }

    @Test
    void processDespawnCommand() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        var cmds = new Commands();
        cmds.despawn(entity);
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(0, world.entityCount());
    }

    @Test
    void processSetCommand() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(0, 0));
        var cmds = new Commands();
        cmds.set(entity, new Position(5, 5));
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(new Position(5, 5), world.getComponent(entity, Position.class));
    }

    @Test
    void batchSpawnSameArchetype() {
        var world = World.builder().build();
        var cmds = new Commands();
        for (int i = 0; i < 1000; i++) {
            cmds.spawn(new Position(i, i));
        }
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(1000, world.entityCount());
    }

    @Test
    void despawnAlreadyDespawnedIsNoOp() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        world.despawn(entity);
        var cmds = new Commands();
        cmds.despawn(entity);
        assertDoesNotThrow(() -> CommandProcessor.process(cmds.drain(), world));
    }

    @Test
    void commandsPreserveOrder() {
        var cmds = new Commands();
        cmds.spawn(new Position(1, 1));
        cmds.spawn(new Position(2, 2));

        var drained = cmds.drain();
        assertEquals(2, drained.size());
        assertTrue(cmds.isEmpty());
    }
}
