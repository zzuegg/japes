package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.RemovedComponents;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cross-library "particle system tick" scenario — the same workload is
 * implemented in {@code ZayEsParticleScenarioBenchmark} (ecs-benchmark-zayes)
 * and {@code change_detection_benchmarks} (bevy-benchmark) so the three ECS
 * libraries can be compared in their idiomatic shape.
 *
 * Entities: {Position, Velocity, Lifetime, Health}
 *
 * Per tick, in order:
 *   1. move     — Position += Velocity
 *   2. damage   — Health.hp -= 1
 *   3. reap     — entities with hp <= 0 are despawned
 *   4. stats    — count (deaths this tick, alive with lifetime > 0)
 *                 uses RemovedComponents<Health> for the death count
 *   5. respawn  — replenish to keep total entity count at N
 *
 * Steady-state 10k entities, ~1% turnover per tick.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ParticleScenarioBenchmark {

    public record Position(float x, float y, float z) {}
    public record Velocity(float dx, float dy, float dz) {}
    public record Lifetime(int ttl) {}
    public record Health(int hp) {}
    public record Stats(long deaths, long alive) {}

    @Param({"10000"})
    int entityCount;

    World world;
    long tickCount;

    public static class MoveSystem {
        @System
        void move(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy(), cur.z() + v.dz()));
        }
    }

    public static class DamageSystem {
        @System
        void damage(@Write Mut<Health> h) {
            h.set(new Health(h.get().hp() - 1));
        }
    }

    // Per-entity reaper — uses Entity injection for the current entity handle
    // and Commands.despawn to defer the despawn to the stage-flush boundary.
    // Replaces the earlier @Exclusive + world.snapshot fallback.
    public static class ReapSystem {
        @System
        void reap(@Read Health h, Entity self, Commands cmds) {
            if (h.hp() <= 0) {
                cmds.despawn(self);
            }
        }
    }

    public static class StatsSystem {
        @System
        void stats(RemovedComponents<Health> dead, ResMut<Stats> stats) {
            long deathCount = 0;
            for (var r : dead) deathCount++;
            var cur = stats.get();
            stats.set(new Stats(cur.deaths() + deathCount, cur.alive()));
        }
    }

    public static class RespawnSystem {
        @Exclusive
        @System
        void respawn(World w) {
            // Pad total entity count back to the target. The exclusive pass
            // lets us run this alongside the snapshot-based reaper without
            // worrying about scheduler interactions.
            int target = 10_000;
            int current = w.entityCount();
            for (int i = current; i < target; i++) {
                w.spawn(new Position(0, 0, 0), new Velocity(1, 1, 1),
                    new Lifetime(1000), new Health(100));
            }
        }
    }

    @Setup(Level.Iteration)
    public void setup() {
        world = World.builder()
            .addResource(new Stats(0, 0))
            .addSystem(MoveSystem.class)
            .addSystem(DamageSystem.class)
            .addSystem(ReapSystem.class)
            .addSystem(StatsSystem.class)
            .addSystem(RespawnSystem.class)
            .build();
        for (int i = 0; i < entityCount; i++) {
            // Stagger starting hp so ~1% die each tick (hp in [1, 100]).
            int startHp = 1 + (i % 100);
            world.spawn(
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(startHp));
        }
        // Prime one tick so the change filters have a baseline.
        world.tick();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        world.tick();
        bh.consume(world.entityCount());
    }
}
