package zzuegg.ecs.bench.valhalla.scenario;

import jdk.internal.vm.annotation.LooselyConsistentValue;
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

import java.util.concurrent.TimeUnit;

/**
 * Valhalla variant of {@code ParticleScenarioBenchmark}. Components are
 * {@code value record}s so Valhalla can flatten them into backing arrays.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ParticleScenarioBenchmarkValhalla {

    @LooselyConsistentValue public value record Position(float x, float y, float z) {}
    @LooselyConsistentValue public value record Velocity(float dx, float dy, float dz) {}
    @LooselyConsistentValue public value record Lifetime(int ttl) {}
    @LooselyConsistentValue public value record Health(int hp) {}
    @LooselyConsistentValue public value record Stats(long deaths, long alive) {}

    @Param({"10000"})
    int entityCount;

    World world;

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
            int startHp = 1 + (i % 100);
            world.spawn(
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(startHp));
        }
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
