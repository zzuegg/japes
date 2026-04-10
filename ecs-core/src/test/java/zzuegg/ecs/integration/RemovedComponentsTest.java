package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.RemovedComponents;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec for the RemovedComponents<T> service parameter. A system declares one
 * or more of these to receive an iterable of (Entity, last-known value) for
 * every removal of the target component that happened since its previous run.
 *
 * Removal sources tracked:
 *  - World.despawn(entity): every component the entity had appears as removed
 *  - World.removeComponent(entity, T): T appears as removed
 *  - Commands.despawn / Commands.remove: routed through the above
 *
 * Per-system watermark: each reader advances its own lastSeenTick, so multiple
 * systems observing the same component each see every removal once.
 */
class RemovedComponentsTest {

    record Health(int hp) {}
    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    static class HealthGraveyard {
        final List<Long> gravedIds = new ArrayList<>();
        final List<Health> lastValues = new ArrayList<>();

        @System
        void bury(RemovedComponents<Health> gone) {
            for (var r : gone) {
                gravedIds.add(r.entity().index() & 0xFFFFFFFFL);
                lastValues.add(r.value());
            }
        }
    }

    @Test
    void removeComponentSurfacesAsRemoved() {
        var graveyard = new HealthGraveyard();
        var world = World.builder().addSystem(graveyard).build();
        var e = world.spawn(new Position(1, 1), new Health(75));

        world.tick(); // flush spawn past the observer's watermark
        assertTrue(graveyard.gravedIds.isEmpty(), "spawn must not surface as removal");

        world.removeComponent(e, Health.class);
        world.tick();

        assertEquals(1, graveyard.gravedIds.size(), "removeComponent must surface");
        assertEquals(new Health(75), graveyard.lastValues.getFirst(),
            "the last-known value at removal time must be available");
    }

    @Test
    void despawnSurfacesAllComponentsAsRemoved() {
        var graveyard = new HealthGraveyard();
        var world = World.builder().addSystem(graveyard).build();
        var a = world.spawn(new Health(10));
        var b = world.spawn(new Health(20));

        world.tick();
        graveyard.gravedIds.clear();
        graveyard.lastValues.clear();

        world.despawn(a);
        world.despawn(b);
        world.tick();

        assertEquals(2, graveyard.gravedIds.size(),
            "despawn of N entities with Health must produce N removal records");
        assertTrue(graveyard.lastValues.contains(new Health(10)));
        assertTrue(graveyard.lastValues.contains(new Health(20)));
    }

    @Test
    void commandDespawnAlsoSurfacesRemovals() {
        var graveyard = new HealthGraveyard();
        var world = World.builder().addSystem(graveyard).build();
        var e = world.spawn(new Health(42));

        world.tick();
        graveyard.gravedIds.clear();
        graveyard.lastValues.clear();

        var cmds = new Commands();
        cmds.despawn(e);
        zzuegg.ecs.command.CommandProcessor.process(cmds.drain(), world);

        world.tick();
        assertEquals(1, graveyard.gravedIds.size(),
            "Commands.despawn → CommandProcessor must surface as removal");
        assertEquals(new Health(42), graveyard.lastValues.getFirst());
    }

    static class MultiComponentWatcher {
        final List<Health> healthGone = new ArrayList<>();
        final List<Position> posGone = new ArrayList<>();

        @System
        void watch(RemovedComponents<Health> h, RemovedComponents<Position> p) {
            for (var r : h) healthGone.add(r.value());
            for (var r : p) posGone.add(r.value());
        }
    }

    @Test
    void systemCanDeclareMultipleRemovedParams() {
        var w = new MultiComponentWatcher();
        var world = World.builder().addSystem(w).build();
        var e = world.spawn(new Position(1, 2), new Health(99));

        world.tick();
        w.healthGone.clear();
        w.posGone.clear();

        world.despawn(e);
        world.tick();

        assertEquals(1, w.healthGone.size());
        assertEquals(1, w.posGone.size());
        assertEquals(new Health(99), w.healthGone.getFirst());
        assertEquals(new Position(1, 2), w.posGone.getFirst());
    }

    static class CountingConsumerA {
        int count;
        @System void obs(RemovedComponents<Health> gone) {
            for (var ignored : gone) count++;
        }
    }
    static class CountingConsumerB {
        int count;
        @System void obs(RemovedComponents<Health> gone) {
            for (var ignored : gone) count++;
        }
    }

    @Test
    void twoConsumersEachSeeEveryRemovalOnce() {
        var a = new CountingConsumerA();
        var b = new CountingConsumerB();
        var world = World.builder().addSystem(a).addSystem(b).build();

        var e1 = world.spawn(new Health(1));
        var e2 = world.spawn(new Health(2));
        world.tick(); // past spawn

        world.despawn(e1);
        world.despawn(e2);
        world.tick();

        assertEquals(2, a.count);
        assertEquals(2, b.count);

        // Second tick with no new removals: both stay stable.
        world.tick();
        assertEquals(2, a.count);
        assertEquals(2, b.count);
    }

