package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.SpawnBuilder;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unified delta observer — one logical observer reacts to added, changed,
 * AND removed entities each tick, across three component types.
 *
 * <p>Mutations are expressed as systems that read component values and
 * conditionally write — the same pattern as real game logic (damage-over-time,
 * mana regeneration, animation ticks). Each mutator iterates all entities
 * but only writes the ~10% that meet its game-logic condition, producing
 * Changed events that the observers react to.
 *
 * <p>This workload favors Zay-ES's {@code EntitySet} model because the
 * dirty-set only touches changed entities, while japes mutator systems
 * must iterate all entities to evaluate the condition. At large entity
 * counts the sequential SoA iteration advantage closes the gap.
 *
 * <p>System registrations:
 * <ul>
 *   <li>3 mutator systems (conditional write per component type)</li>
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

    // --- Mutator systems: game-logic conditionals, ~10% write rate. ---

    /** Damage-over-time: entities above 900 HP take 1 damage per tick. */
    public static final class DamageOverTime {
        @System
        void tick(@Write Mut<Health> h) {
            if (h.get().hp() > 900) {
                h.set(new Health(h.get().hp() - 1));
            }
        }
    }

    /** Mana regeneration: entities below 100 mana regenerate 1 per tick. */
    public static final class ManaRegen {
        @System
        void tick(@Write Mut<Mana> m) {
            if (m.get().points() < 100) {
                m.set(new Mana(m.get().points() + 1));
            }
        }
    }

    /** Animation tick: advances frame counter for entities whose state is
     *  a multiple of 10 (simulates conditional animation updates). */
    public static final class AnimationTick {
        @System
        void tick(@Write Mut<State> s) {
            if (s.get().value() % 10 == 0) {
                s.set(new State(s.get().value() + 1));
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

    World world;
    SpawnBuilder spawner;
    Counters counters;
    List<Entity> handles;
    List<Entity> strippedEntities;
    int stripCursor;

    @Setup(Level.Iteration)
    public void setup() {
        counters = new Counters();
        world = World.builder()
            .addSystem(new DamageOverTime())
            .addSystem(new ManaRegen())
            .addSystem(new AnimationTick())
            .addSystem(new UnifiedAddedObserver(counters))
            .addSystem(new UnifiedChangedObserver(counters))
            .addSystem(new UnifiedRemovedObserver(counters))
            .build();
        spawner = world.spawnBuilder(State.class, Health.class, Mana.class);
        handles = new ArrayList<>(entityCount);
        // Distribute values so ~10% meet each mutator's condition:
        //   Health: 90% in [100,900], 10% in [901,1000] → DamageOverTime fires
        //   Mana:   90% in [100,500], 10% in [0,99]     → ManaRegen fires
        //   State:  10% are multiples of 10              → AnimationTick fires
        var rng = new java.util.Random(42);
        for (int i = 0; i < entityCount; i++) {
            int hp = (i % 10 == 0) ? 901 + rng.nextInt(100) : 100 + rng.nextInt(800);
            int mp = (i % 10 == 1) ? rng.nextInt(100) : 100 + rng.nextInt(400);
            int st = (i % 10 == 2) ? i * 10 : i * 10 + 1; // ~10% are multiples of 10
            handles.add(spawner.spawn(new State(st), new Health(hp), new Mana(mp)));
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

        // 1. Spawn 1% new entities via pre-resolved SpawnBuilder.
        for (int i = 0; i < addCount; i++) {
            handles.add(spawner.spawn(
                new State(n + i),
                new Health(1_000),
                new Mana(0)));
        }

        // 2. Strip Mana from 2%.
        for (int i = 0; i < stripCount; i++) {
            var e = handles.get(stripCursor % handles.size());
            stripCursor++;
            if (world.hasComponent(e, Mana.class)) {
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
