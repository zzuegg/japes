package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted tests for relation subsystem bugs. Each test exposes one
 * concrete defect and is expected to FAIL against the current code.
 *
 * Root cause: World.java's @ForEachPair execution paths (both tier-1
 * generated runner at ~line 1339-1345 and tier-2 reflective processor
 * at ~line 1347-1354) return without calling plan.markExecuted(). This
 * means the plan's lastSeenTick watermark is never advanced, causing:
 *
 * 1. RemovedRelations re-delivers the same removal events every tick
 *    (snapshot reads entries with tick > lastSeenTick, but lastSeenTick
 *    stays at 0 forever).
 *
 * 2. PairRemovalLog entries are never garbage-collected (the end-of-tick
 *    GC pass computes minWatermark from plan.lastSeenTick() across all
 *    consumers; a stuck-at-0 watermark prevents collectGarbage from
 *    pruning anything).
 */
class RelationBugTest {

    record Pos(float x, float y) {}

    @Relation
    record Hunts(int power) {}

    // ---- accumulators ----

    static final List<RemovedRelations.Removal<Hunts>> REMOVED = new ArrayList<>();

    // ================================================================
    // Bug 1: @ForEachPair + RemovedRelations re-delivers every tick
    // ================================================================

    public static class PairSystemWithRemovedRelations {
        @System
        @ForEachPair(Hunts.class)
        public void visit(
                @Read Pos sourcePos,
                Hunts hunting,
                RemovedRelations<Hunts> removed
        ) {
            for (var r : removed) {
                REMOVED.add(r);
            }
        }
    }

    /**
     * A @ForEachPair system that also reads RemovedRelations should
     * see each removal exactly once. Because markExecuted is never
     * called for @ForEachPair paths, the watermark stays at 0 and
     * the same removals are re-delivered on every subsequent tick.
     */
    @Test
    void removedRelationsInForEachPairDeliveredOnlyOnce() {
        REMOVED.clear();

        var world = World.builder()
                .addSystem(PairSystemWithRemovedRelations.class)
                .build();

        var a = world.spawn(new Pos(0, 0));
        var b = world.spawn(new Pos(1, 1));
        var c = world.spawn(new Pos(2, 2));

        world.setRelation(a, b, new Hunts(10));
        world.setRelation(a, c, new Hunts(20));

        // Tick 1: both pairs alive, no removals yet.
        world.tick();
        assertEquals(0, REMOVED.size(), "no removals yet");

        // Remove one relation between ticks.
        world.removeRelation(a, b, Hunts.class);

        // Tick 2: the removal should be visible exactly once.
        world.tick();
        int afterTick2 = REMOVED.size();
        assertEquals(1, afterTick2, "removal of a->b must be delivered once");

        // Tick 3: no new removals. The same event must NOT re-appear.
        REMOVED.clear();
        world.tick();
        assertEquals(0, REMOVED.size(),
                "removal must not be re-delivered on subsequent ticks; "
                        + "watermark was not advanced because @ForEachPair path "
                        + "never calls plan.markExecuted()");
    }

    // ================================================================
    // Bug 2: PairRemovalLog memory leak for @ForEachPair consumers
    // ================================================================

    /**
     * After a removal is consumed and several ticks pass, the removal
     * log entry should be garbage-collected. Because plan.lastSeenTick()
     * is stuck at 0 for @ForEachPair systems, the GC pass computes
     * minWatermark=0, which is a no-op, so entries accumulate forever.
     */
    @Test
    void pairRemovalLogGarbageCollectedForForEachPairConsumers() {
        REMOVED.clear();

        var world = World.builder()
                .addSystem(PairSystemWithRemovedRelations.class)
                .build();

        var a = world.spawn(new Pos(0, 0));
        var b = world.spawn(new Pos(1, 1));
        var c = world.spawn(new Pos(2, 2));

        world.setRelation(a, b, new Hunts(10));
        world.setRelation(a, c, new Hunts(20));

        world.tick(); // tick 1: establish pairs

        world.removeRelation(a, b, Hunts.class);

        world.tick(); // tick 2: removal visible

        // After several more ticks, the removal log entry from tick 2
        // must have been garbage-collected.
        for (int i = 0; i < 5; i++) world.tick();

        var store = world.componentRegistry().relationStore(Hunts.class);
        assertNotNull(store);
        // snapshot(0) returns everything still in the log.
        // If GC worked, old entries should be gone.
        var remaining = store.removalLog().snapshot(0);
        assertEquals(0, remaining.size(),
                "removal log must be GC'd after all consumers advanced; "
                        + "but @ForEachPair path never advances the watermark");
    }
}
