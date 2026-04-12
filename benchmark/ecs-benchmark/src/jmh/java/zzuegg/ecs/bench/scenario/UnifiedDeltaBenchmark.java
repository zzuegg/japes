package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
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
 * <p>Mutations are expressed as systems (not world.getComponent/setComponent),
 * which is how real game logic works. Each mutator system iterates all
 * entities but only writes 1-in-{@code CHANGE_FRACTION} via a rotating
 * counter, producing 10% Changed events per component type per tick.
 *
 * <p>With multi-target {@code @Filter}, japes needs <em>three</em>
 * observer registrations plus <em>three</em> mutator systems — six total:
 * <ul>
 *   <li>3 mutator systems (one per component type)</li>
 *   <li>1 {@code @Filter(Added, target = {State, Health, Mana})}</li>
 *   <li>1 {@code @Filter(Changed, target = {State, Health, Mana})}</li>
 *   <li>1 {@code @Filter(Removed, target = {State, Health, Mana})}</li>
 * </ul>
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

    // --- Mutator systems: iterate all entities, write 1/CHANGE_FRACTION. ---

    public static final class StateMutator {
        int counter;
        final int fraction;
        StateMutator(int fraction) { this.fraction = fraction; }

        @System
        void mutate(@Write Mut<State> s) {
            if (counter++ % fraction == 0) {
                s.set(new State(s.get().value() + 1));
            }
        }
    }

    public static final class HealthMutator {
        int counter;
        final int fraction;
        HealthMutator(int fraction) { this.fraction = fraction; }

        @System
        void mutate(@Write Mut<Health> h) {
            if (counter++ % fraction == 0) {
                h.set(new Health(h.get().hp() - 1));
            }
        }
    }

    public static final class ManaMutator {
        int counter;
        final int fraction;
        ManaMutator(int fraction) { this.fraction = fraction; }

        @System
        void mutate(@Write Mut<Mana> m) {
            if (counter++ % fraction == 0) {
                m.set(new Mana(m.get().points() + 1));
            }
        }
    }

    // --- Observer systems (unchanged). ---

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
    int stripCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new StateMutator(CHANGE_FRACTION))
            .addSystem(new HealthMutator(CHANGE_FRACTION))
            .addSystem(new ManaMutator(CHANGE_FRACTION))
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
        stripCursor = 3 * entityCount / 4;
    }

    @TearDown(Level.Iteration)
    public void tearDown() { if (world != null) world.close(); }

    @Benchmark
    public void tick(Blackhole bh) {
        int n = handles.size();
        int addCount = Math.max(1, entityCount / 100);
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

        // 2. Strip Mana from 2%.
        for (int i = 0; i < stripCount; i++) {
            var e = handles.get(stripCursor % handles.size());
            stripCursor++;
            if (world.getComponent(e, Mana.class) != null) {
                world.removeComponent(e, Mana.class);
                strippedEntities.add(e);
            }
        }

        // 3. Despawn 1% oldest.
        for (int i = 0; i < removeCount && !handles.isEmpty(); i++) {
            world.despawn(handles.removeFirst());
        }

        // 4. Tick: runs 3 mutator systems + 3 observer systems.
        // Mutators iterate all entities, write 10% each via Mut<T>.
        // Observers react to the resulting Added/Changed/Removed events.
        world.tick();
        bh.consume(counters.added);
        bh.consume(counters.changed);
        bh.consume(counters.removed);
    }
}
