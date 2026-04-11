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

    @Setup(Level.Trial)
    public void createRemovedWorld() {
        // World is created once per trial; entities are re-seeded per-invocation
        // (matching the ZayEsChangeDetectionBenchmark.seedRemoved shape) so the
        // measured body contains only the despawn + drain pass.
        removedWorld = World.builder()
            .addSystem(RemovedSink.class)
            .build();
        victims = new Entity[entityCount];
    }

    @TearDown(Level.Trial)
    public void closeRemovedWorld() {
        if (removedWorld != null) removedWorld.close();
    }

    @Setup(Level.Invocation)
    public void seedRemoved() {
        // Spawn N fresh entities and advance the observer watermark past the
        // spawn tick so the @Benchmark body observes exactly N removals each
        // invocation — not N removals + N re-spawns like the old design did.
        for (int i = 0; i < entityCount; i++) {
            victims[i] = removedWorld.spawn(new Position(i, i, i));
        }
        removedWorld.tick(); // prime the observer watermark past the adds
    }

    @Benchmark
    public void removedComponentsDrainAfterBulkDespawn(Blackhole bh) {
        RemovedSink.bh = bh;
        // Despawn every entity → all positions flow through the removal log.
        for (var e : victims) {
            removedWorld.despawn(e);
        }
        // Tick: runs RemovedSink which drains the removal log.
        removedWorld.tick();
    }
}
