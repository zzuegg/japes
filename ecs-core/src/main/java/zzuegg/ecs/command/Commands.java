package zzuegg.ecs.command;

import zzuegg.ecs.entity.Entity;

import java.util.Arrays;
import java.util.List;

/**
 * Deferred command buffer. Systems enqueue mutations via the public API
 * methods; the world flushes them at the end of each stage via
 * {@link zzuegg.ecs.world.CommandProcessor}.
 *
 * <h2>Flat-buffer encoding</h2>
 * Commands are stored in three parallel arrays rather than individual
 * heap-allocated Command objects:
 * <ul>
 *   <li>{@code ops[]}  -- opcode per command</li>
 *   <li>{@code ids[]}  -- entity ID(s) packed as longs</li>
 *   <li>{@code refs[]} -- object references (component Record, Class, etc.)</li>
 * </ul>
 * This eliminates one object allocation per command (the Command record)
 * and avoids ArrayList growth copies of boxed Command references.
 * After steady-state, all three arrays are pre-sized from the previous
 * tick's high-water mark and no allocation occurs during command queueing.
 */
public final class Commands {

    // Legacy types kept for API compatibility
    public sealed interface Command permits SpawnCommand, DespawnCommand, AddCommand, RemoveCommand, SetCommand, InsertResourceCommand, SetRelationCommand, RemoveRelationCommand {}
    public record SpawnCommand(Record... components) implements Command {}
    public record DespawnCommand(Entity entity) implements Command {}
    public record AddCommand(Entity entity, Record component) implements Command {}
    public record RemoveCommand(Entity entity, Class<? extends Record> type) implements Command {}
    public record SetCommand(Entity entity, Record component) implements Command {}
    public record InsertResourceCommand(Object resource) implements Command {}
    public record SetRelationCommand(Entity source, Entity target, Record value) implements Command {}
    public record RemoveRelationCommand(Entity source, Entity target, Class<? extends Record> type) implements Command {}

    // Opcodes (public for CommandProcessor which lives in zzuegg.ecs.world)
    public static final int OP_SPAWN          = 0;
    public static final int OP_DESPAWN        = 1;
    public static final int OP_ADD            = 2;
    public static final int OP_REMOVE         = 3;
    public static final int OP_SET            = 4;
    public static final int OP_INSERT_RES     = 5;
    public static final int OP_SET_RELATION   = 6;
    public static final int OP_REMOVE_RELATION = 7;

    private int[] ops;
    private long[] ids;
    private Object[] refs;

    private int count;
    private int idPos;
    private int refPos;

    private static final int INITIAL_CAPACITY = 16;

    public Commands() {
        ops  = new int[INITIAL_CAPACITY];
        ids  = new long[INITIAL_CAPACITY];
        refs = new Object[INITIAL_CAPACITY * 2];
        count = 0;
        idPos = 0;
        refPos = 0;
    }

    // ---- Enqueue API ----

    public void spawn(Record... components) {
        ensureOps(); ensureIds(1); ensureRefs(1);
        ops[count++] = OP_SPAWN;
        ids[idPos++] = 0L;
        refs[refPos++] = components;
    }

    public void despawn(Entity entity) {
        ensureOps(); ensureIds(1);
        ops[count++] = OP_DESPAWN;
        ids[idPos++] = entity.id();
    }

    public void add(Entity entity, Record component) {
        ensureOps(); ensureIds(1); ensureRefs(1);
        ops[count++] = OP_ADD;
        ids[idPos++] = entity.id();
        refs[refPos++] = component;
    }

    public void remove(Entity entity, Class<? extends Record> type) {
        ensureOps(); ensureIds(1); ensureRefs(1);
        ops[count++] = OP_REMOVE;
        ids[idPos++] = entity.id();
        refs[refPos++] = type;
    }

    public void set(Entity entity, Record component) {
        ensureOps(); ensureIds(1); ensureRefs(1);
        ops[count++] = OP_SET;
        ids[idPos++] = entity.id();
        refs[refPos++] = component;
    }

