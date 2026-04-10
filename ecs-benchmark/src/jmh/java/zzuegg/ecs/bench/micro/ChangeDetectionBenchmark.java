package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.RemovedComponents;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Benchmarks for the functionality added this session: @Filter(Changed)
 * per-entity change detection and RemovedComponents<T> service parameter.
 * Compared against equivalent Bevy benchmarks in bevy-benchmark.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ChangeDetectionBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}

    // ---------- @Filter(Changed) ----------

    static class MoveAll {
        @System
        void move(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy(), cur.z() + v.dz()));
        }
    }

    public static class ChangedObserver {
        public static Blackhole bh;
        public long seen;

        @System
        @Filter(value = Changed.class, target = Position.class)
        void observe(@Read Position p) {
            seen++;
            if (bh != null) bh.consume(p);
        }
    }

    @Param({"10000"})
    int entityCount;

    World changedWorld;

    @Setup(Level.Trial)
    public void setup() {
        // Mover writes every Position → observer sees every entity via Changed.
        changedWorld = World.builder()
            .addSystem(MoveAll.class)
            .addSystem(ChangedObserver.class)
            .build();
        for (int i = 0; i < entityCount; i++) {
            changedWorld.spawn(new Position(i, i, i), new Velocity(1, 1, 1));
        }
    }

    @Benchmark
    public void changedFilterAllEntitiesDirty(Blackhole bh) {
        ChangedObserver.bh = bh;
        changedWorld.tick();
    }

    // ---------- RemovedComponents<T> ----------

    public static class RemovedSink {
        public static Blackhole bh;
        public long seen;

        @System
        void drain(RemovedComponents<Position> gone) {
            for (var r : gone) {
                seen++;
                if (bh != null) bh.consume(r.value());
            }
        }
    }

    World removedWorld;
    Entity[] victims;

    @Setup(Level.Iteration)
    public void seedRemoved() {
        // Fresh world per iteration so the invocation body stays amortisable —
        // we measure the RemoveAll→tick→drain cycle end-to-end, not just the
        // observer pass.
        removedWorld = World.builder()
            .addSystem(RemovedSink.class)
            .build();
        victims = new Entity[entityCount];
        for (int i = 0; i < entityCount; i++) {
            victims[i] = removedWorld.spawn(new Position(i, i, i));
        }
        // Prime the observer past the spawn tick.
        removedWorld.tick();
    }

    @Benchmark
    public void removedComponentsDrainAfterBulkDespawn(Blackhole bh) {
        RemovedSink.bh = bh;
        // Despawn every entity → all positions flow through the removal log.
        for (var e : victims) {
            removedWorld.despawn(e);
        }
        removedWorld.tick();
        // Re-seed so the next invocation has something to despawn.
        for (int i = 0; i < entityCount; i++) {
            victims[i] = removedWorld.spawn(new Position(i, i, i));
        }
        removedWorld.tick();
    }
}
