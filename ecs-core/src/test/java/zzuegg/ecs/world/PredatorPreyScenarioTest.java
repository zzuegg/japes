package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end predator/prey simulation exercising every relation
 * primitive end-to-end:
 *
 * <ul>
 *   <li>{@code Commands.setRelation} in a parallel system to acquire a hunt target</li>
 *   <li>{@code @Pair(Hunting.class)} + {@code PairReader.fromSource(self)}
 *       in the pursuit system — the core "who am I hunting"
 *       iteration pattern the design was built for</li>
 *   <li>{@code PairReader.withTarget(self)} in the prey awareness
 *       system to count incoming pursuit pressure — the core
 *       "who is hunting me" lookup (reverse index hot path)</li>
 *   <li>{@code World.despawn} on a caught prey triggers the
 *       default {@code RELEASE_TARGET} cleanup that drops the
 *       corresponding {@code Hunting} pair from the predator's
 *       forward index</li>
 *   <li>{@code RemovedRelations<Hunting>} drains the removal events
 *       so we can assert on the actual number of catches</li>
 * </ul>
 *
 * <p>The simulation is tiny and deterministic — a fixed RNG seed,
 * a bounded tick count, and an assertion on final counters so this
 * is safe to run as a regular unit test.
 */
class PredatorPreyScenarioTest {

    // ------------------ components ------------------

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Predator(int speed) {}
    record Prey(int alert) {}

    // ------------------ relation ------------------

    @Relation  // default onTargetDespawn = RELEASE_TARGET
    record Hunting(int ticksLeft) {}

    // ------------------ resources ------------------

    static final class PreyRoster {
        final List<Entity> alive = new ArrayList<>();
    }

    static final class Config {
        final float catchDistance = 0.5f;
        final Random rng = new Random(1234);
    }

    static final class Counters {
        long catches;           // observed via RemovedRelations
        long pursuitTicks;      // incremented per pair walked by Pursuit
        long maxIncomingOnPrey; // high-water mark of withTarget count
    }

    // ------------------ systems ------------------

    public static class Systems {

        /** All entities drift. Positions are updated in place. */
        @System
        public void movement(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
        }

        /**
         * Each predator without a current Hunting pair picks a random
         * prey from the roster. Uses {@code Commands.setRelation} so
         * the mutation flushes at the stage boundary.
         *
         * <p>Note: {@code PairReader<Hunting>} alone does NOT add the
         * marker to the archetype filter — only {@code @Pair} does.
         * That means this system still runs on idle predators (which
         * have no Hunting pairs yet), and {@code reader.fromSource(self)}
         * returns empty for them. Exactly what we need to detect the
         * "no active hunt" state.
         */
        @System(after = "movement")
        public void acquireHunt(
                @Read Predator pred,
                Entity self,
                PairReader<Hunting> reader,
                Res<PreyRoster> roster,
                Res<Config> config,
                zzuegg.ecs.world.World world,
                Commands cmds
        ) {
            // Skip predators that already have a hunt pair.
            if (reader.hasSource(self)) return;

            var preyList = roster.get().alive;
            if (preyList.isEmpty()) return;
            var target = preyList.get(config.get().rng.nextInt(preyList.size()));
            if (!world.isAlive(target)) return;
            cmds.setRelation(self, target, new Hunting(3));
        }

        /**
         * Predators with active hunts steer toward the centroid of
         * their prey's positions. Demonstrates
         * {@code @Pair(Hunting.class)} narrowing the archetype filter
         * to markered predators and {@code PairReader.fromSource(self)}
         * walking every hunt pair from this entity.
         *
         * <p>{@code @Read Position} is used both as self's steering
         * origin and as the scheduler-visible Position read that
         * serialises this system against {@code movement}'s
         * {@code Mut<Position>} write. (Target positions are fetched
         * per-pair via {@code world.getComponent}, which is safe
         * because movement already ran and flushed.)
         */
        @System
        @Pair(Hunting.class)
        public void pursuit(
                @Read Position selfPos,
                @Write Mut<Velocity> selfVel,
                Entity self,
                PairReader<Hunting> reader,
                ResMut<Counters> counters,
                zzuegg.ecs.world.World world
        ) {
            float sumDx = 0f;
            float sumDy = 0f;
            int count = 0;
            for (var pair : reader.fromSource(self)) {
                if (!world.isAlive(pair.target())) continue;
                var targetPos = world.getComponent(pair.target(), Position.class);
                sumDx += targetPos.x() - selfPos.x();
                sumDy += targetPos.y() - selfPos.y();
                count++;
            }
            if (count > 0) {
                // Normalise and apply a small acceleration.
                float avgDx = sumDx / count;
                float avgDy = sumDy / count;
                float mag = (float) Math.sqrt(avgDx * avgDx + avgDy * avgDy);
                if (mag > 1e-4f) {
                    selfVel.set(new Velocity(avgDx / mag * 0.1f, avgDy / mag * 0.1f));
                }
                counters.get().pursuitTicks += count;
            }
        }

        /**
         * Each prey counts the number of predators currently hunting
         * it via {@code reader.withTarget(self)}. Demonstrates the
         * reverse-index hot path. The count is written back as the
         * prey's new alert level so we can assert on it.
         */
        @System(after = "pursuit")
        public void awareness(
                @Read Prey preyMarker,
                @Write Mut<Prey> preyWrite,
                Entity self,
                PairReader<Hunting> reader,
                ResMut<Counters> counters
        ) {
            int incoming = 0;
            for (var pair : reader.withTarget(self)) incoming++;
            preyWrite.set(new Prey(incoming));
            if (incoming > counters.get().maxIncomingOnPrey) {
                counters.get().maxIncomingOnPrey = incoming;
            }
        }

