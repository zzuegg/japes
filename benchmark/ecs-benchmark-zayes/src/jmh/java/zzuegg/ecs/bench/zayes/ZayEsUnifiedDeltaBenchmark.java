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
 * This is the workload shape Zay-ES is designed for: the EntitySet maintains
 * all three delta views (added / changed / removed) internally, and one
 * synchronization call exposes them with zero marginal cost per additional
 * delta type.
 *
 * <p>Per tick the driver:
 * <ol>
 *   <li>Spawns 1% new entities         (exercises {@code getAddedEntities})</li>
 *   <li>Mutates 10% existing entities  (exercises {@code getChangedEntities})</li>
 *   <li>Despawns 1% oldest entities    (exercises {@code getRemovedEntities})</li>
 * </ol>
 * Then a single observer calls {@code applyChanges()} and iterates all three
 * delta views.
 *
 * <p><b>Why this favours Zay-ES:</b> japes needs three separate system
 * registrations ({@code @Filter(Added)}, {@code @Filter(Changed)},
 * {@code RemovedComponents<T>}), each with its own watermark, dirty-list
 * walk, and execution-plan slot. The scheduler, stage-graph traversal, and
 * dirty-list pruning overhead is paid even though only one logical observer
 * is running.  Zay-ES pays none of that — one {@code applyChanges()} call
 * and three cheap view iterations.
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

    @Param({"10000", "100000"})
    int entityCount;

    EntityData data;
    EntitySet observerSet;
    List<EntityId> handles;
    int changeCursor;
    long sumAdded, sumChanged, sumRemoved;

    @Setup(Level.Iteration)
    public void setup() {
        data = new DefaultEntityData();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = data.createEntity();
            data.setComponents(id, new State(i));
            handles.add(id);
        }
        observerSet = data.getEntities(State.class);
        observerSet.applyChanges(); // prime past the initial spawn
        changeCursor = 0;
        sumAdded = sumChanged = sumRemoved = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (observerSet != null) observerSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        int addCount = Math.max(1, entityCount / 100);
        int changeCount = Math.max(1, entityCount / 10);
        int removeCount = Math.max(1, entityCount / 100);

        // 1. Spawn 1% new entities.
        for (int i = 0; i < addCount; i++) {
            var id = data.createEntity();
            data.setComponents(id, new State(handles.size() + i));
            handles.add(id);
        }

        // 2. Mutate 10% via rotating cursor.
        for (int i = 0; i < changeCount; i++) {
            int idx = changeCursor % handles.size();
            changeCursor++;
            var id = handles.get(idx);
            var cur = data.getComponent(id, State.class);
            if (cur != null) {
                data.setComponent(id, new State(cur.value() + 1));
            }
        }

        // 3. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            var id = handles.removeFirst();
            data.removeEntity(id);
        }

        // Observer: one applyChanges(), three delta views — the Zay-ES sweet spot.
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
