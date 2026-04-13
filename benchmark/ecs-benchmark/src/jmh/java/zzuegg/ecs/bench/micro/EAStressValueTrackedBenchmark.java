package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.component.ValueTracked;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * EA-stress benchmark comparing @ValueTracked vs non-tracked write paths.
 *
 * Non-tracked components use the generated HiddenMut subclass which stores
 * primitives directly in fields -- ideal for scalar replacement (EA).
 *
 * @ValueTracked components fall back to the base Mut class because flush()
 * needs original.equals(current) comparison.  This keeps the original Record
 * alive, blocks EA, and adds a virtual equals() dispatch.
 *
 * Run with:
 *   -prof gc                     allocation rate
 *   -prof jdk.EscapeAnalysis     EA scalar replacement stats (JDK 24+)
 *
 * All systems use a 10% write rate so the mutation pattern is identical.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"--enable-preview"})
public class EAStressValueTrackedBenchmark {

    // ── Component types ─────────────────────────────────────────────

    /** Plain component -- eligible for HiddenMut generation. */
    record Health(int hp) {}

    /** @ValueTracked component -- falls back to base Mut. */
    @ValueTracked record TrackedHealth(int hp) {}

    // ── Systems ─────────────────────────────────────────────────────

    /**
     * Baseline: writes 10% of entities via HiddenMut (no Record alloc).
     */
    static class UntrackedWriter {
        @System
        void write(@Read Health h, @Write Mut<Health> out) {
            if (h.hp() % 10 == 0) {
                out.set(new Health(h.hp() + 1));
            }
        }
    }

    /**
     * @ValueTracked: writes 10% of entities via plain Mut (Record survives).
     */
    static class TrackedWriter {
        @System
        void write(@Read TrackedHealth h, @Write Mut<TrackedHealth> out) {
            if (h.hp() % 10 == 0) {
                out.set(new TrackedHealth(h.hp() + 1));
            }
        }
    }

    /**
     * @ValueTracked: sets the SAME value back (10% rate).
     * flush() should suppress the change via equals().
     */
    static class TrackedSameValueWriter {
        @System
        void write(@Read TrackedHealth h, @Write Mut<TrackedHealth> out) {
            if (h.hp() % 10 == 0) {
                out.set(new TrackedHealth(h.hp())); // same value
            }
        }
    }

    /**
     * Observer for @ValueTracked: counts entities that actually changed.
     * Used to verify equals-suppression works for same-value writes.
     */
    public static class TrackedChangeObserver {
        public static volatile long seen;

        @System
        @Filter(value = Changed.class, target = TrackedHealth.class)
        void observe(@Read TrackedHealth h) {
            seen++;
        }
    }

    // ── Benchmark state ─────────────────────────────────────────────

    @Param({"10000"})
    int entityCount;

    World untrackedWorld;
    World trackedWorld;
    World trackedSameValueWorld;
    World trackedDiffValueWorld;

    @Setup(Level.Trial)
    public void setup() {
        untrackedWorld = World.builder()
            .addSystem(UntrackedWriter.class)
            .build();

        trackedWorld = World.builder()
            .addSystem(TrackedWriter.class)
            .build();

        trackedSameValueWorld = World.builder()
            .addSystem(TrackedSameValueWriter.class)
            .addSystem(TrackedChangeObserver.class)
            .build();

        trackedDiffValueWorld = World.builder()
            .addSystem(TrackedWriter.class)
            .addSystem(TrackedChangeObserver.class)
            .build();

        for (int i = 0; i < entityCount; i++) {
            untrackedWorld.spawn(new Health(i));
            trackedWorld.spawn(new TrackedHealth(i));
            trackedSameValueWorld.spawn(new TrackedHealth(i));
            trackedDiffValueWorld.spawn(new TrackedHealth(i));
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (untrackedWorld != null) untrackedWorld.close();
        if (trackedWorld != null) trackedWorld.close();
        if (trackedSameValueWorld != null) trackedSameValueWorld.close();
        if (trackedDiffValueWorld != null) trackedDiffValueWorld.close();
    }

    // ── Benchmarks ──────────────────────────────────────────────────

    /** Baseline: HiddenMut path, no @ValueTracked. */
    @Benchmark
    public void writeUntracked() {
        untrackedWorld.tick();
    }

    /** @ValueTracked: plain Mut fallback, different value 10% rate. */
    @Benchmark
    public void writeTrackedDifferentValue() {
        trackedWorld.tick();
    }

    /** @ValueTracked + same value: exercises equals() suppression. */
    @Benchmark
    public void writeTrackedSameValue() {
        trackedSameValueWorld.tick();
    }

    /** @ValueTracked + different value + Changed observer. */
    @Benchmark
    public void writeTrackedDiffWithObserver() {
        TrackedChangeObserver.seen = 0;
        trackedDiffValueWorld.tick();
    }
}
