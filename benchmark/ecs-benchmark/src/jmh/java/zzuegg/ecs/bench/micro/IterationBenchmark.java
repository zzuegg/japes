package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class IterationBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}

    // Read-only systems consume individual FIELDS (not the whole record)
    // via Blackhole. This prevents DCE on the loaded values while letting
    // escape analysis scalar-replace the record object itself — which is
    // what real game code does (nobody stores a raw Position in a
    // collection inside a hot loop; they read p.x(), p.y(), p.z() and
    // compute with the floats).
    //
    // An earlier version consumed the whole record: bh.consume(pos). That
    // forced the record onto the heap and penalised SoA storage (which
    // reconstructs the record per read). Field-level consumption matches
    // the real usage pattern and measures the actual per-field access cost.
    static class SingleComponentSystem {
        public static Blackhole bh;
        @System
        void iterate(@Read Position pos) {
            bh.consume(pos.x());
            bh.consume(pos.y());
            bh.consume(pos.z());
        }
    }

    static class TwoComponentSystem {
        public static Blackhole bh;
        @System
        void iterate(@Read Position pos, @Read Velocity vel) {
            bh.consume(pos.x());
            bh.consume(pos.y());
            bh.consume(pos.z());
            bh.consume(vel.dx());
            bh.consume(vel.dy());
            bh.consume(vel.dz());
        }
    }

    static class WriteSystem {
        @System
        void iterate(@Read Velocity vel, @Write Mut<Position> pos) {
            var p = pos.get();
            pos.set(new Position(p.x() + vel.dx(), p.y() + vel.dy(), p.z() + vel.dz()));
        }
    }

    @Param({"1000", "10000", "100000"})
    int entityCount;

    World singleCompWorld;
    World twoCompWorld;
    World writeWorld;

    @Setup
    public void setup() {
        singleCompWorld = World.builder().addSystem(SingleComponentSystem.class).build();
        twoCompWorld = World.builder().addSystem(TwoComponentSystem.class).build();
        writeWorld = World.builder().addSystem(WriteSystem.class).build();

        for (int i = 0; i < entityCount; i++) {
            singleCompWorld.spawn(new Position(i, i, i));
            twoCompWorld.spawn(new Position(i, i, i), new Velocity(1, 1, 1));
            writeWorld.spawn(new Position(i, i, i), new Velocity(1, 1, 1));
        }
    }

    @Benchmark
    public void iterateSingleComponent(Blackhole bh) {
        SingleComponentSystem.bh = bh;
        singleCompWorld.tick();
    }

    @Benchmark
    public void iterateTwoComponents(Blackhole bh) {
        TwoComponentSystem.bh = bh;
        twoCompWorld.tick();
    }

    @Benchmark
    public void iterateWithWrite() { writeWorld.tick(); }
}
