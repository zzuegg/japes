package zzuegg.ecs.command;

import zzuegg.ecs.world.World;

import java.util.List;

public final class CommandProcessor {

    private CommandProcessor() {}

    public static void process(List<Commands.Command> commands, World world) {
        // Iterate once, dispatch by type. Each dispatch method short-circuits
        // cleanly on dead targets so the caller doesn't need an extra
        // isAlive probe before the world call.
        for (int i = 0, n = commands.size(); i < n; i++) {
            var command = commands.get(i);
            switch (command) {
                case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
                case Commands.DespawnCommand despawn -> world.despawnIfAlive(despawn.entity());
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
                case Commands.SetRelationCommand rel -> {
                    // Skip if either endpoint died between enqueue and flush —
                    // matches the AddCommand / SetCommand handling above.
                    if (world.isAlive(rel.source()) && world.isAlive(rel.target())) {
                        world.setRelation(rel.source(), rel.target(), rel.value());
                    }
                }
                case Commands.RemoveRelationCommand rel -> {
                    if (world.isAlive(rel.source())) {
                        world.removeRelation(rel.source(), rel.target(), rel.type());
                    }
                }
            }
        }
    }
}
