package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.FromTarget;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * EA stress tests for {@code @ForEachPair} write paths.
 *
 * <p>Each benchmark isolates a single pair-write pattern at 500 sources x 4
 * targets (2000 pairs). Running with {@code -prof gc} reveals which patterns
 * the JIT can scalar-replace the {@code Mut<T>} and record allocations for,
 * and which fall back to heap allocation.
 *
 * <p>Patterns tested:
 * <ol>
 *   <li>{@code @Write Mut<Velocity>} — 2 float fields (PredatorPrey shape)</li>
 *   <li>{@code @Write Mut<Position>} — 3 float fields, source-side</li>
 *   <li>{@code @Write Mut<SingleInt>} — 1 int field, simplest case</li>
 *   <li>{@code @Read Position + @Write Mut<Velocity>} — read + write same source</li>
 *   <li>{@code @FromTarget @Read Position + @Write Mut<Velocity>} — cross-entity read + source write</li>
 *   <li>{@code @Write Mut<Velocity> + @Write Mut<SingleInt>} — two source writes</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"--enable-preview"})
public class EAStressPairWriteBenchmark {

    // ── Component records ──────────────────────────────────────────
    public record Position(float x, float y, float z) {}
    public record Velocity(float dx, float dy) {}
    public record SingleInt(int value) {}
    public record Tag() {}

    @Relation
    public record Link(int weight) {}

    // ── System classes — one per pattern ───────────────────────────

    /** Pattern 1: @Write Mut<Velocity> — 2 float fields. */
    public static class WriteVelocity2f {
        @System
        @ForEachPair(Link.class)
        public void run(@Write Mut<Velocity> vel, Link link) {
            var v = vel.get();
            vel.set(new Velocity(v.dx() + 0.01f, v.dy() + 0.01f));
        }
    }

    /** Pattern 2: @Write Mut<Position> — 3 float fields, source-side. */
    public static class WritePosition3f {
        @System
        @ForEachPair(Link.class)
        public void run(@Write Mut<Position> pos, Link link) {
            var p = pos.get();
            pos.set(new Position(p.x() + 0.01f, p.y() + 0.01f, p.z() + 0.01f));
        }
    }

    /** Pattern 3: @Write Mut<SingleInt> — 1 int field, simplest case. */
    public static class WriteSingleInt {
        @System
        @ForEachPair(Link.class)
        public void run(@Write Mut<SingleInt> si, Link link) {
            si.set(new SingleInt(si.get().value() + 1));
        }
    }

    /** Pattern 4: @Read Position + @Write Mut<Velocity> — read + write same source. */
    public static class ReadPosWriteVel {
        @System
        @ForEachPair(Link.class)
        public void run(@Read Position pos, @Write Mut<Velocity> vel, Link link) {
            vel.set(new Velocity(pos.x() * 0.1f, pos.y() * 0.1f));
        }
    }

    /** Pattern 5: @FromTarget @Read Position + source @Write Mut<Velocity>. */
    public static class CrossEntityReadWrite {
        @System
        @ForEachPair(Link.class)
        public void run(
                @Read Position sourcePos,
                @Write Mut<Velocity> sourceVel,
                @FromTarget @Read Position targetPos,
                Link link
        ) {
            float dx = targetPos.x() - sourcePos.x();
            float dy = targetPos.y() - sourcePos.y();
            sourceVel.set(new Velocity(dx * 0.1f, dy * 0.1f));
        }
    }

    /** Pattern 6: @Write Mut<Velocity> + @Write Mut<SingleInt> — two source writes. */
    public static class DualSourceWrite {
        @System
        @ForEachPair(Link.class)
        public void run(
                @Write Mut<Velocity> vel,
                @Write Mut<SingleInt> si,
                Link link
        ) {
            var v = vel.get();
            vel.set(new Velocity(v.dx() + 0.01f, v.dy() + 0.01f));
            si.set(new SingleInt(si.get().value() + 1));
        }
    }

    // ── Worlds — one per pattern ──────────────────────────────────

    World w1_writeVel2f;
    World w2_writePos3f;
    World w3_writeSingleInt;
    World w4_readPosWriteVel;
    World w5_crossEntity;
    World w6_dualWrite;

    private static final int SOURCE_COUNT = 500;
    private static final int TARGETS_PER_SOURCE = 4;

