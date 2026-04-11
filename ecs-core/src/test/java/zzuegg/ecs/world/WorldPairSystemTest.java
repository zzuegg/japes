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
 * End-to-end test: a system annotated with {@code @Pair(T.class)}
 * and accepting a {@code PairReader<T>} service parameter runs
 * exactly once per entity that carries at least one pair of the
 * relation type, and the reader's {@code fromSource(self)} call
 * yields every pair on that entity.
 */
class WorldPairSystemTest {

    record Position(float x, float y) {}
    record Attacker(int level) {}
    record Targeting(int power) {}

    /** Record of system invocations, for assertions after tick. */
    static final List<SeenCall> SEEN = new ArrayList<>();
    record SeenCall(Entity self, List<PairReader.Pair<Targeting>> pairs) {}

    public static class DamageSystems {
        @System
        @Pair(Targeting.class)
        public void applyDamage(
            @Read Attacker attacker,
            Entity self,
            PairReader<Targeting> reader
        ) {
            var pairs = new ArrayList<PairReader.Pair<Targeting>>();
            for (var p : reader.fromSource(self)) pairs.add(p);
            SEEN.add(new SeenCall(self, pairs));
        }
    }

    @Test
    void pairSystemRunsPerEntityAndReceivesPairs() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(DamageSystems.class)
            .build();

        var alice   = world.spawn(new Position(0f, 0f), new Attacker(3));
        var bob     = world.spawn(new Position(1f, 1f), new Attacker(5));
        var charlie = world.spawn(new Position(2f, 2f), new Attacker(7));
        // dave is an Attacker but targets nothing — he must not appear.
        var dave    = world.spawn(new Position(9f, 9f), new Attacker(1));

        world.setRelation(alice, bob, new Targeting(10));
        world.setRelation(alice, charlie, new Targeting(20));
        world.setRelation(bob, charlie, new Targeting(30));

        world.tick();

        // alice and bob are the two sources with at least one pair.
        assertEquals(2, SEEN.size(),
            "system must run exactly once per markered entity");

        // Sort by source index so the assertions are order-stable.
        SEEN.sort((a, b) -> Integer.compare(a.self().index(), b.self().index()));

        var aliceCall = SEEN.get(0);
        assertEquals(alice, aliceCall.self());
        assertEquals(2, aliceCall.pairs().size(),
            "alice has two outgoing Targeting pairs");

        var bobCall = SEEN.get(1);
        assertEquals(bob, bobCall.self());
        assertEquals(1, bobCall.pairs().size(),
            "bob has one outgoing Targeting pair");
    }

    @Test
    void entityWithoutPairDoesNotInvokeSystem() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(DamageSystems.class)
            .build();

        // No setRelation calls — no one has the marker.
        world.spawn(new Position(0f, 0f), new Attacker(3));
        world.spawn(new Position(1f, 1f), new Attacker(5));

        world.tick();

        assertEquals(0, SEEN.size(),
            "no entities carry the marker — system body must never run");
    }

    @Test
    void removingLastRelationStopsSystemFromFiringForThatEntity() {
        SEEN.clear();

        var world = World.builder()
            .addSystem(DamageSystems.class)
            .build();

        var alice = world.spawn(new Position(0f, 0f), new Attacker(3));
        var bob   = world.spawn(new Position(1f, 1f), new Attacker(5));

        world.setRelation(alice, bob, new Targeting(10));
        world.tick();
        assertEquals(1, SEEN.size(), "alice runs on tick 1");

        world.removeRelation(alice, bob, Targeting.class);
        SEEN.clear();
        world.tick();
        assertEquals(0, SEEN.size(),
            "after removing alice's only pair, the marker is gone and alice is filtered out");
    }
}
