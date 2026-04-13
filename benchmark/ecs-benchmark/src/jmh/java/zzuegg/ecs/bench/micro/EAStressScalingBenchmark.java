package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Escape Analysis stress test — entity count scaling.
 *
 * Tests whether the JIT's Escape Analysis keeps allocations constant
 * regardless of entity count.  A simple write system (10% mutation rate
 * on {@code Mut<Health>}) is run at five entity counts from 100 to 1M.
 *
 * If EA succeeds, {@code gc.alloc.rate.norm} stays flat (structural
 * overhead only).  If EA fails at some threshold, allocations scale
 * linearly with entity count.
 *
 * Secondary question: does chunk boundary crossing defeat EA?
 * With the default chunk size of 1024, 10k entities span ~10 chunks,
 * each getting its own {@code processAll} call.  A separate sub-benchmark
 * uses chunk size 64 to force many more chunk transitions at the same
 * entity count, isolating the chunk-boundary variable.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {
    "--enable-preview"
})
public class EAStressScalingBenchmark {

    // ---- Components ----

    public record Health(int hp) {}

    // ---- Systems ----

    /**
     * 10% mutation rate: only entities whose slot mod 10 == 0 actually
     * write.  This is realistic (most ticks, most entities are untouched)
     * and stresses EA because the Mut wrapper is created for every entity
     * but only sometimes escapes through {@code set()}.
     */
    public static class SparseWriteSystem {
        @System
        void tick(@Write Mut<Health> h) {
            if (h.get().hp() % 10 == 0) {
                h.set(new Health(h.get().hp() - 1));
            }
        }
    }

    /**
     * 100% mutation rate — every entity writes.
     * Useful as a control: if EA works, this should still be flat.
     */
    public static class DenseWriteSystem {
        @System
        void tick(@Write Mut<Health> h) {
            h.set(new Health(h.get().hp() - 1));
        }
    }

    // ---- Entity count scaling ----

    @Param({"100", "1000", "10000", "100000", "1000000"})
    int entityCount;

    World sparseWorld;
    World denseWorld;

    @Setup(Level.Trial)
    public void setup() {
        sparseWorld = World.builder()
            .addSystem(SparseWriteSystem.class)
            .build();
        denseWorld = World.builder()
            .addSystem(DenseWriteSystem.class)
            .build();

        for (int i = 0; i < entityCount; i++) {
            sparseWorld.spawn(new Health(100 + i));
            denseWorld.spawn(new Health(100 + i));
        }

        // Prime: let the JIT see the hot path once before measurement.
        sparseWorld.tick();
        denseWorld.tick();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (sparseWorld != null) sparseWorld.close();
        if (denseWorld != null) denseWorld.close();
    }

    @Benchmark
    public void sparseWrite_10pct() {
        sparseWorld.tick();
    }

    @Benchmark
    public void denseWrite_100pct() {
        denseWorld.tick();
    }

    // ---- Chunk boundary stress ----

    /**
     * Same 10k entities, but with chunk size 64 instead of default 1024.
     * This creates ~156 chunks, each with its own processAll invocation.
     * If the JIT fails to EA across chunk boundaries, this will allocate
     * more than the default-chunk variant at the same entity count.
     */
    @State(Scope.Benchmark)
    public static class ChunkBoundaryState {

        @Param({"10000"})
        int entityCount;

        World smallChunkWorld;  // chunk size 64
        World defaultChunkWorld; // chunk size 1024

        @Setup(Level.Trial)
        public void setup() {
            smallChunkWorld = World.builder()
                .chunkSize(64)
                .addSystem(SparseWriteSystem.class)
                .build();
            defaultChunkWorld = World.builder()
                .chunkSize(1024)
                .addSystem(SparseWriteSystem.class)
                .build();

            for (int i = 0; i < entityCount; i++) {
                smallChunkWorld.spawn(new Health(100 + i));
                defaultChunkWorld.spawn(new Health(100 + i));
            }

            smallChunkWorld.tick();
            defaultChunkWorld.tick();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (smallChunkWorld != null) smallChunkWorld.close();
            if (defaultChunkWorld != null) defaultChunkWorld.close();
        }
    }

    @Benchmark
    public void chunkBoundary_size64(ChunkBoundaryState state) {
        state.smallChunkWorld.tick();
    }

    @Benchmark
    public void chunkBoundary_size1024(ChunkBoundaryState state) {
        state.defaultChunkWorld.tick();
    }
}
