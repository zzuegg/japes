package zzuegg.ecs.command;

import zzuegg.ecs.world.World;

import java.util.List;

public final class CommandProcessor {

    private CommandProcessor() {}

    public static void process(List<Commands.Command> commands, World world) {
        for (var command : commands) {
            switch (command) {
                case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
                case Commands.DespawnCommand despawn -> {
                    try {
                        world.despawn(despawn.entity());
                    } catch (IllegalArgumentException ignored) {
                        // Entity already despawned
                    }
                }
                case Commands.AddCommand add -> world.addComponent(add.entity(), add.component());
                case Commands.RemoveCommand remove -> world.removeComponent(remove.entity(), remove.type());
                case Commands.SetCommand set -> world.setComponent(set.entity(), set.component());
                case Commands.InsertResourceCommand res -> world.setResource(res.resource());
            }
        }
    }
}
