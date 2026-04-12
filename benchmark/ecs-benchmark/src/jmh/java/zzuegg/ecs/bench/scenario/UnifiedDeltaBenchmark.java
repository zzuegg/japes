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
 * <p>With multi-target {@code @Filter}, japes needs <em>three</em>
 * system registrations — one per delta category:
 * <ul>
 *   <li>1 {@code @Filter(Added, target = {State, Health, Mana})}</li>
 *   <li>1 {@code @Filter(Changed, target = {State, Health, Mana})}</li>
 *   <li>1 {@code @Filter(Removed, target = {State, Health, Mana})}
 *       — driven by the removal log with last-value binding</li>
 * </ul>
 *
 * <p>The Added/Changed filters walk the union of dirty lists;
 * Removed walks the removal log. All three deduplicate per entity.
 * This cuts scheduler overhead from 9 dispatches to 3.
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

    public static final class Counters {
        long added, changed, removed;
    }

    // --- Five system registrations (down from nine). ---

    public static final class UnifiedAddedObserver {
        final Counters c;
        UnifiedAddedObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Added.class, target = {State.class, Health.class, Mana.class})
        void observe(@Read State s, @Read Health h, @Read Mana m) {
            c.added++;
        }
    }

    public static final class UnifiedChangedObserver {
        final Counters c;
        UnifiedChangedObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Changed.class, target = {State.class, Health.class, Mana.class})
        void observe(@Read State s, @Read Health h, @Read Mana m) {
            c.changed++;
        }
    }

    // One multi-target @Filter(Removed) observer replaces three
    // RemovedComponents<T> systems. @Read params bind to the last-known
    // values before removal (from the removal log for removed components,
    // from the live entity for still-present components).
    public static final class UnifiedRemovedObserver {
        final Counters c;
        UnifiedRemovedObserver(Counters c) { this.c = c; }

        @System
        @Filter(value = Removed.class, target = {State.class, Health.class, Mana.class})
        void observe(@Read State s, @Read Health h, @Read Mana m) {
            c.removed++;
        }
    }

    @Param({"10000", "100000"})
    int entityCount;

    static final int CHANGE_FRACTION = 10;

    World world;
    Counters counters;
    List<Entity> handles;
    List<Entity> strippedEntities;
    int stateCursor, healthCursor, manaCursor, stripCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new UnifiedAddedObserver(counters))
            .addSystem(new UnifiedChangedObserver(counters))
            .addSystem(new UnifiedRemovedObserver(counters))
            .build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(
                new State(i),
                new Health(1_000),
                new Mana(0)));
        }
        world.tick();
        strippedEntities = new ArrayList<>();
        stateCursor = 0;
        healthCursor = entityCount / 4;
        manaCursor = entityCount / 2;
        stripCursor = 3 * entityCount / 4;
    }

    @TearDown(Level.Iteration)
    public void tearDown() { if (world != null) world.close(); }

    @Benchmark
    public void tick(Blackhole bh) {
        int n = handles.size();
        int addCount = Math.max(1, entityCount / 100);
        int changeCount = Math.max(1, entityCount / CHANGE_FRACTION);
        int removeCount = Math.max(1, entityCount / 100);
        int stripCount = Math.max(1, entityCount / 50);

        // 0. Restore Mana on entities stripped last tick.
        for (var e : strippedEntities) {
            if (world.isAlive(e)) world.addComponent(e, new Mana(0));
        }
        strippedEntities.clear();

        // 1. Spawn 1% new entities.
        for (int i = 0; i < addCount; i++) {
            handles.add(world.spawn(
                new State(n + i),
                new Health(1_000),
                new Mana(0)));
        }

        // 2. Mutate 10% per component via offset cursors.
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

        // 3. Strip Mana from 2%.
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

        // 3 system dispatches: 1 Added + 1 Changed + 1 Removed.
        world.tick();
        bh.consume(counters.added);
        bh.consume(counters.changed);
        bh.consume(counters.removed);
    }
}
