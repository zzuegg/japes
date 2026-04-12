package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unified delta observer — one logical observer reacts to added, changed,
 * AND removed entities each tick.
 *
 * <p>This is the workload shape Zay-ES is designed for: a single
 * {@code EntitySet} gives you added / changed / removed delta views from one
 * {@code applyChanges()} call with zero marginal cost per additional delta
 * type.  In japes the same logical observer requires three separate system
 * methods ({@code @Filter(Added)}, {@code @Filter(Changed)},
 * {@code RemovedComponents<T>}), each with its own watermark, dirty-list
 * walk, and execution-plan slot.  The scheduler, stage-graph traversal, and
 * dirty-list pruning overhead is paid even though only one logical observer
 * is running.
 *
 * <p>Per tick the driver (outside systems):
 * <ol>
 *   <li>Spawns 1% new entities         (visible to {@code @Filter(Added)})</li>
 *   <li>Mutates 10% existing entities  (visible to {@code @Filter(Changed)})</li>
 *   <li>Despawns 1% oldest entities    (visible to {@code RemovedComponents})</li>
 * </ol>
 * Then {@code world.tick()} runs all three observer systems.
 *
 * <p>Counterpart: {@code ZayEsUnifiedDeltaBenchmark} in ecs-benchmark-zayes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class UnifiedDeltaBenchmark {

    public record State(int value) {}

    // --- Counters shared across the three observer systems. ---

    public static final class Counters {
        long added, changed, removed;
    }

    // --- Three system registrations for what Zay-ES does in one EntitySet. ---

    public static final class AddedObserver {
        final Counters counters;
        AddedObserver(Counters counters) { this.counters = counters; }

        @System
        @Filter(value = Added.class, target = State.class)
        void observe(@Read State s) {
            counters.added++;
        }
    }

    public static final class ChangedObserver {
        final Counters counters;
        ChangedObserver(Counters counters) { this.counters = counters; }

        @System
        @Filter(value = Changed.class, target = State.class)
        void observe(@Read State s) {
            counters.changed++;
        }
    }

    public static final class RemovedObserver {
        final Counters counters;
        RemovedObserver(Counters counters) { this.counters = counters; }

        @System
        void observe(RemovedComponents<State> gone) {
            for (var r : gone) {
                counters.removed++;
            }
        }
    }

    @Param({"10000", "100000"})
    int entityCount;

    World world;
    Counters counters;
    List<Entity> handles;
    int changeCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new AddedObserver(counters))
            .addSystem(new ChangedObserver(counters))
            .addSystem(new RemovedObserver(counters))
            .build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(new State(i)));
        }
        // Prime one tick so the observer watermarks are past the initial spawn.
        world.tick();
        changeCursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        int addCount = Math.max(1, entityCount / 100);
        int changeCount = Math.max(1, entityCount / 10);
        int removeCount = Math.max(1, entityCount / 100);

        // 1. Spawn 1% new entities.
        for (int i = 0; i < addCount; i++) {
            handles.add(world.spawn(new State(handles.size() + i)));
        }

        // 2. Mutate 10% via rotating cursor.
        for (int i = 0; i < changeCount; i++) {
            int idx = changeCursor % handles.size();
            changeCursor++;
            var e = handles.get(idx);
            var cur = world.getComponent(e, State.class);
            if (cur != null) {
                world.setComponent(e, new State(cur.value() + 1));
            }
        }

        // 3. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            world.despawn(handles.removeFirst());
        }

        // world.tick() runs all three observer systems — schedule-graph
        // traversal, per-system watermarks, dirty-list walks, and
        // removal-log drain.  This is the overhead Zay-ES avoids with its
        // single applyChanges() model.
        world.tick();
        bh.consume(counters.added);
        bh.consume(counters.changed);
        bh.consume(counters.removed);
    }
}
