package zzuegg.ecs.command;

import zzuegg.ecs.world.World;

import java.util.List;

public final class CommandProcessor {

    private CommandProcessor() {}

    public static void process(List<Commands.Command> commands, World world) {
        for (var command : commands) {
            processCommand(command, world);
        }
    }

    private static void processCommand(Commands.Command command, World world) {
        switch (command) {
            case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
            case Commands.DespawnCommand despawn -> {
                if (world.isAlive(despawn.entity())) {
                    world.despawn(despawn.entity());
                }
            }
            case Commands.AddCommand add -> {
                if (world.isAlive(add.entity())) {
                    world.addComponent(add.entity(), add.component());
                }
            }
            case Commands.RemoveCommand remove -> {
                if (world.isAlive(remove.entity())) {
                    world.removeComponent(remove.entity(), remove.type());
                }
            }
            case Commands.SetCommand set -> {
                if (world.isAlive(set.entity())) {
                    world.setComponent(set.entity(), set.component());
                }
            }
            case Commands.InsertResourceCommand res -> world.setResource(res.resource());
        }
    }
}
