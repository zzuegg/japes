package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Stress-tests escape analysis (EA) for systems with multiple @Write params.
 *
 * Each benchmark variant exercises a different multi-write pattern that the
 * tier-1 generator supports (up to 4 component params). The goal is to expose
 * EA failures: situations where HiddenMut instances, intermediate Record
 * allocations, or flushed values escape to the heap.
 *
 * Run with -prof gc to measure gc.alloc.rate.norm (bytes allocated per op).
 * Compare with -XX:-EliminateAllocations to calculate the EA elision rate:
 *
 *   EA_rate = 1 - (alloc_normal / alloc_no_EA)
 *
 * Ideal: gc.alloc.rate.norm ~0 B/op with EA enabled for all variants.
 *
 * Usage:
 *   ./gradlew :benchmark:ecs-benchmark:jmhJar
 *   java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-*-jmh.jar \
 *     "EAStressMultiWrite" -f 1 -wi 10 -i 5 -w 2s -r 2s -p entityCount=10000 -prof gc
 *
 * To measure without EA (baseline for calculating EA rate):
 *   java --enable-preview -XX:-EliminateAllocations \
 *     -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-*-jmh.jar \
 *     "EAStressMultiWrite" -f 1 -wi 10 -i 5 -w 2s -r 2s -p entityCount=10000 -prof gc
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EAStressMultiWriteBenchmark {

    // ---- Component types: small all-primitive records (EA-friendly shape) ----

    record CompA(float x, float y) {}
    record CompB(float u, float v) {}
    record CompC(float p, float q) {}
    record CompD(float r, float s) {}

    // ======================================================================
    // Variant 1: Two @Write params -- dual HiddenMut
    //
    // Tests whether the JIT can scalar-replace BOTH HiddenMut instances and
    // the intermediate Record allocations from get()/set() on each.
    // ======================================================================

    static class DualWriteSystem {
        @System
        void run(@Write Mut<CompA> a, @Write Mut<CompB> b) {
            var av = a.get();
            a.set(new CompA(av.x() + 1f, av.y() + 1f));
            var bv = b.get();
            b.set(new CompB(bv.u() + 1f, bv.v() + 1f));
        }
    }

    // ======================================================================
    // Variant 2: Mixed read + two writes
    //
    // @Read A does not need a Mut wrapper, but the two @Write params do.
    // Tests that the read path doesn't perturb EA of the write wrappers.
    // ======================================================================

    static class MixedReadWriteSystem {
        @System
        void run(@Read CompA a, @Write Mut<CompB> b, @Write Mut<CompC> c) {
            b.set(new CompB(a.x() + b.get().u(), a.y() + b.get().v()));
            c.set(new CompC(a.x() * c.get().p(), a.y() * c.get().q()));
        }
    }

    // ======================================================================
    // Variant 3: Four @Write params -- generator limit
    //
    // Pushes the tier-1 generator to its maximum of 4 component params, all
    // writable. Four simultaneous HiddenMut instances is the hardest case
    // for EA: the JIT must track 4 virtual objects each with multiple fields.
    // ======================================================================

    static class QuadWriteSystem {
        @System
        void run(@Write Mut<CompA> a, @Write Mut<CompB> b,
                 @Write Mut<CompC> c, @Write Mut<CompD> d) {
            a.set(new CompA(a.get().x() + 1f, a.get().y() + 1f));
            b.set(new CompB(b.get().u() + 1f, b.get().v() + 1f));
            c.set(new CompC(c.get().p() + 1f, c.get().q() + 1f));
            d.set(new CompD(d.get().r() + 1f, d.get().s() + 1f));
        }
    }

    // ======================================================================
    // Variant 4: Cross-write -- read from one Mut, write to another
    //
    // b.set(new B(a.get().x(), a.get().y())) -- the new B record is
    // constructed from data read via a.get() (which itself returns a
    // record). If a.get() materialises a record that the JIT cannot
    // scalar-replace because it feeds into b.set(), both the read-side
    // and write-side records may escape.
    // ======================================================================

    static class CrossWriteSystem {
        @System
        void run(@Write Mut<CompA> a, @Write Mut<CompB> b) {
            var av = a.get();
            b.set(new CompB(av.x(), av.y()));
            var bv = b.get();   // reads the value we just set
            a.set(new CompA(bv.u(), bv.v()));
        }
    }

    // ======================================================================
    // Variant 5: Identity write -- set same value back
    //
    // This exercises the flush() value-tracking optimisation path. If the
    // record written back equals the original, flush() should suppress the
    // change. But the get()/set() dance still creates intermediate records
    // that EA must elide.
    // ======================================================================

    static class IdentityWriteSystem {
        @System
        void run(@Write Mut<CompA> a, @Write Mut<CompB> b) {
            a.set(a.get());     // no-op write
            b.set(b.get());     // no-op write
        }
    }

    // ---- World setup ----

    @Param({"10000"})
    int entityCount;

    World dualWriteWorld;
    World mixedReadWriteWorld;
    World quadWriteWorld;
    World crossWriteWorld;
    World identityWriteWorld;

    @Setup(Level.Trial)
    public void setup() {
        dualWriteWorld = World.builder()
            .addSystem(DualWriteSystem.class)
            .build();

        mixedReadWriteWorld = World.builder()
            .addSystem(MixedReadWriteSystem.class)
            .build();

        quadWriteWorld = World.builder()
            .addSystem(QuadWriteSystem.class)
            .build();

        crossWriteWorld = World.builder()
            .addSystem(CrossWriteSystem.class)
            .build();

        identityWriteWorld = World.builder()
            .addSystem(IdentityWriteSystem.class)
            .build();

        for (int i = 0; i < entityCount; i++) {
            float f = i;
            dualWriteWorld.spawn(new CompA(f, f), new CompB(f, f));
            mixedReadWriteWorld.spawn(new CompA(f, f), new CompB(f, f), new CompC(f, f));
            quadWriteWorld.spawn(new CompA(f, f), new CompB(f, f), new CompC(f, f), new CompD(f, f));
            crossWriteWorld.spawn(new CompA(f, f), new CompB(f, f));
            identityWriteWorld.spawn(new CompA(f, f), new CompB(f, f));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (dualWriteWorld != null) dualWriteWorld.close();
        if (mixedReadWriteWorld != null) mixedReadWriteWorld.close();
        if (quadWriteWorld != null) quadWriteWorld.close();
        if (crossWriteWorld != null) crossWriteWorld.close();
        if (identityWriteWorld != null) identityWriteWorld.close();
    }

    // ---- Benchmarks ----

    @Benchmark
    public void dualWrite() {
        dualWriteWorld.tick();
    }

    @Benchmark
    public void mixedReadWrite() {
        mixedReadWriteWorld.tick();
    }

    @Benchmark
    public void quadWrite() {
        quadWriteWorld.tick();
    }

    @Benchmark
    public void crossWrite() {
        crossWriteWorld.tick();
    }

    @Benchmark
    public void identityWrite() {
        identityWriteWorld.tick();
    }
}