    @Setup(Level.Trial)
    public void setup() {
        w1_writeVel2f       = buildWorld(WriteVelocity2f.class,   true,  false, true);
        w2_writePos3f       = buildWorld(WritePosition3f.class,   true,  true,  false);
        w3_writeSingleInt   = buildWorld(WriteSingleInt.class,    false, false, true);
        w4_readPosWriteVel  = buildWorld(ReadPosWriteVel.class,   true,  false, true);
        w5_crossEntity      = buildWorld(CrossEntityReadWrite.class, true, false, true);
        w6_dualWrite        = buildWorld(DualSourceWrite.class,   true,  false, true);

        // Warm up — let the JIT compile the generated pair processors.
        for (int i = 0; i < 20; i++) {
            w1_writeVel2f.tick();
            w2_writePos3f.tick();
            w3_writeSingleInt.tick();
            w4_readPosWriteVel.tick();
            w5_crossEntity.tick();
            w6_dualWrite.tick();
        }
    }

    /**
     * Build a world with SOURCE_COUNT sources, each linked to TARGETS_PER_SOURCE
     * targets. Components on sources/targets are configured per pattern.
     *
     * @param systemClass system to register
     * @param sourceNeedsPos  if true, sources get Position
     * @param sourceNeedsPosMutable if true, sources get Position (needed for write-pos pattern)
     * @param sourceNeedsVel  if true, sources get Velocity
     */
    private World buildWorld(Class<?> systemClass,
                             boolean sourceNeedsPos,
                             boolean sourceNeedsPosMutable,
                             boolean sourceNeedsVel) {
        var world = World.builder().addSystem(systemClass).build();
        var rng = new Random(42);

        // Create target entities first — they just need Position for cross-entity reads.
        var targets = new Entity[TARGETS_PER_SOURCE];
        for (int t = 0; t < TARGETS_PER_SOURCE; t++) {
            targets[t] = world.spawn(
                new Position(rng.nextFloat() * 100f, rng.nextFloat() * 100f, rng.nextFloat() * 100f),
                new Tag()
            );
        }

        // Create source entities and link them to all targets.
        for (int s = 0; s < SOURCE_COUNT; s++) {
            Entity source;
            if (sourceNeedsPos && sourceNeedsVel) {
                source = world.spawn(
                    new Position(rng.nextFloat() * 10f, rng.nextFloat() * 10f, rng.nextFloat() * 10f),
                    new Velocity(0f, 0f),
                    new SingleInt(0)
                );
            } else if (sourceNeedsPosMutable) {
                source = world.spawn(
                    new Position(rng.nextFloat() * 10f, rng.nextFloat() * 10f, rng.nextFloat() * 10f),
                    new SingleInt(0)
                );
            } else {
                // SingleInt-only source
                source = world.spawn(
                    new SingleInt(0),
                    new Velocity(0f, 0f)
                );
            }

            for (int t = 0; t < TARGETS_PER_SOURCE; t++) {
                world.setRelation(source, targets[t], new Link(s * TARGETS_PER_SOURCE + t));
            }
        }

        return world;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (w1_writeVel2f != null) w1_writeVel2f.close();
        if (w2_writePos3f != null) w2_writePos3f.close();
        if (w3_writeSingleInt != null) w3_writeSingleInt.close();
        if (w4_readPosWriteVel != null) w4_readPosWriteVel.close();
        if (w5_crossEntity != null) w5_crossEntity.close();
        if (w6_dualWrite != null) w6_dualWrite.close();
    }

    // ── Benchmarks ────────────────────────────────────────────────

    @Benchmark
    public void p1_writeVelocity2f() {
        w1_writeVel2f.tick();
    }

    @Benchmark
    public void p2_writePosition3f() {
        w2_writePos3f.tick();
    }

    @Benchmark
    public void p3_writeSingleInt() {
        w3_writeSingleInt.tick();
    }

    @Benchmark
    public void p4_readPosWriteVel() {
        w4_readPosWriteVel.tick();
    }

    @Benchmark
    public void p5_crossEntityReadWrite() {
        w5_crossEntity.tick();
    }

    @Benchmark
    public void p6_dualSourceWrite() {
        w6_dualWrite.tick();
    }
}
