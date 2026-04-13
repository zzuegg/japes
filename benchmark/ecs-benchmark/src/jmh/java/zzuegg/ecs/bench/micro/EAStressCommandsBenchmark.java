package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * EA-stress benchmark: does the presence of {@link Commands} (and other
 * service parameters) prevent escape analysis from scalar-replacing
 * {@link Mut} and the component records inside a write-heavy system?
 *
 * Five variants, all touching 10k entities with ~10% mutation rate:
 *
 *   1. writeOnly        -- @Write Mut<Health> baseline
 *   2. writeAndCommands  -- @Write Mut<Health> + Commands (never used)
 *   3. writeAndCommandsSpawn -- @Write Mut<Health> + Commands (spawn on 1%)
 *   4. writeResCommands  -- @Write Mut<Health> + Res<Config> + Commands
 *   5. writeAndEntity    -- @Write Mut<Health> + Entity self
 *
 * Run with {@code -prof gc} to compare {@code gc.alloc.rate.norm}
 * across the five variants. Any variant that allocates significantly
 * more than writeOnly has broken EA on the Mut wrapper.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EAStressCommandsBenchmark {

    // ---- Components & resources ----

    public record Health(int hp) {}
    public record Marker(int id) {}
    public record Config(int threshold) {}

    // ---- Systems ----

    // 1. Baseline: @Write Mut<Health> only
    public static class WriteOnlySystem {
        @System
        void tick(@Write Mut<Health> h) {
            var cur = h.get();
            // ~10% mutation: mutate when hp % 10 == 0
            if (cur.hp() % 10 == 0) {
                h.set(new Health(cur.hp() + 1));
            }
        }
    }

    // 2. @Write Mut<Health> + Commands (never invoked)
    public static class WriteAndCommandsSystem {
        @System
        void tick(@Write Mut<Health> h, Commands cmds) {
            var cur = h.get();
            if (cur.hp() % 10 == 0) {
                h.set(new Health(cur.hp() + 1));
            }
            // cmds deliberately unused -- does its presence break EA on Mut?
        }
    }

    // 3. @Write Mut<Health> + Commands with occasional spawn (~1%)
    public static class WriteAndCommandsSpawnSystem {
        @System
        void tick(@Write Mut<Health> h, Commands cmds) {
            var cur = h.get();
            if (cur.hp() % 10 == 0) {
                h.set(new Health(cur.hp() + 1));
            }
            // 1% of entities trigger a spawn command
            if (cur.hp() % 100 == 0) {
                cmds.spawn(new Health(1000));
            }
        }
    }

    // 4. @Write Mut<Health> + Res<Config> + Commands
    public static class WriteResCommandsSystem {
        @System
        void tick(@Write Mut<Health> h, Res<Config> cfg, Commands cmds) {
            var cur = h.get();
            if (cur.hp() % cfg.get().threshold() == 0) {
                h.set(new Health(cur.hp() + 1));
            }
            // cmds unused
        }
    }

    // 5. @Write Mut<Health> + Entity self
    public static class WriteAndEntitySystem {
        @System
        void tick(@Write Mut<Health> h, Entity self) {
            var cur = h.get();
            // Use entity index to drive the mutation condition so the
            // Entity parameter is consumed and not DCE'd.
            if ((cur.hp() + self.index()) % 10 == 0) {
                h.set(new Health(cur.hp() + 1));
            }
        }
    }

    // ---- Benchmark state ----

    static final int ENTITY_COUNT = 10_000;

    World writeOnlyWorld;
    World writeAndCommandsWorld;
    World writeAndCommandsSpawnWorld;
    World writeResCommandsWorld;
    World writeAndEntityWorld;

    @Setup(Level.Iteration)
    public void setup() {
        writeOnlyWorld = buildWorld(WriteOnlySystem.class, false);
        writeAndCommandsWorld = buildWorld(WriteAndCommandsSystem.class, false);
        writeAndCommandsSpawnWorld = buildWorld(WriteAndCommandsSpawnSystem.class, false);
        writeResCommandsWorld = buildWorld(WriteResCommandsSystem.class, true);
        writeAndEntityWorld = buildWorld(WriteAndEntitySystem.class, false);
    }

    private World buildWorld(Class<?> systemClass, boolean withResource) {
        var builder = World.builder().addSystem(systemClass);
        if (withResource) {
            builder.addResource(new Config(10));
        }
        var w = builder.build();
        for (int i = 0; i < ENTITY_COUNT; i++) {
            w.spawn(new Health(i));
        }
        // Prime one tick so change trackers have a baseline.
        w.tick();
        return w;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (writeOnlyWorld != null) writeOnlyWorld.close();
        if (writeAndCommandsWorld != null) writeAndCommandsWorld.close();
        if (writeAndCommandsSpawnWorld != null) writeAndCommandsSpawnWorld.close();
        if (writeResCommandsWorld != null) writeResCommandsWorld.close();
        if (writeAndEntityWorld != null) writeAndEntityWorld.close();
    }

    // ---- Benchmarks ----

    @Benchmark
    public void writeOnly() {
        writeOnlyWorld.tick();
    }

    @Benchmark
    public void writeAndCommands() {
        writeAndCommandsWorld.tick();
    }

    @Benchmark
    public void writeAndCommandsSpawn() {
        writeAndCommandsSpawnWorld.tick();
    }

    @Benchmark
    public void writeResCommands() {
        writeResCommandsWorld.tick();
    }

    @Benchmark
    public void writeAndEntity() {
        writeAndEntityWorld.tick();
    }
}
