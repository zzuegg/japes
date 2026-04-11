package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Changed;
import zzuegg.ecs.system.Filter;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sparse-delta scenario: 10k entities exist, but only 100 (1%) are touched
 * per tick. The observer reacts only to entities whose Health changed.
 *
 * This is the shape change-detection is designed for — per-tick work should
 * scale with the number of dirty entities, not the total entity count.
 *
 * Per tick:
 *   1. Driver damages 100 entities (rotating window) via World.setComponent
 *   2. ChangedObserver (@Filter(Changed, Health)) counts the dirty entities
 *
 * <b>Benchmark fairness note:</b> The japes benchmark body calls
 * {@code world.tick()}, which includes full-tick overhead (event-buffer swap,
 * stage-graph traversal, dirty-list pruning, removal-log GC). The Artemis and
 * Dominion counterparts ({@code ArtemisSparseDeltaBenchmark},
 * {@code DominionSparseDeltaBenchmark}) run a hand-rolled mutation + dirty-bag
 * drain loop with none of that overhead. With a workload of only 100 entities
 * the fixed tick cost is not amortised, so japes numbers are systematically
 * higher than a raw component-read comparison would suggest. The numbers
 * measure the realistic library API cost (full tick), not raw component
 * iteration speed.
 *
 * Compared idiomatically against ZayEsSparseDeltaBenchmark and the
 * bevy-benchmark "sparse_delta" group.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SparseDeltaBenchmark {

    public record Health(int hp) {}

    public static class ChangedObserver {
        public static Blackhole bh;
        public long count;

        @System
        @Filter(value = Changed.class, target = Health.class)
        void observe(@Read Health h) {
            count++;
            if (bh != null) bh.consume(h);
        }
    }

    @Param({"10000"})
    int entityCount;

    static final int BATCH = 100;

    World world;
    List<Entity> handles;
    int cursor;

    @Setup(Level.Iteration)
    public void setup() {
        world = World.builder().addSystem(ChangedObserver.class).build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(new Health(1000)));
        }
        // Prime one tick so the observer's watermark is past the spawn tick.
        world.tick();
        cursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        ChangedObserver.bh = bh;
        // Damage 100 entities via the rotating cursor.
        for (int i = 0; i < BATCH; i++) {
            var e = handles.get(cursor);
            cursor = (cursor + 1) % handles.size();
            var h = world.getComponent(e, Health.class);
            world.setComponent(e, new Health(h.hp() - 1));
        }
        world.tick();
    }
}
