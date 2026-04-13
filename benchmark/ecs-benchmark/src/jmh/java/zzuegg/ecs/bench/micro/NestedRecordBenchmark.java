package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark comparing flat vs nested record types for SoA storage and EA.
 *
 * <p>{@code BigFlat} has 6 direct float fields. {@code BigNested} has
 * the same 6 floats but organized as two nested {@code Vec3} records.
 * Both should flatten to the same SoA layout. This benchmark verifies
 * that nested records achieve the same performance (throughput and
 * zero-allocation) as flat records.
 *
 * <p>Run with: {@code -prof gc} to verify allocation rates.
 * Compare with: {@code -jvmArgs -XX:-EliminateAllocations} to see
 * the cost without EA.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NestedRecordBenchmark {

    // ---- Record types ----

    /** Flat: 6 direct float fields. */
    public record BigFlat(float a, float b, float c, float d, float e, float f) {}

    /** Nested building block. */
    public record Vec3(float x, float y, float z) {}

    /** Nested: same 6 floats but organized as pos + vel. */
    public record BigNested(Vec3 pos, Vec3 vel) {}

    // ---- Systems: read + unconditional write (worst case for allocation) ----

    static class FlatMoveSystem {
        @System
        void move(@Write Mut<BigFlat> mut) {
            var s = mut.get();
            mut.set(new BigFlat(
                s.a() + s.d(), s.b() + s.e(), s.c() + s.f(),
                s.d(), s.e(), s.f()));
        }
    }

    static class NestedMoveSystem {
        @System
        void move(@Write Mut<BigNested> mut) {
            var s = mut.get();
            mut.set(new BigNested(
                new Vec3(s.pos().x() + s.vel().x(),
                         s.pos().y() + s.vel().y(),
                         s.pos().z() + s.vel().z()),
                s.vel()));
        }
    }

    // ---- Read-only variants (no write, pure reconstruction cost) ----

    static volatile float readSink;

    static class FlatReadSystem {
        @System
        void read(@Read BigFlat s) {
            readSink = s.a() + s.b() + s.c();
        }
    }

    static class NestedReadSystem {
        @System
        void read(@Read BigNested s) {
            readSink = s.pos().x() + s.pos().y() + s.pos().z();
        }
    }

    // ---- Benchmark state ----

    @Param({"10000"})
    int entityCount;

    World flatWriteWorld;
    World nestedWriteWorld;
    World flatReadWorld;
    World nestedReadWorld;

    @Setup
    public void setup() {
        flatWriteWorld = World.builder().addSystem(FlatMoveSystem.class).build();
        nestedWriteWorld = World.builder().addSystem(NestedMoveSystem.class).build();
        flatReadWorld = World.builder().addSystem(FlatReadSystem.class).build();
        nestedReadWorld = World.builder().addSystem(NestedReadSystem.class).build();

        for (int i = 0; i < entityCount; i++) {
            flatWriteWorld.spawn(new BigFlat(i, i + 1, i + 2, 1, 0, 0));
            nestedWriteWorld.spawn(new BigNested(
                new Vec3(i, i + 1, i + 2), new Vec3(1, 0, 0)));
            flatReadWorld.spawn(new BigFlat(i, i + 1, i + 2, 1, 0, 0));
            nestedReadWorld.spawn(new BigNested(
                new Vec3(i, i + 1, i + 2), new Vec3(1, 0, 0)));
        }
    }

    @TearDown
    public void tearDown() {
        if (flatWriteWorld != null) flatWriteWorld.close();
        if (nestedWriteWorld != null) nestedWriteWorld.close();
        if (flatReadWorld != null) flatReadWorld.close();
        if (nestedReadWorld != null) nestedReadWorld.close();
    }

    // ---- Benchmarks ----

    @Benchmark
    public void flatWrite() { flatWriteWorld.tick(); }

    @Benchmark
    public void nestedWrite() { nestedWriteWorld.tick(); }

    @Benchmark
    public void flatRead() { flatReadWorld.tick(); }

    @Benchmark
    public void nestedRead() { nestedReadWorld.tick(); }
}
