package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Change-detection and removal benchmarks using Zay-ES in its native idiom.
 *
 * Zay-ES exposes change detection via {@link EntitySet#applyChanges()} + the
 * three delta views ({@code getAddedEntities}, {@code getChangedEntities},
 * {@code getRemovedEntities}). A tick looks like:
 *
 * <pre>
 *     set.applyChanges();
 *     for (Entity e : set.getChangedEntities()) { ... }
 *     for (Entity e : set.getRemovedEntities()) { ... }
 * </pre>
 *
 * Counterparts of our JMH benchmarks:
 *   - changedAfterBulkUpdate      ↔ ChangeDetectionBenchmark.changedFilterAllEntitiesDirty
 *   - removedAfterBulkDespawn     ↔ ChangeDetectionBenchmark.removedComponentsDrainAfterBulkDespawn
 *   - fullRoundtripAddChangeRemove — a mixed-workload test that exercises all
 *     three delta views in one measurement, since that's how real Zay-ES code
 *     actually interacts with EntitySet.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ZayEsChangeDetectionBenchmark {

    public record Position(float x, float y, float z) implements EntityComponent {}
    public record Velocity(float dx, float dy, float dz) implements EntityComponent {}

    @Param({"10000"})
    int entityCount;

    // ---------- Changed: every entity dirtied each tick ----------

    EntityData changedData;
    EntitySet changedSet;
    List<EntityId> changedIds;

    @Setup(Level.Trial)
    public void setupChanged() {
        changedData = new DefaultEntityData();
        changedIds = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = changedData.createEntity();
            changedData.setComponents(id,
                new Position(i, i, i),
                new Velocity(1, 1, 1));
            changedIds.add(id);
        }
        changedSet = changedData.getEntities(Position.class, Velocity.class);
        changedSet.applyChanges();
    }

    @TearDown(Level.Trial)
    public void teardownChanged() {
        if (changedSet != null) changedSet.release();
    }

    /**
     * Mimics our "move all + observe changed" pipeline: every entity is written
     * each tick, then applyChanges + iterate the changed view reports every
     * entity as changed. This is the Zay-ES-idiomatic way to react to
     * component mutations.
     */
    @Benchmark
    public void changedAfterBulkUpdate(Blackhole bh) {
        // Writer pass — setComponent on every entity, equivalent to our
        // MoveAll system.
        for (var id : changedIds) {
            var cur = changedData.getComponent(id, Position.class);
            var vel = changedData.getComponent(id, Velocity.class);
            changedData.setComponent(id,
                new Position(cur.x() + vel.dx(), cur.y() + vel.dy(), cur.z() + vel.dz()));
        }
        // Apply the writes and drain the changed view.
        changedSet.applyChanges();
        for (Entity e : changedSet.getChangedEntities()) {
            bh.consume(e.get(Position.class));
        }
    }

    // ---------- Removed: bulk despawn ----------

    /**
     * Per-invocation setup so the measured body sees exactly N removals.
     * Zay-ES's getRemovedEntities drains on each applyChanges, so without a
     * fresh seed the second invocation would observe nothing.
     */
    @State(Scope.Thread)
    public static class RemovedState {
        public EntityData data;
        public EntitySet set;
        public List<EntityId> ids;
    }

    EntityData removedTrialData;
    EntitySet removedTrialSet;

    @Setup(Level.Trial)
    public void setupRemovedTrial() {
        removedTrialData = new DefaultEntityData();
        removedTrialSet = removedTrialData.getEntities(Position.class);
    }

    @TearDown(Level.Trial)
    public void teardownRemovedTrial() {
        if (removedTrialSet != null) removedTrialSet.release();
    }

    @Setup(Level.Invocation)
    public void seedRemoved() {
        // Seed the trial world with N fresh entities for this invocation.
        // Clear prior state first so consecutive invocations start from scratch.
        for (EntityId id : new ArrayList<>(removedIds())) {
            removedTrialData.removeEntity(id);
        }
        removedTrialSet.applyChanges();
        removedIds().clear();
        for (int i = 0; i < entityCount; i++) {
            var id = removedTrialData.createEntity();
            removedTrialData.setComponents(id, new Position(i, i, i));
            removedIds().add(id);
        }
        removedTrialSet.applyChanges(); // prime past the adds
    }

    private final List<EntityId> removedIdsBacking = new ArrayList<>();
    private List<EntityId> removedIds() { return removedIdsBacking; }

    @Benchmark
    public void removedAfterBulkDespawn(Blackhole bh) {
        for (var id : removedIdsBacking) {
            removedTrialData.removeEntity(id);
        }
        removedTrialSet.applyChanges();
        for (Entity e : removedTrialSet.getRemovedEntities()) {
            bh.consume(e.getId());
        }
    }

    // ---------- Full roundtrip: added + changed + removed in one tick ----------

    EntityData roundtripData;
    EntitySet roundtripSet;
    List<EntityId> roundtripIds;
    int roundtripCounter;

    @Setup(Level.Trial)
    public void setupRoundtrip() {
        roundtripData = new DefaultEntityData();
        roundtripIds = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = roundtripData.createEntity();
            roundtripData.setComponents(id,
                new Position(i, i, i), new Velocity(1, 1, 1));
            roundtripIds.add(id);
        }
        roundtripSet = roundtripData.getEntities(Position.class, Velocity.class);
        roundtripSet.applyChanges();
    }

    @TearDown(Level.Trial)
    public void teardownRoundtrip() {
        if (roundtripSet != null) roundtripSet.release();
    }

    /**
     * One realistic Zay-ES tick:
     *   - spawn 1% new entities   (exercises getAddedEntities)
     *   - mutate 10% of live set  (exercises getChangedEntities)
     *   - despawn 1% oldest       (exercises getRemovedEntities)
     *   - applyChanges + iterate each delta view
     *
     * This is the workload shape Zay-ES is actually designed for.
     */
    @Benchmark
    public void fullRoundtripAddChangeRemove(Blackhole bh) {
        int add = Math.max(1, entityCount / 100);
        int change = Math.max(1, entityCount / 10);
        int remove = Math.max(1, entityCount / 100);

        // Spawn new entities.
        for (int i = 0; i < add; i++) {
            var id = roundtripData.createEntity();
            roundtripData.setComponents(id,
                new Position(i, i, i), new Velocity(1, 1, 1));
            roundtripIds.add(id);
        }
        // Mutate a sliding window — avoid hitting the same entities every tick.
        int base = roundtripCounter % Math.max(1, roundtripIds.size() - change);
        for (int i = 0; i < change; i++) {
            var id = roundtripIds.get(base + i);
            var cur = roundtripData.getComponent(id, Position.class);
            if (cur == null) continue;
            roundtripData.setComponent(id,
                new Position(cur.x() + 0.1f, cur.y() + 0.1f, cur.z() + 0.1f));
        }
        // Despawn the oldest entries.
        for (int i = 0; i < remove && !roundtripIds.isEmpty(); i++) {
            var id = roundtripIds.removeFirst();
            roundtripData.removeEntity(id);
        }
        roundtripCounter++;

        // Observer pass — the whole point of the idiomatic model.
        roundtripSet.applyChanges();
        for (Entity e : roundtripSet.getAddedEntities()) {
            bh.consume(e.get(Position.class));
        }
        for (Entity e : roundtripSet.getChangedEntities()) {
            bh.consume(e.get(Position.class));
        }
        for (Entity e : roundtripSet.getRemovedEntities()) {
            bh.consume(e.getId());
        }
    }
}
