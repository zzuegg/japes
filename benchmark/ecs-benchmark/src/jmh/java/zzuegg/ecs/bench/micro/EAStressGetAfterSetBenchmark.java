package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Stress-tests escape analysis for get()-after-set() patterns on Mut.
 *
 * After set(), Mut.get() returns the pending record. The JIT must
 * scalar-replace both the record passed to set() and the record
 * reconstructed by get(). These patterns probe whether EA handles
 * increasingly complex get/set interleaving.
 *
 * Run with: -prof gc to observe gc.alloc.rate.norm (bytes/op).
 * Zero allocation means EA succeeded.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EAStressGetAfterSetBenchmark {

    record Health(int hp) {}

    // --- Pattern 1: get before set (baseline, normal read-modify-write) ---
    static class GetBeforeSetSystem {
        @System
        void run(@Write Mut<Health> h) {
            h.set(new Health(h.get().hp() - 1));
        }
    }

    // --- Pattern 2: get AFTER set (reconstructs from pending) ---
    static class GetAfterSetSystem {
        public static Blackhole bh;
        @System
        void run(@Write Mut<Health> h) {
            h.set(new Health(10));
            var check = h.get().hp();
            bh.consume(check);
        }
    }

    // --- Pattern 3: set, get, set again (two writes, one read between) ---
    static class SetGetSetSystem {
        @System
        void run(@Write Mut<Health> h) {
            h.set(new Health(10));
            h.set(new Health(h.get().hp() + 1));
        }
    }

    // --- Pattern 4: conditional with double get ---
    static class ConditionalDoubleGetSystem {
        @System
        void run(@Write Mut<Health> h) {
            if (h.get().hp() > 50) {
                h.set(new Health(h.get().hp() - 1));
            }
        }
    }

    // --- Pattern 5: loop with repeated get/set ---
    static class LoopGetSetSystem {
        @System
        void run(@Write Mut<Health> h) {
            for (int i = 0; i < 3; i++) {
                h.set(new Health(h.get().hp() + 1));
            }
        }
    }

    @Param({"10000"})
    int entityCount;

    World getBeforeSetWorld;
    World getAfterSetWorld;
    World setGetSetWorld;
    World conditionalDoubleGetWorld;
    World loopGetSetWorld;

    @Setup(Level.Trial)
    public void setup() {
        getBeforeSetWorld = World.builder().addSystem(GetBeforeSetSystem.class).build();
        getAfterSetWorld = World.builder().addSystem(GetAfterSetSystem.class).build();
        setGetSetWorld = World.builder().addSystem(SetGetSetSystem.class).build();
        conditionalDoubleGetWorld = World.builder().addSystem(ConditionalDoubleGetSystem.class).build();
        loopGetSetWorld = World.builder().addSystem(LoopGetSetSystem.class).build();

        for (int i = 0; i < entityCount; i++) {
            getBeforeSetWorld.spawn(new Health(100));
            getAfterSetWorld.spawn(new Health(100));
            setGetSetWorld.spawn(new Health(100));
            conditionalDoubleGetWorld.spawn(new Health(100));
            loopGetSetWorld.spawn(new Health(100));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        getBeforeSetWorld.close();
        getAfterSetWorld.close();
        setGetSetWorld.close();
        conditionalDoubleGetWorld.close();
        loopGetSetWorld.close();
    }

    @Benchmark
    public void p1_getBeforeSet() {
        getBeforeSetWorld.tick();
    }

    @Benchmark
    public void p2_getAfterSet(Blackhole bh) {
        GetAfterSetSystem.bh = bh;
        getAfterSetWorld.tick();
    }

    @Benchmark
    public void p3_setGetSet() {
        setGetSetWorld.tick();
    }

    @Benchmark
    public void p4_conditionalDoubleGet() {
        conditionalDoubleGetWorld.tick();
    }

    @Benchmark
    public void p5_loopGetSet() {
        loopGetSetWorld.tick();
    }
}