    @Test
    void removedEntriesAreGarbageCollectedAfterAllConsumersSee() {
        var graveyard = new HealthGraveyard();
        var world = World.builder().addSystem(graveyard).build();

        for (int i = 0; i < 100; i++) {
            var e = world.spawn(new Health(i));
            world.despawn(e);
        }
        world.tick();
        assertEquals(100, graveyard.gravedIds.size(), "all 100 removals surface");

        graveyard.gravedIds.clear();
        graveyard.lastValues.clear();

        // Next tick: no new removals. The buffer must have been GC'd after
        // every consumer saw each entry — a non-GC implementation would
        // re-iterate the same 100 entries here.
        world.tick();
        assertEquals(0, graveyard.gravedIds.size(), "GC'd removals must not replay");
    }

    static class InvocationCounter {
        int invocations;
        int posSeen;

        @System
        void watch(RemovedComponents<Position> posGone) {
            invocations++;
            for (var ignored : posGone) posSeen++;
        }
    }

    @Test
    void systemRunsEveryTickEvenWhenNothingWasRemoved() {
        var w = new InvocationCounter();
        var world = World.builder().addSystem(w).build();
        // Spawn something irrelevant so the world isn't completely idle.
        world.spawn(new Position(1, 1));

        world.tick();  // #1
        world.tick();  // #2
        world.tick();  // #3

        assertEquals(3, w.invocations,
            "system body must run every tick regardless of removal activity");
        assertEquals(0, w.posSeen, "no removals yet");
    }

    @Test
    void systemRunsAndObservesWhenRemovalHappens() {
        var w = new InvocationCounter();
        var world = World.builder().addSystem(w).build();
        var e = world.spawn(new Position(1, 1), new Health(10));

        world.tick();                       // #1 — sees nothing
        assertEquals(1, w.invocations);
        assertEquals(0, w.posSeen);

        world.removeComponent(e, Position.class);
        world.tick();                       // #2 — sees one
        assertEquals(2, w.invocations);
        assertEquals(1, w.posSeen);

        world.tick();                       // #3 — sees nothing (log GC'd)
        assertEquals(3, w.invocations);
        assertEquals(1, w.posSeen, "removal must not replay on the following tick");
    }

    static class PartialRemovalWatcher {
        final List<Removal<Health>> healthSeen = new ArrayList<>();
        final List<Removal<Position>> posSeen = new ArrayList<>();

        // Import the nested record type alias locally for convenience.
        record Removal<T extends Record>(Entity entity, T value) {}

        @System
        void watch(RemovedComponents<Health> h, RemovedComponents<Position> p) {
            for (var r : h) healthSeen.add(new Removal<>(r.entity(), r.value()));
            for (var r : p) posSeen.add(new Removal<>(r.entity(), r.value()));
        }
    }

    @Test
    void removingOneComponentOfManyOnlyFiresThatReader() {
        var watcher = new PartialRemovalWatcher();
        var world = World.builder().addSystem(watcher).build();
        var e = world.spawn(new Position(3, 4), new Health(77));

        world.tick();
        watcher.healthSeen.clear();
        watcher.posSeen.clear();

        // Remove Position only — Health stays on the entity.
        world.removeComponent(e, Position.class);
        world.tick();

        assertEquals(0, watcher.healthSeen.size(),
            "removing Position must not surface in the Health reader");
        assertEquals(1, watcher.posSeen.size(),
            "removing Position must surface in the Position reader");
        assertEquals(new Position(3, 4), watcher.posSeen.getFirst().value());

        // The entity is still alive — Position removal is not despawn.
        var r = watcher.posSeen.getFirst();
        assertTrue(world.isAlive(r.entity()),
            "entity with only Position removed is still alive");
        assertEquals(new Health(77), world.getComponent(r.entity(), Health.class),
            "Health survives the Position removal intact");
    }

    @Test
    void removedSeenInSameTickAsRemoval() {
        // A system that runs strictly after the removal call (i.e., user code
        // or a preceding system called removeComponent) observes it on its
        // first subsequent run.
        var graveyard = new HealthGraveyard();
        var world = World.builder().addSystem(graveyard).build();
        var e = world.spawn(new Health(7));

        world.tick(); // baseline
        graveyard.gravedIds.clear();
        graveyard.lastValues.clear();

        // removeComponent is called between ticks, and the graveyard observer
        // runs on the next tick.
        world.removeComponent(e, Health.class);
        world.tick();

        assertEquals(1, graveyard.gravedIds.size());
    }
}
