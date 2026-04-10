package zzuegg.ecs.command;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.world.World;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandProcessorErrorHandlingTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    @Test
    void addCommandOnDespawnedEntityIsSkipped() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        // Later commands will target this entity after it's already gone.
        var commands = List.<Commands.Command>of(
            new Commands.DespawnCommand(entity),
            new Commands.AddCommand(entity, new Velocity(3, 4))
        );

        // Before: AddCommand called world.addComponent on a dead entity and
        // propagated IllegalArgumentException, aborting the whole frame.
        assertDoesNotThrow(() -> CommandProcessor.process(commands, world));
        assertFalse(world.isAlive(entity));
    }

    @Test
    void removeCommandOnDespawnedEntityIsSkipped() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2), new Velocity(3, 4));
        var commands = List.<Commands.Command>of(
            new Commands.DespawnCommand(entity),
            new Commands.RemoveCommand(entity, Velocity.class)
        );

        assertDoesNotThrow(() -> CommandProcessor.process(commands, world));
    }

    @Test
    void setCommandOnDespawnedEntityIsSkipped() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        var commands = List.<Commands.Command>of(
            new Commands.DespawnCommand(entity),
            new Commands.SetCommand(entity, new Position(9, 9))
        );

        assertDoesNotThrow(() -> CommandProcessor.process(commands, world));
    }

    @Test
    void laterValidCommandsStillApplyAfterDeadTargetSkipped() {
        var world = World.builder().build();
        var dead = world.spawn(new Position(1, 2));
        var live = world.spawn(new Position(7, 8));

        var commands = List.<Commands.Command>of(
            new Commands.DespawnCommand(dead),
            new Commands.AddCommand(dead, new Velocity(1, 1)),  // dead, skip
            new Commands.AddCommand(live, new Velocity(9, 9))   // must still apply
        );

        CommandProcessor.process(commands, world);

        assertTrue(world.isAlive(live));
        // The live entity picked up a Velocity component.
        assertEquals(new Velocity(9, 9), world.getComponent(live, Velocity.class));
    }
}
