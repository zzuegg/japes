package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unified delta observer — one logical observer reacts to added, changed,
 * AND removed entities each tick, across three component types.
 *
 * <p>This is the workload shape Zay-ES is designed for: a single
 * {@code EntitySet} tracking {State, Health, Mana} gives you added /
 * changed / removed delta views from one {@code applyChanges()} call with
 * zero marginal cost per additional component type.  Stripping a single
 * component (e.g. Mana) causes the entity to leave the EntitySet
 * automatically; restoring it causes re-entry.  This structural tracking
 * is free in Zay-ES but requires explicit per-component system
 * registrations in japes.
 *
 * <p>In japes the same logical observer requires <em>nine</em> separate
 * system registrations:
 * <ul>
 *   <li>3 {@code @Filter(Added)}  — one per component, because
 *       re-adding a stripped component is only visible to the filter
 *       targeting that specific type</li>
 *   <li>3 {@code @Filter(Changed)} — one per component type (State,
 *       Health, Mana), since each component has its own dirty list</li>
 *   <li>3 {@code RemovedComponents<T>} — one per component type, each
 *       draining its own section of the removal log</li>
 * </ul>
 * Each system has its own watermark, dirty-list walk, and
 * execution-plan slot.  The scheduler, stage-graph traversal, and
 * dirty-list pruning overhead scales with the number of registered
 * systems, while Zay-ES pays a fixed cost regardless of component count.
 *
 * <p>Per tick the driver (outside systems):
 * <ol>
 *   <li>Restores Mana on entities stripped last tick</li>
 *   <li>Spawns 1% new entities with {State, Health, Mana}</li>
 *   <li>Mutates 10% State, 10% Health, 10% Mana (offset cursors —
 *       30% of entities touched total)</li>
 *   <li>Strips Mana from 2% of entities (component-only removal)</li>
 *   <li>Despawns 1% oldest entities</li>
 * </ol>
 * Then {@code world.tick()} runs all nine observer systems.
 *
 * <p>Counterpart: {@code ZayEsUnifiedDeltaBenchmark} in ecs-benchmark-zayes.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class UnifiedDeltaBenchmark {

    public record State(int value) {}
    public record Health(int hp) {}
    public record Mana(int points) {}

    // --- Counters shared across all observer systems. ---

    public static final class Counters {
        long addedState, addedHealth, addedMana;
        long changedState, changedHealth, changedMana;
        long removedState, removedHealth, removedMana;
    }

    // --- Nine system registrations for what Zay-ES does in one EntitySet. ---

    // 3 × Added observers — one per component, because re-adding a
    //     stripped component (e.g. Mana restored after removal) is only
    //     visible to the @Filter(Added) targeting that exact type.
    //     Zay-ES reports entity re-entry via getAddedEntities() for free.

    public static final class AddedStateObserver {
        final Counters c;
        AddedStateObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Added.class, target = State.class)
        void observe(@Read State s) {
            c.addedState++;
        }
    }

    public static final class AddedHealthObserver {
        final Counters c;
        AddedHealthObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Added.class, target = Health.class)
        void observe(@Read Health h) {
            c.addedHealth++;
        }
    }

    public static final class AddedManaObserver {
        final Counters c;
        AddedManaObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Added.class, target = Mana.class)
        void observe(@Read Mana m) {
            c.addedMana++;
        }
    }

    // 3 × Changed observers — one per component type, since japes tracks
    //     dirty lists per component.  Zay-ES merges all component mutations
    //     into a single getChangedEntities() view for free.

    public static final class ChangedStateObserver {
        final Counters c;
        ChangedStateObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Changed.class, target = State.class)
        void observe(@Read State s) {
            c.changedState++;
        }
    }

    public static final class ChangedHealthObserver {
        final Counters c;
        ChangedHealthObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Changed.class, target = Health.class)
        void observe(@Read Health h) {
            c.changedHealth++;
        }
    }

    public static final class ChangedManaObserver {
        final Counters c;
        ChangedManaObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Changed.class, target = Mana.class)
        void observe(@Read Mana m) {
            c.changedMana++;
        }
    }

    // 3 × Removed observers — one per component type, each draining its
    //     own section of the removal log.  Zay-ES reports entity exit
    //     once via getRemovedEntities() regardless of component count.

    public static final class RemovedStateObserver {
        final Counters c;
        RemovedStateObserver(Counters c) { this.c = c; }

        @System
        void observe(RemovedComponents<State> gone) {
            for (var r : gone) c.removedState++;
        }
    }

    public static final class RemovedHealthObserver {
        final Counters c;
        RemovedHealthObserver(Counters c) { this.c = c; }

        @System
        void observe(RemovedComponents<Health> gone) {
            for (var r : gone) c.removedHealth++;
        }
    }

    public static final class RemovedManaObserver {
        final Counters c;
        RemovedManaObserver(Counters c) { this.c = c; }

        @System
        void observe(RemovedComponents<Mana> gone) {
            for (var r : gone) c.removedMana++;
        }
    }

    @Param({"10000", "100000"})
    int entityCount;

    static final int CHANGE_FRACTION = 10; // 10% per component per tick

    World world;
    Counters counters;
    List<Entity> handles;
    /** Entities whose Mana was stripped last tick — restored at the start of the next. */
    List<Entity> strippedEntities;
    int stateCursor, healthCursor, manaCursor, stripCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new AddedStateObserver(counters))
            .addSystem(new AddedHealthObserver(counters))
            .addSystem(new AddedManaObserver(counters))
            .addSystem(new ChangedStateObserver(counters))
            .addSystem(new ChangedHealthObserver(counters))
            .addSystem(new ChangedManaObserver(counters))
            .addSystem(new RemovedStateObserver(counters))
            .addSystem(new RemovedHealthObserver(counters))
            .addSystem(new RemovedManaObserver(counters))
            .build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(
                new State(i),
                new Health(1_000),
                new Mana(0)));
        }
        // Prime one tick so the observer watermarks are past the initial spawn.
        world.tick();
        strippedEntities = new ArrayList<>();
        stateCursor = 0;
        healthCursor = entityCount / 4;
        manaCursor = entityCount / 2;
        stripCursor = 3 * entityCount / 4;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        int n = handles.size();
        int addCount = Math.max(1, entityCount / 100);
        int changeCount = Math.max(1, entityCount / CHANGE_FRACTION);
        int removeCount = Math.max(1, entityCount / 100);
        int stripCount = Math.max(1, entityCount / 50); // 2%

        // 0. Restore Mana on entities stripped last tick.
        //    Triggers @Filter(Added, Mana) — the entity re-gains the
        //    component.  In Zay-ES this is automatic EntitySet re-entry.
        for (var e : strippedEntities) {
            world.addComponent(e, new Mana(0));
        }
        strippedEntities.clear();

        // 1. Spawn 1% new entities (all three components).
        for (int i = 0; i < addCount; i++) {
            handles.add(world.spawn(
                new State(n + i),
                new Health(1_000),
                new Mana(0)));
        }

        // 2. Mutate 10% per component via offset rotating cursors.
        //    Three disjoint slices — 30% of entities touched total.
        for (int i = 0; i < changeCount; i++) {
            var e = handles.get(stateCursor % handles.size());
            stateCursor++;
            var cur = world.getComponent(e, State.class);
            if (cur != null) world.setComponent(e, new State(cur.value() + 1));
        }
        for (int i = 0; i < changeCount; i++) {
            var e = handles.get(healthCursor % handles.size());
            healthCursor++;
            var cur = world.getComponent(e, Health.class);
            if (cur != null) world.setComponent(e, new Health(cur.hp() - 1));
        }
        for (int i = 0; i < changeCount; i++) {
            var e = handles.get(manaCursor % handles.size());
            manaCursor++;
            var cur = world.getComponent(e, Mana.class);
            if (cur != null) world.setComponent(e, new Mana(cur.points() + 1));
        }

        // 3. Strip Mana from 2% of entities — component-only removal.
        //    The entity stays alive with {State, Health}.  In Zay-ES the
        //    entity automatically leaves the (State,Health,Mana) EntitySet
        //    and appears in getRemovedEntities().  In japes this fires
        //    RemovedComponents<Mana> only — the other two Removed drains
        //    see nothing, but still pay the system-dispatch cost.
        for (int i = 0; i < stripCount; i++) {
            var e = handles.get(stripCursor % handles.size());
            stripCursor++;
            if (world.getComponent(e, Mana.class) != null) {
                world.removeComponent(e, Mana.class);
                strippedEntities.add(e);
            }
        }

        // 4. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            world.despawn(handles.removeFirst());
        }

        // world.tick() runs all NINE observer systems — schedule-graph
        // traversal, nine execution-plan slots, per-system watermarks,
        // three dirty-list walks, and three removal-log drains.
        // Zay-ES does the same work with one applyChanges() call.
        world.tick();
        bh.consume(counters.addedState);
        bh.consume(counters.addedHealth);
        bh.consume(counters.addedMana);
        bh.consume(counters.changedState);
        bh.consume(counters.changedHealth);
        bh.consume(counters.changedMana);
        bh.consume(counters.removedState);
        bh.consume(counters.removedHealth);
        bh.consume(counters.removedMana);
    }
}
