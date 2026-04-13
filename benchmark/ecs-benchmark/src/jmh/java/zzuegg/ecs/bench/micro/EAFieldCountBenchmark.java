package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Tests whether the number of record fields affects EA in the
 * actual japes system loop. Each variant has a system with @Write Mut
 * and a conditional 10% write rate — the pattern that triggers
 * the cold-branch inlining issue.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class EAFieldCountBenchmark {

    // Records with increasing field count, ALL same type (float)
    public record F1(float a) {}
    public record F2(float a, float b) {}
    public record F3(float a, float b, float c) {}
    public record F4(float a, float b, float c, float d) {}
    public record F5(float a, float b, float c, float d, float e) {}
    public record F6(float a, float b, float c, float d, float e, float f) {}
    public record F8(float a, float b, float c, float d, float e, float f, float g, float h) {}

    // Systems: conditional 10% write, same pattern for all
    public static final class Write1 {
        @System void tick(@Write Mut<F1> m) {
            if (m.get().a() > 0.9f) m.set(new F1(m.get().a() - 0.01f));
        }
    }
    public static final class Write2 {
        @System void tick(@Write Mut<F2> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F2(v.a() - 0.01f, v.b()));
        }
    }
    public static final class Write3 {
        @System void tick(@Write Mut<F3> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F3(v.a() - 0.01f, v.b(), v.c()));
        }
    }
    public static final class Write4 {
        @System void tick(@Write Mut<F4> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F4(v.a() - 0.01f, v.b(), v.c(), v.d()));
        }
    }
    public static final class Write5 {
        @System void tick(@Write Mut<F5> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F5(v.a() - 0.01f, v.b(), v.c(), v.d(), v.e()));
        }
    }
    public static final class Write6 {
        @System void tick(@Write Mut<F6> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F6(v.a() - 0.01f, v.b(), v.c(), v.d(), v.e(), v.f()));
        }
    }
    public static final class Write8 {
        @System void tick(@Write Mut<F8> m) {
            var v = m.get();
            if (v.a() > 0.9f) m.set(new F8(v.a() - 0.01f, v.b(), v.c(), v.d(), v.e(), v.f(), v.g(), v.h()));
        }
    }

    @Param({"10000"})
    int entityCount;

    World w1, w2, w3, w4, w5, w6, w8;

    @Setup(Level.Iteration)
    public void setup() {
        var rng = new java.util.Random(42);
        w1 = World.builder().addSystem(new Write1()).build();
        w2 = World.builder().addSystem(new Write2()).build();
        w3 = World.builder().addSystem(new Write3()).build();
        w4 = World.builder().addSystem(new Write4()).build();
        w5 = World.builder().addSystem(new Write5()).build();
        w6 = World.builder().addSystem(new Write6()).build();
        w8 = World.builder().addSystem(new Write8()).build();
        for (int i = 0; i < entityCount; i++) {
            float v = rng.nextFloat();
            w1.spawn(new F1(v));
            w2.spawn(new F2(v, v));
            w3.spawn(new F3(v, v, v));
            w4.spawn(new F4(v, v, v, v));
            w5.spawn(new F5(v, v, v, v, v));
            w6.spawn(new F6(v, v, v, v, v, v));
            w8.spawn(new F8(v, v, v, v, v, v, v, v));
        }
        w1.tick(); w2.tick(); w3.tick(); w4.tick(); w5.tick(); w6.tick(); w8.tick();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        w1.close(); w2.close(); w3.close(); w4.close(); w5.close(); w6.close(); w8.close();
    }

    @Benchmark public void fields1(Blackhole bh) { w1.tick(); }
    @Benchmark public void fields2(Blackhole bh) { w2.tick(); }
    @Benchmark public void fields3(Blackhole bh) { w3.tick(); }
    @Benchmark public void fields4(Blackhole bh) { w4.tick(); }
    @Benchmark public void fields5(Blackhole bh) { w5.tick(); }
    @Benchmark public void fields6(Blackhole bh) { w6.tick(); }
    @Benchmark public void fields8(Blackhole bh) { w8.tick(); }
}
