package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unified delta observer — one logical observer reacts to added, changed,
 * AND removed entities each tick, all from a single {@link EntitySet} and a
 * single {@code applyChanges()} call.
 *
 * <p>Three components ({@code State}, {@code Health}, {@code Mana}) are
 * tracked in one EntitySet. The driver mutates each component on a
 * different 10% slice per tick (offset cursors — 30% of entities touched
 * total), spawns 1%, and despawns 1%. The observer calls
 * {@code applyChanges()} once and iterates the three delta views — added,
 * changed, removed — regardless of how many component types triggered the
 * change.
 *
 * <p><b>Why this favours Zay-ES:</b> japes needs <em>seven</em> separate
 * system registrations to cover the same ground:
 * <ul>
 *   <li>1 {@code @Filter(Added)}  — for new entities</li>
 *   <li>3 {@code @Filter(Changed)} — one per component type</li>
 *   <li>3 {@code RemovedComponents<T>} — one per component type</li>
 * </ul>
 * Each system has its own watermark, dirty-list walk, and execution-plan
 * slot.  The scheduler, stage-graph traversal, and dirty-list pruning
 * overhead scales with the number of systems, while Zay-ES pays a fixed
 * cost of one {@code applyChanges()} call no matter how many component
 * types the EntitySet tracks.
 *
 * <p>Counterpart: {@code UnifiedDeltaBenchmark} in ecs-benchmark.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ZayEsUnifiedDeltaBenchmark {

    public record State(int value) implements EntityComponent {}
    public record Health(int hp) implements EntityComponent {}
    public record Mana(int points) implements EntityComponent {}

    @Param({"10000", "100000"})
    int entityCount;

    static final int CHANGE_FRACTION = 10; // 10% per component per tick

    EntityData data;
    EntitySet observerSet;
    List<EntityId> handles;
    int stateCursor, healthCursor, manaCursor;
    long sumAdded, sumChanged, sumRemoved;

    @Setup(Level.Iteration)
    public void setup() {
        data = new DefaultEntityData();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = data.createEntity();
            data.setComponents(id,
                new State(i),
                new Health(1_000),
                new Mana(0));
            handles.add(id);
        }
        // One EntitySet tracks all three components — the Zay-ES sweet spot.
        observerSet = data.getEntities(State.class, Health.class, Mana.class);
        observerSet.applyChanges(); // prime past the initial spawn
        stateCursor = 0;
        healthCursor = entityCount / 3;
        manaCursor = 2 * entityCount / 3;
        sumAdded = sumChanged = sumRemoved = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (observerSet != null) observerSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        int n = handles.size();
        int addCount = Math.max(1, entityCount / 100);
        int changeCount = Math.max(1, entityCount / CHANGE_FRACTION);
        int removeCount = Math.max(1, entityCount / 100);

        // 1. Spawn 1% new entities (all three components).
        for (int i = 0; i < addCount; i++) {
            var id = data.createEntity();
            data.setComponents(id,
                new State(n + i),
                new Health(1_000),
                new Mana(0));
            handles.add(id);
        }

        // 2. Mutate 10% per component via offset rotating cursors.
        //    Three disjoint slices — 30% of entities touched total.
        for (int i = 0; i < changeCount; i++) {
            var id = handles.get(stateCursor % handles.size());
            stateCursor++;
            var cur = data.getComponent(id, State.class);
            if (cur != null) data.setComponent(id, new State(cur.value() + 1));
        }
        for (int i = 0; i < changeCount; i++) {
            var id = handles.get(healthCursor % handles.size());
            healthCursor++;
            var cur = data.getComponent(id, Health.class);
            if (cur != null) data.setComponent(id, new Health(cur.hp() - 1));
        }
        for (int i = 0; i < changeCount; i++) {
            var id = handles.get(manaCursor % handles.size());
            manaCursor++;
            var cur = data.getComponent(id, Mana.class);
            if (cur != null) data.setComponent(id, new Mana(cur.points() + 1));
        }

        // 3. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            var id = handles.removeFirst();
            data.removeEntity(id);
        }

        // Observer: ONE applyChanges(), three delta views.
        // The EntitySet merges mutations across all three component types
        // into a single changed view — no per-component overhead.
        observerSet.applyChanges();
        for (Entity e : observerSet.getAddedEntities()) {
            sumAdded++;
            bh.consume(e.get(State.class));
        }
        for (Entity e : observerSet.getChangedEntities()) {
            sumChanged++;
            bh.consume(e.get(State.class));
        }
        for (Entity e : observerSet.getRemovedEntities()) {
            sumRemoved++;
            bh.consume(e.getId());
        }

        bh.consume(sumAdded);
        bh.consume(sumChanged);
        bh.consume(sumRemoved);
    }
}
