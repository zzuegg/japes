package zzuegg.ecs.bench.valhalla.scenario;

import jdk.internal.vm.annotation.LooselyConsistentValue;
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
import zzuegg.ecs.util.LongArrayList;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Valhalla variant of {@code PredatorPreyForEachPairBenchmark}. The
 * four per-entity components (Position, Velocity, Predator, Prey)
 * are declared as {@code value record}s annotated
 * {@code @LooselyConsistentValue} so the VM returns flat
 * primitive-backed arrays from
 * {@code ValueClass.newNullRestrictedNonAtomicArray} and component
 * reads turn into direct primitive loads instead of reference
 * loads + unboxing.
 *
 * <p>The {@code Hunting} relation payload is kept as a plain
 * {@code record} — relation payloads live in the per-source
 * {@code TargetSlice.values} {@code Object[]}, not in a flat
 * {@code ComponentStorage}, so there's nothing to flatten on the
 * payload side. The win (if any) is entirely in the per-entity
 * component reads and writes the pair iteration drives.
 *
 * <p>Same workload shape, same grid params, same scheduler,
 * same @ForEachPair dispatch as the stock benchmark —
 * numbers are directly comparable.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PredatorPreyForEachPairBenchmarkValhalla {

    // @LooselyConsistentValue opts into the flat-array layout — without it
    // the VM returns a non-flat reference array even for newNullRestricted
    // storage. Matches RealisticTickBenchmarkValhalla.
    @LooselyConsistentValue public value record Position(float x, float y) {}
    @LooselyConsistentValue public value record Velocity(float dx, float dy) {}
    @LooselyConsistentValue public value record Predator(int speed) {}
    @LooselyConsistentValue public value record Prey(int alert) {}

    // Relation payload is plain — lives in Object[] in TargetSlice, not
    // a flat component storage, so value semantics give nothing here.
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

            var caughtIds = CAUGHT_BUFFER;
            caughtIds.clear();
            store.forEachPairLong((predatorId, preyId, val) -> {
                var predPos = posReader.getById(predatorId);
                var preyPos = posReader.getById(preyId);
                if (predPos == null || preyPos == null) return;
                float dx = predPos.x() - preyPos.x();
                float dy = predPos.y() - preyPos.y();
                if (dx * dx + dy * dy <= catchDistSq) {
                    caughtIds.add(preyId);
                }
            });

            int caughtCount = caughtIds.size();
            if (caughtCount == 0) return;

            var alive = roster.get().alive;
            var caughtRaw = caughtIds.rawArray();
            for (int i = 0; i < caughtCount; i++) {
                var prey = new Entity(caughtRaw[i]);
                if (world.isAlive(prey)) {
                    world.despawn(prey);
                    alive.remove(prey);
                }
            }
        }

        private final LongArrayList CAUGHT_BUFFER = new LongArrayList(32);

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
