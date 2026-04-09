package zzuegg.ecs.command;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class Commands {

    public sealed interface Command permits SpawnCommand, DespawnCommand, AddCommand, RemoveCommand, SetCommand, InsertResourceCommand {}
    public record SpawnCommand(Record... components) implements Command {}
    public record DespawnCommand(Entity entity) implements Command {}
    public record AddCommand(Entity entity, Record component) implements Command {}
    public record RemoveCommand(Entity entity, Class<? extends Record> type) implements Command {}
    public record SetCommand(Entity entity, Record component) implements Command {}
    public record InsertResourceCommand(Object resource) implements Command {}

    private final List<Command> buffer = new ArrayList<>();

    public void spawn(Record... components) {
        buffer.add(new SpawnCommand(components));
    }

    public void despawn(Entity entity) {
        buffer.add(new DespawnCommand(entity));
    }

    public void add(Entity entity, Record component) {
        buffer.add(new AddCommand(entity, component));
    }

    public void remove(Entity entity, Class<? extends Record> type) {
        buffer.add(new RemoveCommand(entity, type));
    }

    public void set(Entity entity, Record component) {
        buffer.add(new SetCommand(entity, component));
    }

    public <T> void insertResource(T resource) {
        buffer.add(new InsertResourceCommand(resource));
    }

    public List<Command> drain() {
        var commands = List.copyOf(buffer);
        buffer.clear();
        return commands;
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }
}
