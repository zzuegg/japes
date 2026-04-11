package zzuegg.ecs.command;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class Commands {

    public sealed interface Command permits SpawnCommand, DespawnCommand, AddCommand, RemoveCommand, SetCommand, InsertResourceCommand, SetRelationCommand, RemoveRelationCommand {}
    public record SpawnCommand(Record... components) implements Command {}
    public record DespawnCommand(Entity entity) implements Command {}
    public record AddCommand(Entity entity, Record component) implements Command {}
    public record RemoveCommand(Entity entity, Class<? extends Record> type) implements Command {}
    public record SetCommand(Entity entity, Record component) implements Command {}
    public record InsertResourceCommand(Object resource) implements Command {}
    public record SetRelationCommand(Entity source, Entity target, Record value) implements Command {}
    public record RemoveRelationCommand(Entity source, Entity target, Class<? extends Record> type) implements Command {}

    private List<Command> buffer = new ArrayList<>();

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

    public <T extends Record> void setRelation(Entity source, Entity target, T value) {
        buffer.add(new SetRelationCommand(source, target, value));
    }

    public <T extends Record> void removeRelation(Entity source, Entity target, Class<T> type) {
        buffer.add(new RemoveRelationCommand(source, target, type));
    }

    public List<Command> drain() {
        // Swap in a fresh buffer and hand the old one to the caller. Avoids
        // the per-flush List.copyOf allocation the previous implementation
        // paid for an immutable snapshot we don't actually need — the caller
        // just iterates once and drops the list.
        if (buffer.isEmpty()) return List.of();
        var out = buffer;
        buffer = new ArrayList<>();
        return out;
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }
}
