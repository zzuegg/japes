package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.PairReader;
import zzuegg.ecs.system.Pair;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract for {@code @Pair(role = TARGET)} — a system's
 * archetype filter is automatically narrowed to entities with
 * {@code >= 1 incoming} pair of the named relation type, so the
 * body never sees entities that aren't currently being targeted.
 *
 * <p>Cell being tested: the "awareness" shape of predator/prey
 * workloads. Without {@code role = TARGET}, a prey-awareness system
 * runs on every prey (2000 in the benchmark) and most calls return
 * zero incoming hunters. With {@code role = TARGET}, the filter
 * narrows to the ~500 prey being hunted and skips the rest
 * entirely.
 */
class WorldPairRoleTargetTest {

    record Position(float x, float y) {}
    record Predator() {}
    record Prey() {}
    record Hunting(int focus) {}

    static final List<Entity> SEEN_AS_TARGET = new ArrayList<>();

    public static class AwarenessSystem {
        @System
        @Pair(value = Hunting.class, role = Pair.Role.TARGET)
        public void observe(@Read Prey tag, Entity self, PairReader<Hunting> reader) {
            SEEN_AS_TARGET.add(self);
        }
    }

    @Test
    void targetRoleFiltersToEntitiesWithIncomingPairs() {
        SEEN_AS_TARGET.clear();

        var world = World.builder().addSystem(AwarenessSystem.class).build();

        // Two predators + three prey. Only two prey are actually
        // being hunted; the third is idle.
        var pred1 = world.spawn(new Position(0f, 0f), new Predator());
        var pred2 = world.spawn(new Position(1f, 1f), new Predator());
        var huntedA = world.spawn(new Position(5f, 5f), new Prey());
        var huntedB = world.spawn(new Position(6f, 6f), new Prey());
        var idle    = world.spawn(new Position(7f, 7f), new Prey());

        world.setRelation(pred1, huntedA, new Hunting(1));
        world.setRelation(pred2, huntedB, new Hunting(1));

        world.tick();

        // Only the two prey with incoming pairs should have been
        // visited — `idle` has no target marker, so the archetype
        // filter excludes it.
        assertEquals(2, SEEN_AS_TARGET.size(),
            "system body must run exactly once per prey with incoming pairs");
        assertTrue(SEEN_AS_TARGET.contains(huntedA));
        assertTrue(SEEN_AS_TARGET.contains(huntedB));
        assertFalse(SEEN_AS_TARGET.contains(idle),
            "idle prey has no target marker — system must not see it");
    }

    @Test
    void targetMarkerClearsWhenLastIncomingPairRemoved() {
        SEEN_AS_TARGET.clear();

        var world = World.builder().addSystem(AwarenessSystem.class).build();
        var pred = world.spawn(new Position(0f, 0f), new Predator());
        var prey = world.spawn(new Position(5f, 5f), new Prey());

        world.setRelation(pred, prey, new Hunting(1));
        world.tick();
        assertEquals(1, SEEN_AS_TARGET.size(), "prey visible on first tick");

        // Remove the only incoming pair — the target marker drops
        // and the prey should fall out of the archetype filter on
        // the next tick.
        world.removeRelation(pred, prey, Hunting.class);
        SEEN_AS_TARGET.clear();
        world.tick();
        assertEquals(0, SEEN_AS_TARGET.size(),
            "after losing its last incoming pair, prey must be filtered out");
    }

    @Test
    void targetMarkerHoldsWhileAnyIncomingPairRemains() {
        SEEN_AS_TARGET.clear();

        var world = World.builder().addSystem(AwarenessSystem.class).build();
        var pred1 = world.spawn(new Position(0f, 0f), new Predator());
        var pred2 = world.spawn(new Position(1f, 1f), new Predator());
        var prey  = world.spawn(new Position(5f, 5f), new Prey());

        world.setRelation(pred1, prey, new Hunting(1));
        world.setRelation(pred2, prey, new Hunting(1));

        // Drop pred1's pair but leave pred2's — prey still has a
        // target marker via pred2.
        world.removeRelation(pred1, prey, Hunting.class);
        world.tick();
        assertEquals(1, SEEN_AS_TARGET.size(),
            "target marker must stay while any incoming pair remains");
        assertEquals(prey, SEEN_AS_TARGET.getFirst());
    }
}
