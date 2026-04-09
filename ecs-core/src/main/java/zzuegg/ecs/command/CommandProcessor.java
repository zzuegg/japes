package zzuegg.ecs.command;

import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class CommandProcessor {

    private CommandProcessor() {}

    public static void process(List<Commands.Command> commands, World world) {
        // Group consecutive spawn commands by archetype signature for bulk allocation
        var pending = new ArrayList<Commands.Command>();
        var spawnBatch = new ArrayList<Commands.SpawnCommand>();

        for (var command : commands) {
            if (command instanceof Commands.SpawnCommand spawn) {
                spawnBatch.add(spawn);
            } else {
                // Flush any pending spawn batch before processing other commands
                if (!spawnBatch.isEmpty()) {
                    flushSpawnBatch(spawnBatch, world);
                    spawnBatch.clear();
                }
                processCommand(command, world);
            }
        }

        // Flush remaining spawn batch
        if (!spawnBatch.isEmpty()) {
            flushSpawnBatch(spawnBatch, world);
        }
    }

    private static void flushSpawnBatch(List<Commands.SpawnCommand> batch, World world) {
        // Group by component type signature for bulk allocation
        var groups = new LinkedHashMap<Set<Class<?>>, List<Commands.SpawnCommand>>();
        for (var spawn : batch) {
            Set<Class<?>> signature = new java.util.HashSet<>();
            for (var comp : spawn.components()) {
                signature.add(comp.getClass());
            }
            groups.computeIfAbsent(signature, k -> new ArrayList<>()).add(spawn);
        }

        for (var group : groups.values()) {
            for (var spawn : group) {
                world.spawn(spawn.components());
            }
        }
    }

    private static void processCommand(Commands.Command command, World world) {
        switch (command) {
            case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
            case Commands.DespawnCommand despawn -> {
                try {
                    world.despawn(despawn.entity());
                } catch (IllegalArgumentException ignored) {}
            }
            case Commands.AddCommand add -> world.addComponent(add.entity(), add.component());
            case Commands.RemoveCommand remove -> world.removeComponent(remove.entity(), remove.type());
            case Commands.SetCommand set -> world.setComponent(set.entity(), set.component());
            case Commands.InsertResourceCommand res -> world.setResource(res.resource());
        }
    }
}
