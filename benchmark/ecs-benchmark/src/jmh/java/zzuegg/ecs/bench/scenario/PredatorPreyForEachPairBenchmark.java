package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.ComponentReader;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.FromTarget;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Alternate shape of {@link PredatorPreyBenchmark} that uses
 * {@code @ForEachPair(Hunting.class)} for pursuit instead of
 * {@code @Pair(Hunting.class)} + {@code PairReader}. Compares the
 * two dispatch models on the same workload — per-entity iteration
 * with an inner pair walker vs per-pair iteration with direct
 * source/target component binding.
 *
 * <p>Current expectation (as of the reflective tier-3 processor):
 * this benchmark is likely <em>slower</em> than the per-entity
 * version because the dispatch path goes through {@code world
 * .getComponent} and {@code SystemInvoker.invoke} reflection.
 * Once tier-1 bytecode generation for pair iteration lands, the
 * number should drop below the per-entity cell.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PredatorPreyForEachPairBenchmark {

    // Reuse the top-level component records via nested redeclaration —
    // JMH benchmark classes are compiled in isolation and we don't
    // want to share state with the companion benchmark.
    public record Position(float x, float y) {}
    public record Velocity(float dx, float dy) {}
    public record Predator(int speed) {}
    public record Prey(int alert) {}

    @Relation
    public record Hunting(int ticksLeft) {}

    public static final class PreyRoster { public final List<Entity> alive = new ArrayList<>(); }
    public static final class Config {
        public float catchDistance = 0.5f;
        public float arenaSize = 20.0f;
        public Random rng = new Random(1234);
    }
    public static final class Counters { public long pursuitCalls; public long catches; }

    public static Blackhole BH;
    public static volatile int BASELINE_PREY_COUNT;

    public static class Systems {

        @System
        public void movement(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
        }

        @System(after = "movement")
        @zzuegg.ecs.system.Without(Hunting.class)
        public void acquireHunt(
                @Read Predator pred,
                Entity self,
                Res<PreyRoster> roster,
                Res<Config> config,
                World world,
                zzuegg.ecs.command.Commands cmds
        ) {
            var preyList = roster.get().alive;
            if (preyList.isEmpty()) return;
            var target = preyList.get(config.get().rng.nextInt(preyList.size()));
            if (!world.isAlive(target)) return;
            cmds.setRelation(self, target, new Hunting(3));
        }

        /**
         * Per-pair pursuit. One call per live {@code Hunting} pair.
         * {@code sourcePos} and {@code sourceVel} resolve against the
         * source (predator); {@code targetPos} against the target
         * (prey) via {@code @FromTarget}.
         */
        @System
        @ForEachPair(Hunting.class)
        public void pursuit(
                @Read Position sourcePos,
                @Write Mut<Velocity> sourceVel,
                @FromTarget @Read Position targetPos,
                Hunting hunting,
                ResMut<Counters> counters
        ) {
            float dx = targetPos.x() - sourcePos.x();
            float dy = targetPos.y() - sourcePos.y();
            float mag = (float) Math.sqrt(dx * dx + dy * dy);
            if (mag > 1e-4f) {
                sourceVel.set(new Velocity(dx / mag * 0.1f, dy / mag * 0.1f));
            }
            counters.get().pursuitCalls++;
            if (BH != null) BH.consume(mag);
        }

        @System(stage = "PostUpdate")
        @zzuegg.ecs.system.Exclusive
        public void resolveCatches(
                World world,
                ResMut<PreyRoster> roster,
                Res<Config> config,
                ComponentReader<Position> posReader
        ) {
            var store = world.componentRegistry().relationStore(Hunting.class);
            if (store == null) return;
            float catchDistSq = config.get().catchDistance * config.get().catchDistance;

            var caught = new ArrayList<Entity>();
            store.forEachPair((predator, prey, val) -> {
                var predPos = posReader.get(predator);
                var preyPos = posReader.get(prey);
                if (predPos == null || preyPos == null) return;
                float dx = predPos.x() - preyPos.x();
                float dy = predPos.y() - preyPos.y();
                if (dx * dx + dy * dy <= catchDistSq) {
                    caught.add(prey);
                }
            });

            if (!caught.isEmpty()) {
                var alive = roster.get().alive;
                for (int i = 0, n = caught.size(); i < n; i++) {
                    var prey = caught.get(i);
                    if (world.isAlive(prey)) {
                        world.despawn(prey);
                        alive.remove(prey);
                    }
                }
            }
        }

        @System(stage = "PostUpdate", after = "resolveCatches")
        @zzuegg.ecs.system.Exclusive
        public void respawnPrey(World world, ResMut<PreyRoster> roster, Res<Config> config) {
            while (roster.get().alive.size() < BASELINE_PREY_COUNT) {
                float x = config.get().rng.nextFloat() * config.get().arenaSize;
                float y = config.get().rng.nextFloat() * config.get().arenaSize;
                var p = world.spawn(new Position(x, y), new Velocity(0f, 0f), new Prey(0));
                roster.get().alive.add(p);
            }
        }
    }

    @Param({"500"}) public int predatorCount;
    @Param({"2000"}) public int preyCount;

    World world;

    @Setup(Level.Iteration)
    public void setup() {
        BASELINE_PREY_COUNT = preyCount;
        var roster = new PreyRoster();
        var config = new Config();
        var counters = new Counters();

        world = World.builder()
            .addResource(roster)
            .addResource(config)
            .addResource(counters)
            .addSystem(Systems.class)
            .build();

        var rng = new Random(7);
        for (int i = 0; i < predatorCount; i++) {
            world.spawn(
                new Position(rng.nextFloat() * 2f, rng.nextFloat() * 2f),
                new Velocity(0.05f, 0.05f),
                new Predator(1)
            );
        }
        for (int i = 0; i < preyCount; i++) {
            var e = world.spawn(
                new Position(rng.nextFloat() * config.arenaSize, rng.nextFloat() * config.arenaSize),
                new Velocity(0f, 0f),
                new Prey(0)
            );
            roster.alive.add(e);
        }

        for (int i = 0; i < 5; i++) world.tick();
    }

    @TearDown(Level.Iteration)
    public void tearDown() { if (world != null) world.close(); }

    @Benchmark
    public void tick(Blackhole bh) {
        BH = bh;
        world.tick();
    }
}
