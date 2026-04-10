package zzuegg.ecs.bench.valhalla.scenario;

import jdk.internal.vm.annotation.LooselyConsistentValue;
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
 * Valhalla variant of {@code RealisticTickBenchmark}. Components are
 * {@code value record}s so Valhalla can flatten Position/Velocity/Health/Mana
 * into the backing storage. The rest of the shape (100 dirty per component
 * per tick, three @Filter(Changed) observers) is identical to the
 * non-Valhalla benchmark — so `st` and `mt` numbers are directly comparable.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class RealisticTickBenchmarkValhalla {

    // @LooselyConsistentValue opts into the flat-array layout — without it
    // the VM returns a non-flat reference array even for newNullRestricted
    // storage. See DefaultComponentStorage for the flat-allocator probe.
    @LooselyConsistentValue public value record Position(float x, float y, float z) {}
    @LooselyConsistentValue public value record Velocity(float dx, float dy, float dz) {}
    @LooselyConsistentValue public value record Health(int hp) {}
    @LooselyConsistentValue public value record Mana(int points) {}

    @Param({"10000"})
    int entityCount;

    @Param({"st", "mt"})
    String executor;

    static final int BATCH = 100;

    public static final class Stats {
        long sumX, sumHp, sumMana;
    }

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
        world.tick();
        positionCursor = 0;
        healthCursor = BATCH;
        manaCursor = 2 * BATCH;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
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
        world.tick();
        bh.consume(stats.sumX);
        bh.consume(stats.sumHp);
        bh.consume(stats.sumMana);
    }
}
