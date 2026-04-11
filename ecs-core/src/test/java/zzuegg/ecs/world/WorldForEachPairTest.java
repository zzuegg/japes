package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.FromTarget;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@code @ForEachPair} dispatch. The world
 * walks the relation store directly and calls the user method once
 * per live pair, binding source-side components, target-side
 * components, entity handles, and the pair payload from the
 * matching positions.
 *
 * <p>Starts with a reflective (tier-3) processor — these tests pin
 * the external contract and keep passing when tier-1 bytecode gen
 * lands later.
 */
class WorldForEachPairTest {

    record Position(float x, float y) {}
    record Predator() {}
    record Prey() {}
    record Hunting(int power) {}

    /** Per-invocation snapshot so tests can inspect visits. */
    record Visit(Entity source, Entity target, Hunting hunting,
                 Position sourcePos, Position targetPos) {}

    static final List<Visit> SEEN = new ArrayList<>();

    public static class PursuitSystem {
        @System
        @ForEachPair(Hunting.class)
        public void visit(
                @Read Position sourcePos,
                @FromTarget @Read Position targetPos,
                Hunting hunting,
                Entity source,
                @FromTarget Entity target
        ) {
            SEEN.add(new Visit(source, target, hunting, sourcePos, targetPos));
        }
    }

    @Test
    void visitsEveryLivePairExactlyOnce() {
        SEEN.clear();
        var world = World.builder().addSystem(PursuitSystem.class).build();

        var pred1 = world.spawn(new Position(0f, 0f), new Predator());
        var pred2 = world.spawn(new Position(1f, 0f), new Predator());
        var prey1 = world.spawn(new Position(5f, 5f), new Prey());
        var prey2 = world.spawn(new Position(6f, 6f), new Prey());

        world.setRelation(pred1, prey1, new Hunting(10));
        world.setRelation(pred1, prey2, new Hunting(20));
        world.setRelation(pred2, prey2, new Hunting(30));

        world.tick();

        assertEquals(3, SEEN.size(),
            "@ForEachPair must visit every live pair exactly once");

        // Verify source/target binding correctness per visit — each
        // triple must match the setRelation calls above.
        var asSet = new java.util.HashSet<String>();
        for (var v : SEEN) {
            asSet.add(v.source() + "->" + v.target() + ":" + v.hunting().power());
        }
        assertTrue(asSet.contains(pred1 + "->" + prey1 + ":10"));
        assertTrue(asSet.contains(pred1 + "->" + prey2 + ":20"));
        assertTrue(asSet.contains(pred2 + "->" + prey2 + ":30"));
    }

    @Test
    void sourceSideComponentsResolveAgainstSourceEntity() {
        SEEN.clear();
        var world = World.builder().addSystem(PursuitSystem.class).build();

        var pred = world.spawn(new Position(3.5f, 7.5f), new Predator());
        var prey = world.spawn(new Position(99f, 99f), new Prey());
        world.setRelation(pred, prey, new Hunting(42));

        world.tick();

        assertEquals(1, SEEN.size());
        var v = SEEN.getFirst();
        assertEquals(new Position(3.5f, 7.5f), v.sourcePos(),
            "source-side @Read must come from the source entity's archetype");
        assertEquals(new Position(99f, 99f), v.targetPos(),
            "target-side @FromTarget @Read must come from the target entity's archetype");
    }

    @Test
    void noPairsMeansBodyNeverRuns() {
        SEEN.clear();
        var world = World.builder().addSystem(PursuitSystem.class).build();
        // Spawn entities but no relations.
        world.spawn(new Position(0f, 0f), new Predator());
        world.spawn(new Position(5f, 5f), new Prey());

        world.tick();

        assertEquals(0, SEEN.size(), "no live pairs — body must not run");
    }

    record Velocity(float dx, float dy) {}

    public static class SourceWriteSystem {
        @System
        @ForEachPair(Hunting.class)
        public void steerToward(
                @Read Position sourcePos,
                @FromTarget @Read Position targetPos,
                @Write Mut<Velocity> sourceVel,
                Hunting hunting
        ) {
            float dx = targetPos.x() - sourcePos.x();
            float dy = targetPos.y() - sourcePos.y();
            sourceVel.set(new Velocity(dx, dy));
        }
    }

    @Test
    void sourceWriteBackPersistsAfterTick() {
        var world = World.builder().addSystem(SourceWriteSystem.class).build();
        var pred = world.spawn(new Position(0f, 0f), new Predator(), new Velocity(0f, 0f));
        var prey = world.spawn(new Position(3f, 4f), new Prey());
        world.setRelation(pred, prey, new Hunting(1));

        world.tick();

        assertEquals(new Velocity(3f, 4f), world.getComponent(pred, Velocity.class),
            "@Write Mut<Velocity> on source must flush back to the source's storage");
    }

    // Accumulator for the multi-pair test.
    static int SOURCE_CALL_COUNT;

    public static class MultiPairCountingSystem {
        @System
        @ForEachPair(Hunting.class)
        public void count(@Read Position sourcePos, Hunting hunting) {
            SOURCE_CALL_COUNT++;
        }
    }

    @Test
    void oneSourceWithMultiplePairsVisitsEachPair() {
        SOURCE_CALL_COUNT = 0;
        var world = World.builder().addSystem(MultiPairCountingSystem.class).build();
        var pred = world.spawn(new Position(0f, 0f), new Predator());
        var prey1 = world.spawn(new Position(1f, 0f), new Prey());
        var prey2 = world.spawn(new Position(2f, 0f), new Prey());
        var prey3 = world.spawn(new Position(3f, 0f), new Prey());

        world.setRelation(pred, prey1, new Hunting(1));
        world.setRelation(pred, prey2, new Hunting(2));
        world.setRelation(pred, prey3, new Hunting(3));

        world.tick();

        assertEquals(3, SOURCE_CALL_COUNT,
            "a single source with 3 pairs must cause 3 body invocations");
    }

    // Observer that counts ForEachPair invocations across ticks.
    static int OBSERVER_CALLS;
    static int OBSERVED_POWER;

    public static class ServiceParamSystem {
        @System
        @ForEachPair(Hunting.class)
        public void observe(
                @Read Position sourcePos,
                Hunting hunting,
                ResMut<Counter> counter
        ) {
            OBSERVER_CALLS++;
            OBSERVED_POWER += hunting.power();
            counter.get().n++;
        }
    }

    public static final class Counter { public int n; }

    @Test
    void serviceParamsResolveAndAreSharedAcrossPairs() {
        OBSERVER_CALLS = 0;
        OBSERVED_POWER = 0;
        var counter = new Counter();
        var world = World.builder()
            .addResource(counter)
            .addSystem(ServiceParamSystem.class)
            .build();
        var pred = world.spawn(new Position(0f, 0f), new Predator());
        var a = world.spawn(new Position(1f, 0f), new Prey());
        var b = world.spawn(new Position(2f, 0f), new Prey());
        world.setRelation(pred, a, new Hunting(10));
        world.setRelation(pred, b, new Hunting(20));

        world.tick();

        assertEquals(2, OBSERVER_CALLS);
        assertEquals(30, OBSERVED_POWER, "payload binding must pick per-pair values");
        assertEquals(2, counter.n,
            "ResMut<Counter> service param must resolve once and accumulate across pairs");
    }
}
