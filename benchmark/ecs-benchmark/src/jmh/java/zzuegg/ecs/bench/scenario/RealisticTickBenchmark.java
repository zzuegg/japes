package zzuegg.ecs.bench.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Realistic multi-observer game tick — the benchmark change detection is
 * actually designed for.
 *
 * 10 000 entities with {Position, Velocity, Health, Mana}. Each tick the
 * driver mutates 100 entities' Health, 100 entities' Mana and 100 entities'
 * Position (rotating cursors — entirely different slices), then the world
 * ticks three observer systems that each react to {@code @Filter(Changed)}
 * of one of those components.
 *
 * The japes observers walk exactly the dirty view (300 total entity
 * touches per tick). In the Dominion/Artemis counterparts the observers
 * have no way to know what's dirty so they walk the full world (30 000
 * entity touches per tick). That ~100× work gap is the change-detection
 * win — the library earns it for you by tracking writes automatically.
 *
 * {@code @Param executor} = "st" or "mt":
 *   - st: single-threaded scheduler.
 *   - mt: japes {@code MultiThreadedExecutor}, which runs the three
 *         observers in parallel inside the {@code PostUpdate} stage
 *         because their reads are disjoint (different components). The
 *         Dominion/Artemis counterparts implement their own "mt" via
 *         {@link java.util.concurrent.ExecutorService} for a fair fight.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class RealisticTickBenchmark {

    public record Position(float x, float y, float z) {}
    public record Velocity(float dx, float dy, float dz) {}
    public record Health(int hp) {}
    public record Mana(int points) {}

    @Param({"10000"})
    int entityCount;

    @Param({"st", "mt"})
    String executor;

    static final int BATCH = 100;

    public static final class Stats {
        long sumX, sumHp, sumMana;
    }

    // ---- Observer systems: each reacts to Changed<C> for one component.
    //      They have no mutual conflicts (disjoint reads) so the japes
    //      scheduler runs them in parallel under multiThreaded().

    public static final class PositionObserver {
        final Stats stats;
        PositionObserver(Stats stats) { this.stats = stats; }

        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Position.class)
        void observe(@Read Position p) {
            stats.sumX += (long) p.x();
        }
    }

    public static final class HealthObserver {
        final Stats stats;
        HealthObserver(Stats stats) { this.stats = stats; }

        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Health.class)
        void observe(@Read Health h) {
            stats.sumHp += h.hp();
        }
    }

    public static final class ManaObserver {
        final Stats stats;
        ManaObserver(Stats stats) { this.stats = stats; }

        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Mana.class)
        void observe(@Read Mana m) {
            stats.sumMana += m.points();
        }
    }

    World world;
    Stats stats;
    List<Entity> handles;
    int positionCursor, healthCursor, manaCursor;

    @Setup(Level.Iteration)
    public void setup() {
        stats = new Stats();
        var exec = switch (executor) {
            case "st" -> Executors.singleThreaded();
            case "mt" -> Executors.multiThreaded();
            default -> throw new IllegalArgumentException(executor);
        };
        world = World.builder()
            .executor(exec)
            .addSystem(new PositionObserver(stats))
            .addSystem(new HealthObserver(stats))
            .addSystem(new ManaObserver(stats))
            .build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Health(1_000_000),
                new Mana(0)));
        }
        // Prime one tick so the Changed trackers have a baseline.
        world.tick();
        positionCursor = 0;
        healthCursor = BATCH;      // offset cursors so the three slices don't overlap
        manaCursor = 2 * BATCH;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // Driver: 300 sparse mutations — 100 each of Position/Health/Mana.
        // Rotating cursors touch different slices each tick so the dirty
        // set turns over.
        int n = handles.size();
        for (int i = 0; i < BATCH; i++) {
            var e = handles.get(positionCursor);
            positionCursor = (positionCursor + 1) % n;
            var p = world.getComponent(e, Position.class);
            world.setComponent(e, new Position(p.x() + 1, p.y(), p.z()));
        }
        for (int i = 0; i < BATCH; i++) {
            var e = handles.get(healthCursor);
            healthCursor = (healthCursor + 1) % n;
            var h = world.getComponent(e, Health.class);
            world.setComponent(e, new Health(h.hp() - 1));
        }
        for (int i = 0; i < BATCH; i++) {
            var e = handles.get(manaCursor);
            manaCursor = (manaCursor + 1) % n;
            var m = world.getComponent(e, Mana.class);
            world.setComponent(e, new Mana(m.points() + 1));
        }

        // World tick — runs the three @Filter(Changed) observers. Under
        // multiThreaded() the scheduler fans them out across the FJP because
        // their reads are disjoint.
        world.tick();
        bh.consume(stats.sumX);
        bh.consume(stats.sumHp);
        bh.consume(stats.sumMana);
    }
}
