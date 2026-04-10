package zzuegg.ecs.bench.valhalla.micro;

import jdk.internal.vm.annotation.LooselyConsistentValue;
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
public class IterationBenchmarkValhalla {

    // Value records + @LooselyConsistentValue — the combination that lets
    // the JVM actually lay these out flat in a backing array. JEP 401 EA
    // only flattens value classes that explicitly opt into relaxed
    // atomicity; without the annotation newNullRestrictedNonAtomicArray
    // returns a non-flat Object[] even though the API accepts the call.
    @LooselyConsistentValue public value record Position(float x, float y, float z) {}
    @LooselyConsistentValue public value record Velocity(float dx, float dy, float dz) {}

    // Read-only systems hand their components to a Blackhole so the JIT
    // can't dead-code-eliminate the per-entity load. Empty method bodies
    // were measuring "nothing" on Valhalla — the JIT saw the value-record
    // load had no observable effect and deleted the whole loop.
    static class SingleComponentSystem {
        public static Blackhole bh;
        @System
        void iterate(@Read Position pos) { bh.consume(pos); }
    }

    static class TwoComponentSystem {
        public static Blackhole bh;
        @System
        void iterate(@Read Position pos, @Read Velocity vel) {
            bh.consume(pos);
            bh.consume(vel);
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
        singleCompWorld = World.builder().addSystem(SingleComponentSystem.class).useGeneratedProcessors(true).build();
        twoCompWorld = World.builder().addSystem(TwoComponentSystem.class).useGeneratedProcessors(true).build();
        writeWorld = World.builder().addSystem(WriteSystem.class).useGeneratedProcessors(true).build();

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
