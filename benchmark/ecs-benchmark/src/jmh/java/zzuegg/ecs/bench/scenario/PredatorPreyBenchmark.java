package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ComponentReader;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.PairReader;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.Pair;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Steady-state predator/prey scenario benchmark — the canonical
 * relation-system workload.
 *
 * <p>Each tick walks every hot path on the relation API:
 * <ul>
 *   <li>{@code Commands.setRelation} — predators with no active
 *       hunt acquire a target.</li>
 *   <li>{@code @Pair(Hunting.class)} + {@code PairReader.fromSource} —
 *       pursuit walks every hunt pair on the current predator.</li>
 *   <li>{@code PairReader.withTarget} — prey counts incoming hunters
 *       via the reverse index.</li>
 *   <li>{@code World.despawn} → {@code RELEASE_TARGET} cleanup —
 *       every catch drops pairs from both forward and reverse
 *       indices and feeds the removal log.</li>
 *   <li>{@code RemovedRelations<Hunting>} — observer drains the log,
 *       driving end-of-tick GC.</li>
 * </ul>
 *
 * <p>To keep the tick cost steady-state the benchmark respawns one
 * fresh prey per catch, so entity counts stay stable across warmup
 * and measurement iterations.
 *
 * <p>Use this as the primary "does adding a relation feature cost
 * me anything per tick" measurement. Typical shape: most of the
 * time is in pursuit (forward walk) + awareness (reverse walk), not
 * in the cleanup path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public class PredatorPreyBenchmark {

    // ------------------ components ------------------

    public record Position(float x, float y) {}
    public record Velocity(float dx, float dy) {}
    public record Predator(int speed) {}
    public record Prey(int alert) {}

    // ------------------ relation ------------------

    @Relation
    public record Hunting(int ticksLeft) {}

    // ------------------ resources ------------------

    public static final class PreyRoster {
        public final List<Entity> alive = new ArrayList<>();
    }

    public static final class Config {
        public float catchDistance = 0.5f;
        public float arenaSize = 20.0f;
        public Random rng = new Random(1234);
    }

    public static final class Counters {
        public long pursuitWalks;
        public long withTargetWalks;
        public long catches;
    }

    public static Blackhole BH;

    /**
     * Experimental switch: when set via
     * {@code -Dzzuegg.ecs.pairIteration=true}, the pursuit pipeline
     * uses a direct pair walk over the relation store instead of the
     * per-entity {@code PairReader.fromSource} loop. Flips the
     * iteration model to compare "call once per entity" vs
     * "call once per pair". Set at benchmark {@code @Setup}.
     */

    // ------------------ systems ------------------

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
                Commands cmds
        ) {
            // @Without(Hunting.class) filters to predators that do
            // NOT carry the Hunting source marker — i.e. the ~50
            // that lost their hunt last tick. The archetype filter
            // handles the "is busy" check for free, so this body
            // runs on far fewer entities than the 500 it would
            // otherwise visit.
            var preyList = roster.get().alive;
            if (preyList.isEmpty()) return;
            var target = preyList.get(config.get().rng.nextInt(preyList.size()));
            if (!world.isAlive(target)) return;
            cmds.setRelation(self, target, new Hunting(3));
        }

        @System
        @Pair(Hunting.class)
        public void pursuit(
                @Read Position selfPos,
                @Write Mut<Velocity> selfVel,
                Entity self,
                PairReader<Hunting> reader,
                ComponentReader<Position> posReader,
                ResMut<Counters> counters
        ) {
            float sumDx = 0f;
            float sumDy = 0f;
            int count = 0;
            for (var pair : reader.fromSource(self)) {
                // posReader.get is a cached fast path: per-chunk
                // storage cache means repeated prey-archetype lookups
                // skip the archetype/chunk/storage indirection entirely.
                var targetPos = posReader.get(pair.target());
                if (targetPos == null) continue;
                sumDx += targetPos.x() - selfPos.x();
                sumDy += targetPos.y() - selfPos.y();
                count++;
            }
            if (count > 0) {
                float avgDx = sumDx / count;
                float avgDy = sumDy / count;
                float mag = (float) Math.sqrt(avgDx * avgDx + avgDy * avgDy);
                if (mag > 1e-4f) {
                    selfVel.set(new Velocity(avgDx / mag * 0.1f, avgDy / mag * 0.1f));
                }
                counters.get().pursuitWalks += count;
            }
            if (BH != null) BH.consume(count);
        }


        @System(after = "pursuit")
        @Pair(value = Hunting.class, role = Pair.Role.TARGET)
        public void awareness(
                @Read Prey preyMarker,
                @Write Mut<Prey> preyWrite,
                Entity self,
                PairReader<Hunting> reader,
                ResMut<Counters> counters
        ) {
            // role = TARGET narrows the archetype filter to prey that
            // have >= 1 incoming Hunting pair — the "am I being
            // hunted?" question is already answered by the filter,
            // so the body only runs on prey that are actually being
            // targeted (~500 out of 2000 at 500-predator steady state).
            int incoming = 0;
            for (var pair : reader.withTarget(self)) incoming++;
            preyWrite.set(new Prey(incoming));
            counters.get().withTargetWalks += incoming;
            if (BH != null) BH.consume(incoming);
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

            // Walk every live pair directly. This visits exactly
            // one (predator, prey) per tick per active hunt — no
            // wasted iteration over idle prey or a per-prey reverse
            // lookup. Position lookups go through the cached
            // ComponentReader for ~3× cheaper per-pair access.
            var caught = new java.util.ArrayList<Entity>();
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
            // Apply despawns after the walk so we don't mutate the
            // forward map during iteration. For small catch counts
            // (~50 per tick at 500×2000), iterating the list of
            // caught entities and removing each one beats the
            // HashSet-based removeAll approach — removeAll ends up
            // calling HashSet.contains on every one of the ~2000
            // alive entries, which costs more than a handful of
            // ArrayList.remove calls.
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

        /**
         * Steady-state: respawn one prey per catch so entity counts
         * and per-tick work stay stable across iterations.
         */
        @System(stage = "PostUpdate", after = "resolveCatches")
        @zzuegg.ecs.system.Exclusive
        public void respawnPrey(
                World world,
                ResMut<PreyRoster> roster,
                Res<Config> config
        ) {
            // Top up to the configured baseline so each tick does the
            // same amount of work. Runs in the same stage as
            // resolveCatches (after it) so the catch→respawn pair is
            // atomic from the next stage's perspective.
            while (roster.get().alive.size() < BASELINE_PREY_COUNT) {
                float x = config.get().rng.nextFloat() * config.get().arenaSize;
                float y = config.get().rng.nextFloat() * config.get().arenaSize;
                var p = world.spawn(new Position(x, y), new Velocity(0f, 0f), new Prey(0));
                roster.get().alive.add(p);
            }
        }

        @System(stage = "Last")
        public void observeCatches(
                RemovedRelations<Hunting> removed,
                ResMut<Counters> counters
        ) {
            for (var r : removed) {
                counters.get().catches++;
                if (BH != null) BH.consume(r.source());
            }
        }
    }

    // ------------------ JMH state ------------------

    @Param({"500"})
    public int predatorCount;

    /** Baseline prey count at steady state. Read by respawnPrey. */
    public static volatile int BASELINE_PREY_COUNT;

    @Param({"2000"})
    public int preyCount;

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

        // Predators cluster in one corner, prey scattered across the arena.
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
                new Position(
                    rng.nextFloat() * config.arenaSize,
                    rng.nextFloat() * config.arenaSize),
                new Velocity(0f, 0f),
                new Prey(0)
            );
            roster.alive.add(e);
        }

        // Warm a handful of ticks so the steady state of "everyone
        // has a hunt pair" is reached before measurement.
        for (int i = 0; i < 5; i++) world.tick();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        BH = bh;
        world.tick();
    }
}
