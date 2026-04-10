package zzuegg.ecs.bench.valhalla.scenario;

import jdk.internal.vm.annotation.LooselyConsistentValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.storage.ComponentStorage;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RealisticTickBenchmarkValhalla, but with a hand-written SoA (struct-of-
 * arrays) storage for every component type instead of the default
 * {@code Object[]} / flat-reference-array backing.
 *
 * Each component's backing storage is now a per-field primitive array
 * ({@code float[] x, y, z} for Position; {@code int[] hp} for Health; and
 * so on). {@code set(slot, value)} reads the record's fields and stores
 * the primitives directly — the benchmark's {@code new Position(...)}
 * allocation in the driver loop has no heap escape because {@code set()}
 * immediately decomposes the record, so escape analysis kills it.
 *
 * {@code get(slot)} constructs a fresh {@code new Position(x[i], y[i],
 * z[i])} per call. In tight loops where the returned record is
 * immediately deconstructed by the consumer, the JIT scalar-replaces it.
 * In loops where the record escapes to an opaque sink (Blackhole), the
 * allocation is forced. For this benchmark the observer systems each
 * read {@code h.hp()} / {@code p.x()} directly on the loaded record, so
 * the scalar-replacement case applies.
 *
 * The point is to bypass the Valhalla flat-array story entirely: this
 * storage doesn't depend on JEP 401, value records, or
 * {@code LooselyConsistentValue}. It just lays out the record's
 * primitive fields in separate primitive arrays, which is what an ECS
 * written in C/Rust would do.
 *
 * {@code @Param storage} toggles between the hand-rolled SoA factory
 * and japes's default {@link zzuegg.ecs.storage.DefaultComponentStorage}
 * so both configurations can be compared on the same JVM.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class RealisticTickBenchmarkValhallaSoA {

    @LooselyConsistentValue public value record Position(float x, float y, float z) {}
    @LooselyConsistentValue public value record Velocity(float dx, float dy, float dz) {}
    @LooselyConsistentValue public value record Health(int hp) {}
    @LooselyConsistentValue public value record Mana(int points) {}

    @Param({"10000"})
    int entityCount;

    @Param({"st"})
    String executor;

    @Param({"default", "soa"})
    String storage;

    static final int BATCH = 100;

    public static final class Stats {
        long sumX, sumHp, sumMana;
    }

    public static final class PositionObserver {
        final Stats stats;
        PositionObserver(Stats stats) { this.stats = stats; }
        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Position.class)
        void observe(@Read Position p) { stats.sumX += (long) p.x(); }
    }

    public static final class HealthObserver {
        final Stats stats;
        HealthObserver(Stats stats) { this.stats = stats; }
        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Health.class)
        void observe(@Read Health h) { stats.sumHp += h.hp(); }
    }

    public static final class ManaObserver {
        final Stats stats;
        ManaObserver(Stats stats) { this.stats = stats; }
        @System(stage = "PostUpdate")
        @Filter(value = Changed.class, target = Mana.class)
        void observe(@Read Mana m) { stats.sumMana += m.points(); }
    }

    // ---- Hand-rolled SoA storages. Each one owns N primitive arrays,
    //      one per record component, and decomposes/recomposes records
    //      on set/get. The whole point is that no wrapper object ever
    //      needs to escape to the heap for a write — the benchmark's
    //      `new Position(...)` gets inlined into this set() which
    //      reads the fields directly and forwards them into primitive
    //      stores. Escape analysis handles the rest.

    public static final class PositionSoA implements ComponentStorage<Position> {
        private final float[] x, y, z;
        PositionSoA(int capacity) {
            x = new float[capacity];
            y = new float[capacity];
            z = new float[capacity];
        }
        @Override public Position get(int i) { return new Position(x[i], y[i], z[i]); }
        @Override public void set(int i, Position v) {
            x[i] = v.x(); y[i] = v.y(); z[i] = v.z();
        }
        @Override public void swapRemove(int i, int count) {
            int last = count - 1;
            if (i < last) { x[i] = x[last]; y[i] = y[last]; z[i] = z[last]; }
        }
        @Override public void copyInto(int src, ComponentStorage<Position> dst, int dstI) { dst.set(dstI, get(src)); }
        @Override public int capacity() { return x.length; }
        @Override public Class<Position> type() { return Position.class; }
    }

    public static final class VelocitySoA implements ComponentStorage<Velocity> {
        private final float[] dx, dy, dz;
        VelocitySoA(int capacity) {
            dx = new float[capacity];
            dy = new float[capacity];
            dz = new float[capacity];
        }
        @Override public Velocity get(int i) { return new Velocity(dx[i], dy[i], dz[i]); }
        @Override public void set(int i, Velocity v) {
            dx[i] = v.dx(); dy[i] = v.dy(); dz[i] = v.dz();
        }
        @Override public void swapRemove(int i, int count) {
            int last = count - 1;
            if (i < last) { dx[i] = dx[last]; dy[i] = dy[last]; dz[i] = dz[last]; }
        }
        @Override public void copyInto(int src, ComponentStorage<Velocity> dst, int dstI) { dst.set(dstI, get(src)); }
        @Override public int capacity() { return dx.length; }
        @Override public Class<Velocity> type() { return Velocity.class; }
    }

    public static final class HealthSoA implements ComponentStorage<Health> {
        private final int[] hp;
        HealthSoA(int capacity) { hp = new int[capacity]; }
        @Override public Health get(int i) { return new Health(hp[i]); }
        @Override public void set(int i, Health v) { hp[i] = v.hp(); }
        @Override public void swapRemove(int i, int count) {
            int last = count - 1;
            if (i < last) hp[i] = hp[last];
        }
        @Override public void copyInto(int src, ComponentStorage<Health> dst, int dstI) { dst.set(dstI, get(src)); }
        @Override public int capacity() { return hp.length; }
        @Override public Class<Health> type() { return Health.class; }
    }

    public static final class ManaSoA implements ComponentStorage<Mana> {
        private final int[] points;
        ManaSoA(int capacity) { points = new int[capacity]; }
        @Override public Mana get(int i) { return new Mana(points[i]); }
        @Override public void set(int i, Mana v) { points[i] = v.points(); }
        @Override public void swapRemove(int i, int count) {
            int last = count - 1;
            if (i < last) points[i] = points[last];
        }
        @Override public void copyInto(int src, ComponentStorage<Mana> dst, int dstI) { dst.set(dstI, get(src)); }
        @Override public int capacity() { return points.length; }
        @Override public Class<Mana> type() { return Mana.class; }
    }

    // Factory that routes each known record type to its specialised SoA
    // storage; other types (e.g. whatever the framework registers
    // internally) fall through to the default Object[]-backed storage.
    public static final class SoAFactory implements ComponentStorage.Factory {
        @SuppressWarnings("unchecked")
        @Override
        public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
            if (type == Position.class) return (ComponentStorage<T>) new PositionSoA(capacity);
            if (type == Velocity.class) return (ComponentStorage<T>) new VelocitySoA(capacity);
            if (type == Health.class)   return (ComponentStorage<T>) new HealthSoA(capacity);
            if (type == Mana.class)     return (ComponentStorage<T>) new ManaSoA(capacity);
            return ComponentStorage.create(type, capacity);
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
        var builder = World.builder()
            .executor(exec)
            .addSystem(new PositionObserver(stats))
            .addSystem(new HealthObserver(stats))
            .addSystem(new ManaObserver(stats));
        if ("soa".equals(storage)) {
            builder.storageFactory(new SoAFactory());
        }
        world = builder.build();
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
