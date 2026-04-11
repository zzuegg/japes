package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@link RemovedRelations}. Parallels the
 * {@code RemovedComponents<T>} story: a system that takes
 * {@code RemovedRelations<T>} observes every pair of type {@code T}
 * that was dropped since the system last ran — once. Multiple
 * consumers each see every removal. Removal sources include direct
 * API calls, cleanup on despawn, and Commands flush.
 */
class WorldRemovedRelationsTest {

    record Position(float x, float y) {}
    record Targeting(int power) {}

    static final List<RemovedRelations.Removal<Targeting>> SEEN = new ArrayList<>();

    public static class Observer {
        @System
        public void observe(RemovedRelations<Targeting> removed) {
            for (var r : removed) SEEN.add(r);
        }
    }

    @Test
    void observerSeesRemovedPairAfterDirectApiCall() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(Observer.class)
            .build();

        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.setRelation(alice, bob, new Targeting(42));

        // First tick: no removal yet.
        world.tick();
        assertEquals(0, SEEN.size(), "no removals yet — observer sees nothing");

        world.removeRelation(alice, bob, Targeting.class);
        world.tick();

        assertEquals(1, SEEN.size());
        var r = SEEN.getFirst();
        assertEquals(alice, r.source());
        assertEquals(bob,   r.target());
        assertEquals(new Targeting(42), r.lastValue(),
            "observer must see the last value at the moment of removal");
    }

    @Test
    void observerSeesEachRemovalExactlyOnce() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(Observer.class)
            .build();

        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.setRelation(alice, bob, new Targeting(42));

        world.removeRelation(alice, bob, Targeting.class);
        world.tick();
        assertEquals(1, SEEN.size(), "seen once on first tick after removal");

        SEEN.clear();
        world.tick();
        assertEquals(0, SEEN.size(),
            "second tick — watermark advanced, nothing new visible");
    }

    @Test
    void observerSeesRemovalCausedByDespawnCleanup() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(Observer.class)
            .build();

        var alice  = world.spawn(new Position(0f, 0f));
        var victim = world.spawn(new Position(1f, 1f));
        world.setRelation(alice, victim, new Targeting(7));

        // despawn the target: the default RELEASE_TARGET policy drops
        // the pair and logs the removal via the store's removal log.
        world.despawn(victim);
        world.tick();

        assertEquals(1, SEEN.size(),
            "despawn cleanup must feed the same PairRemovalLog");
        assertEquals(new Targeting(7), SEEN.getFirst().lastValue());
    }

    @Test
    void observerSeesNothingWhenNoRemovalsHappen() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(Observer.class)
            .build();

        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.setRelation(alice, bob, new Targeting(42));

        world.tick();
        world.tick();

        assertEquals(0, SEEN.size());
    }
}