        /**
         * Walks every live predator and despawns any prey it's close
         * enough to catch. Despawning the prey triggers the default
         * {@code RELEASE_TARGET} cleanup which drops the matching
         * {@code Hunting} pair from the predator's forward index and
         * fires a {@code RemovedRelations<Hunting>} event.
         *
         * <p>Runs as an exclusive system so no other system races it
         * while it mutates world state via {@code despawn}.
         */
        @System(stage = "PostUpdate")
        @zzuegg.ecs.system.Exclusive
        public void resolveCatches(
                zzuegg.ecs.world.World world,
                ResMut<PreyRoster> roster,
                Res<Config> config
        ) {
            var store = world.componentRegistry().relationStore(Hunting.class);
            if (store == null) return;
            float catchDist = config.get().catchDistance;

            // Snapshot both roster and sources-of-hunt so we can
            // mutate during iteration.
            var preyList = new ArrayList<>(roster.get().alive);
            for (var prey : preyList) {
                if (!world.isAlive(prey)) continue;
                var preyPos = world.getComponent(prey, Position.class);
                // Walk every predator hunting this prey.
                var predators = new ArrayList<Entity>();
                for (var p : store.sourcesFor(prey)) predators.add(p);
                for (var predator : predators) {
                    if (!world.isAlive(predator)) continue;
                    var predPos = world.getComponent(predator, Position.class);
                    float dx = predPos.x() - preyPos.x();
                    float dy = predPos.y() - preyPos.y();
                    if (dx * dx + dy * dy <= catchDist * catchDist) {
                        world.despawn(prey);
                        roster.get().alive.remove(prey);
                        break;  // prey is gone; skip remaining predators
                    }
                }
            }
        }

        /**
         * Drains {@code RemovedRelations<Hunting>} and accumulates
         * into {@code Counters.catches}.
         */
        @System(stage = "Last")
        public void observeCatches(
                RemovedRelations<Hunting> removed,
                ResMut<Counters> counters
        ) {
            for (var r : removed) counters.get().catches++;
        }
    }

    @Test
    void runsFullPipelineCorrectly() {
        var roster = new PreyRoster();
        var config = new Config();
        var counters = new Counters();

        var world = World.builder()
            .addResource(roster)
            .addResource(config)
            .addResource(counters)
            .addSystem(Systems.class)
            .build();

        // Setup: 3 predators in one corner, 6 prey clustered in the
        // opposite corner. Predators move toward prey, acquire hunts,
        // and eventually catch them.
        var rng = new Random(42);
        for (int i = 0; i < 3; i++) {
            world.spawn(
                new Position(0f, 0f),
                new Velocity(0.1f, 0.1f),
                new Predator(1)
            );
        }
        for (int i = 0; i < 6; i++) {
            var prey = world.spawn(
                new Position(2f + rng.nextFloat() * 0.5f, 2f + rng.nextFloat() * 0.5f),
                new Velocity(0f, 0f),
                new Prey(0)
            );
            roster.alive.add(prey);
        }

        // Initial state sanity.
        assertEquals(6, roster.alive.size());
        assertEquals(0, counters.catches);

        // Tick until every prey is caught or we run out of time.
        for (int tickNum = 0; tickNum < 200 && !roster.alive.isEmpty(); tickNum++) {
            world.tick();
        }

        // Every prey should have been caught — assert the sim ran to
        // completion rather than getting stuck.
        assertTrue(roster.alive.isEmpty(),
            "expected every prey to be caught; still alive: " + roster.alive.size());

        // Every despawn must have produced at least one
        // RemovedRelations event (RELEASE_TARGET cleanup).
        // Note: counters.catches can exceed 6 because multiple
        // predators may have been hunting the same prey at the moment
        // of death, and each Hunting pair generates its own removal
        // event.
        assertTrue(counters.catches >= 6,
            "observer must see at least one RemovedRelations event per prey death; got "
                + counters.catches);

        // Pursuit walked pairs at least a few times — proves @Pair
        // + PairReader.fromSource hot path fired.
        assertTrue(counters.pursuitTicks > 0,
            "pursuit system must have walked at least one Hunting pair");

        // At some point at least one prey saw an incoming hunter
        // via withTarget — proves the reverse index hot path fired.
        assertTrue(counters.maxIncomingOnPrey >= 1,
            "at least one prey must have observed an incoming hunter via withTarget");
    }

    @Test
    void cleanupMaintainsStoreInvariants() {
        // Separate test: after prey are all caught, the Hunting store
        // must be empty. Verifies the RELEASE_TARGET cleanup path
        // doesn't leave dangling pairs.
        var roster = new PreyRoster();
        var config = new Config();
        var counters = new Counters();

        var world = World.builder()
            .addResource(roster)
            .addResource(config)
            .addResource(counters)
            .addSystem(Systems.class)
            .build();

        for (int i = 0; i < 3; i++) {
            world.spawn(new Position(0f, 0f), new Velocity(0.1f, 0.1f), new Predator(1));
        }
        for (int i = 0; i < 4; i++) {
            var prey = world.spawn(new Position(1.5f, 1.5f), new Velocity(0f, 0f), new Prey(0));
            roster.alive.add(prey);
        }

        for (int t = 0; t < 200 && !roster.alive.isEmpty(); t++) world.tick();

        var store = world.componentRegistry().relationStore(Hunting.class);
        assertNotNull(store);
        assertEquals(0, store.size(),
            "all pairs must be cleaned up after every target was despawned");
    }
}
