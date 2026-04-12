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
 * zero marginal cost per additional component type.
 *
 * <p>In japes the same logical observer requires <em>seven</em> separate
 * system registrations:
 * <ul>
 *   <li>1 {@code @Filter(Added)}  — for new entities</li>
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
 *   <li>Spawns 1% new entities with {State, Health, Mana}</li>
 *   <li>Mutates 10% State, 10% Health, 10% Mana (offset cursors —
 *       30% of entities touched total)</li>
 *   <li>Despawns 1% oldest entities</li>
 * </ol>
 * Then {@code world.tick()} runs all seven observer systems.
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
        long added, changedState, changedHealth, changedMana;
        long removedState, removedHealth, removedMana;
    }

    // --- Seven system registrations for what Zay-ES does in one EntitySet. ---

    // 1 × Added observer (all three components are spawned together,
    //     so targeting any one of them catches every new entity).

    public static final class AddedObserver {
        final Counters c;
        AddedObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Added.class, target = State.class)
        void observe(@Read State s) {
            c.added++;
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
    //     own section of the removal log.  Zay-ES reports entity removal
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
    int stateCursor, healthCursor, manaCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new AddedObserver(counters))
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
        stateCursor = 0;
        healthCursor = entityCount / 3;
        manaCursor = 2 * entityCount / 3;
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

        // 3. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            world.despawn(handles.removeFirst());
        }

        // world.tick() runs all SEVEN observer systems — schedule-graph
        // traversal, seven execution-plan slots, per-system watermarks,
        // three dirty-list walks, and three removal-log drains.
        // Zay-ES does the same work with one applyChanges() call.
        world.tick();
        bh.consume(counters.added);
        bh.consume(counters.changedState);
        bh.consume(counters.changedHealth);
        bh.consume(counters.changedMana);
        bh.consume(counters.removedState);
        bh.consume(counters.removedHealth);
        bh.consume(counters.removedMana);
    }
}
