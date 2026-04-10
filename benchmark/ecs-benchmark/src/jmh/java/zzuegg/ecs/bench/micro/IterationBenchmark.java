package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
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

    static class SingleComponentSystem {
        @System
        void iterate(@Read Position pos) {}
    }

    static class TwoComponentSystem {
        @System
        void iterate(@Read Position pos, @Read Velocity vel) {}
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
    public void iterateSingleComponent() { singleCompWorld.tick(); }

    @Benchmark
    public void iterateTwoComponents() { twoCompWorld.tick(); }

    @Benchmark
    public void iterateWithWrite() { writeWorld.tick(); }
}
