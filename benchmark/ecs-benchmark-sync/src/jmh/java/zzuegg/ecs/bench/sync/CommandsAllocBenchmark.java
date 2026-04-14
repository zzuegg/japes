package zzuegg.ecs.bench.sync;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.command.CommandProcessor;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Allocation benchmark for the flat-buffer {@link Commands} implementation.
 *
 * <p>Measures B/op for spawn, set, despawn, mixed, and steady-state scenarios.
 * Run with {@code -prof gc} to see {@code gc.alloc.rate.norm}.
 *
 * <p>The key question: after the flat-buffer rewrite, is the remaining
 * allocation purely from the flush side (World.spawn / setComponent / despawn)
 * or is there still command-buffer overhead?
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CommandsAllocBenchmark {

    static final int OPS_PER_BATCH = 100;

    public record Position(float x, float y, float z) {}
    public record Velocity(float vx, float vy, float vz) {}

    // ---- State for spawn benchmark ----

    @State(Scope.Thread)
    public static class SpawnState {
        World world;
        Commands cmds;

        @Setup(Level.Iteration)
        public void setup() {
            world = World.builder().build();
            cmds = new Commands();
            // Prime: run one batch to grow arrays to steady-state size
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                cmds.spawn(new Position(i, i, i));
            }
            CommandProcessor.process(cmds, world);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            if (world != null) world.close();
        }
    }

    // ---- State for set benchmark ----

    @State(Scope.Thread)
    public static class SetState {
        World world;
        Commands cmds;
        List<Entity> entities;

        @Setup(Level.Iteration)
        public void setup() {
            world = World.builder().build();
            cmds = new Commands();
            entities = new ArrayList<>(OPS_PER_BATCH);
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
            // Prime the command buffer arrays
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                cmds.set(entities.get(i), new Position(i + 1, i + 1, i + 1));
            }
            CommandProcessor.process(cmds, world);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            if (world != null) world.close();
        }
    }

    // ---- State for despawn benchmark ----

    @State(Scope.Thread)
    public static class DespawnState {
        World world;
        Commands cmds;
        List<Entity> entities;

        @Setup(Level.Invocation)
        public void setup() {
            world = World.builder().build();
            cmds = new Commands();
            entities = new ArrayList<>(OPS_PER_BATCH);
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
            // Prime the command buffer arrays by doing a dummy flush
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                cmds.despawn(entities.get(i));
            }
            // Reset without processing -- we want the entities alive
            cmds.reset();
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            if (world != null) world.close();
        }
    }

    // ---- State for mixed benchmark ----

    @State(Scope.Thread)
    public static class MixedState {
        World world;
        Commands cmds;
        List<Entity> entities;

        @Setup(Level.Invocation)
        public void setup() {
            world = World.builder().build();
            cmds = new Commands();
            entities = new ArrayList<>(200);
            // 100 entities for set, 50 will be despawned
            for (int i = 0; i < 200; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
            // Prime arrays with a mixed batch
            for (int i = 0; i < 50; i++) {
                cmds.spawn(new Position(i, i, i));
            }
            for (int i = 0; i < 50; i++) {
                cmds.set(entities.get(i), new Position(i + 1, i + 1, i + 1));
            }
            for (int i = 100; i < 150; i++) {
                cmds.despawn(entities.get(i));
            }
            // Reset without flushing -- we just wanted to grow the buffers
            cmds.reset();
        }

        @TearDown(Level.Invocation)
        public void tearDown() {
            if (world != null) world.close();
        }
    }

    // ---- State for steady-state benchmark ----

    @State(Scope.Thread)
    public static class SteadyState {
        World world;
        Commands cmds;
        List<Entity> entities;

        @Setup(Level.Iteration)
        public void setup() {
            world = World.builder().build();
            cmds = new Commands();
            entities = new ArrayList<>(OPS_PER_BATCH);
            for (int i = 0; i < OPS_PER_BATCH; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
            // Run 10 ticks of set commands to reach steady state
            for (int tick = 0; tick < 10; tick++) {
                for (int i = 0; i < OPS_PER_BATCH; i++) {
                    cmds.set(entities.get(i), new Position(tick + i, tick + i, tick + i));
                }
                CommandProcessor.process(cmds, world);
            }
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            if (world != null) world.close();
        }
    }

    // ---- Benchmarks ----

    /** Spawn 100 entities via Commands + flush. */
    @Benchmark
    public void spawn100(SpawnState state) {
        Commands cmds = state.cmds;
        for (int i = 0; i < OPS_PER_BATCH; i++) {
            cmds.spawn(new Position(i, i, i));
        }
        CommandProcessor.process(cmds, state.world);
    }

    /** Set 100 components via Commands + flush. */
    @Benchmark
    public void set100(SetState state) {
        Commands cmds = state.cmds;
        List<Entity> entities = state.entities;
        for (int i = 0; i < OPS_PER_BATCH; i++) {
            cmds.set(entities.get(i), new Position(i + 1, i + 1, i + 1));
        }
        CommandProcessor.process(cmds, state.world);
    }

    /** Despawn 100 entities via Commands + flush. */
    @Benchmark
    public void despawn100(DespawnState state) {
        Commands cmds = state.cmds;
        List<Entity> entities = state.entities;
        for (int i = 0; i < OPS_PER_BATCH; i++) {
            cmds.despawn(entities.get(i));
        }
        CommandProcessor.process(cmds, state.world);
    }

    /** Mixed: 50 spawn + 50 set + 50 despawn via Commands + flush. */
    @Benchmark
    public void mixed150(MixedState state) {
        Commands cmds = state.cmds;
        List<Entity> entities = state.entities;
        for (int i = 0; i < 50; i++) {
            cmds.spawn(new Position(i, i, i));
        }
        for (int i = 0; i < 50; i++) {
            cmds.set(entities.get(i), new Position(i + 1, i + 1, i + 1));
        }
        for (int i = 150; i < 200; i++) {
            cmds.despawn(entities.get(i));
        }
        CommandProcessor.process(cmds, state.world);
    }

    /**
     * Steady-state: run 10 ticks in setup, measure tick 11.
     * Arrays should be pre-sized -- queueing should be ~0 B/op.
     * Any allocation is purely from the flush side.
     */
    @Benchmark
    public void steadyStateSet100(SteadyState state) {
        Commands cmds = state.cmds;
        List<Entity> entities = state.entities;
        for (int i = 0; i < OPS_PER_BATCH; i++) {
            cmds.set(entities.get(i), new Position(i + 1, i + 1, i + 1));
        }
        CommandProcessor.process(cmds, state.world);
    }
}