    public <T> void insertResource(T resource) {
        ensureOps(); ensureIds(1); ensureRefs(1);
        ops[count++] = OP_INSERT_RES;
        ids[idPos++] = 0L;
        refs[refPos++] = resource;
    }

    public <T extends Record> void setRelation(Entity source, Entity target, T value) {
        ensureOps(); ensureIds(2); ensureRefs(1);
        ops[count++] = OP_SET_RELATION;
        ids[idPos++] = source.id();
        ids[idPos++] = target.id();
        refs[refPos++] = value;
    }

    public <T extends Record> void removeRelation(Entity source, Entity target, Class<T> type) {
        ensureOps(); ensureIds(2); ensureRefs(1);
        ops[count++] = OP_REMOVE_RELATION;
        ids[idPos++] = source.id();
        ids[idPos++] = target.id();
        refs[refPos++] = type;
    }

    // ---- Drain / query API ----

    /**
     * Apply all buffered commands to the world and reset the buffer.
     * Convenience method equivalent to what happens during tick flush.
     */
    public void applyTo(zzuegg.ecs.world.World world) {
        world.flushCommands(this);
    }

    /** Number of commands currently buffered. */
    public int size() { return count; }

    /** Access the flat arrays directly for fast processing. */
    public int[] ops() { return ops; }
    public long[] ids() { return ids; }
    public Object[] refs() { return refs; }

    /** Reset cursors after the processor has consumed the buffer. */
    public void reset() {
        Arrays.fill(refs, 0, refPos, null);
        count = 0;
        idPos = 0;
        refPos = 0;
    }

    /**
     * Legacy drain that materializes Command objects. Provided for
     * backward compatibility. Prefer {@link zzuegg.ecs.world.CommandProcessor}.
     */
    public List<Command> drain() {
        if (count == 0) return List.of();
        var out = new java.util.ArrayList<Command>(count);
        int ri = 0, ii = 0;
        for (int i = 0; i < count; i++) {
            switch (ops[i]) {
                case OP_SPAWN -> { out.add(new SpawnCommand((Record[]) refs[ri++])); ii++; }
                case OP_DESPAWN -> { out.add(new DespawnCommand(new Entity(ids[ii++]))); }
                case OP_ADD -> { out.add(new AddCommand(new Entity(ids[ii++]), (Record) refs[ri++])); }
                case OP_REMOVE -> { out.add(new RemoveCommand(new Entity(ids[ii++]), asRecordClass(refs[ri++]))); }
                case OP_SET -> { out.add(new SetCommand(new Entity(ids[ii++]), (Record) refs[ri++])); }
                case OP_INSERT_RES -> { out.add(new InsertResourceCommand(refs[ri++])); ii++; }
                case OP_SET_RELATION -> { out.add(new SetRelationCommand(new Entity(ids[ii++]), new Entity(ids[ii++]), (Record) refs[ri++])); }
                case OP_REMOVE_RELATION -> { out.add(new RemoveRelationCommand(new Entity(ids[ii++]), new Entity(ids[ii++]), asRecordClass(refs[ri++]))); }
                default -> throw new IllegalStateException("Unknown op: " + ops[i]);
            }
        }
        reset();
        return out;
    }

    public boolean isEmpty() { return count == 0; }

    // ---- Capacity management ----

    private void ensureOps() {
        if (count == ops.length) ops = Arrays.copyOf(ops, ops.length * 2);
    }

    private void ensureIds(int needed) {
        if (idPos + needed > ids.length) ids = Arrays.copyOf(ids, Math.max(ids.length * 2, idPos + needed));
    }

    private void ensureRefs(int needed) {
        if (refPos + needed > refs.length) refs = Arrays.copyOf(refs, Math.max(refs.length * 2, refPos + needed));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Record> asRecordClass(Object o) { return (Class<? extends Record>) o; }
}
