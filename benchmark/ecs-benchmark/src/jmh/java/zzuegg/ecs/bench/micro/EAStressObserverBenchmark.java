package zzuegg.ecs.bench.micro;

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
 * EA (Escape Analysis) stress test for {@code @Filter(Changed)} observers.
 *
 * <p>SoA storage reconstructs a record object for each {@code @Read} param on
 * every invocation. If EA can scalar-replace the record the allocation cost is
 * zero; if the record escapes, each invocation allocates. This benchmark
 * measures the allocation rate ({@code -prof gc}) across several observer
 * shapes to find the point at which EA gives up.
 *
 * <h3>Variants</h3>
 * <ol>
 *   <li>{@code read1} — 1 @Read param (baseline)</li>
 *   <li>{@code read3} — 3 @Read params (UnifiedDelta shape)</li>
 *   <li>{@code read4} — 4 @Read params (at the component param limit)</li>
 *   <li>{@code read3Used} — 3 @Read params, accumulates all values into a sum</li>
 *   <li>{@code read3Ignored} — 3 @Read params, ignores all values (counter only)</li>
 *   <li>{@code largeRecord} — 1 @Read on a 6-field record</li>
 *   <li>{@code twoObservers} — two observers on the same Changed target</li>
 * </ol>
 *
 * <p>Each variant has a writer system that mutates ~10% of entities per tick.
 * Entity count is fixed at 10 000 so the observer processes ~1 000 entities.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class EAStressObserverBenchmark {

    // ---- Component records ----

    public record CompA(float x, float y, float z) {}
    public record CompB(int value) {}
    public record CompC(long id) {}
    public record CompD(double weight) {}

    /** 6-field record — tests whether SoA reconstruction + EA still works. */
    public record LargeComp(float x, float y, float z, float w, float u, float v) {}

    /** Marker component for the dirty-flag field. */
    public record DirtyTag(int seed) {}

    static final int ENTITY_COUNT = 10_000;

    // ---- Writer systems: mutate ~10% of entities per tick ----

    /** Writes CompA on entities where seed % 10 == 0. */
    public static final class WriterA {
        @System
        void tick(@Read DirtyTag tag, @Write Mut<CompA> a) {
            if (tag.seed() % 10 == 0) {
                var cur = a.get();
                a.set(new CompA(cur.x() + 1, cur.y() + 1, cur.z() + 1));
            }
        }
    }

    /** Writes CompB on entities where seed % 10 == 1. */
    public static final class WriterB {
        @System
        void tick(@Read DirtyTag tag, @Write Mut<CompB> b) {
            if (tag.seed() % 10 == 1) {
                b.set(new CompB(b.get().value() + 1));
            }
        }
    }

    /** Writes CompC on entities where seed % 10 == 2. */
    public static final class WriterC {
        @System
        void tick(@Read DirtyTag tag, @Write Mut<CompC> c) {
            if (tag.seed() % 10 == 2) {
                c.set(new CompC(c.get().id() + 1));
            }
        }
    }

    /** Writes CompD on entities where seed % 10 == 3. */
    public static final class WriterD {
        @System
        void tick(@Read DirtyTag tag, @Write Mut<CompD> d) {
            if (tag.seed() % 10 == 3) {
                d.set(new CompD(d.get().weight() + 0.1));
            }
        }
    }

    /** Writes LargeComp on entities where seed % 10 == 0. */
    public static final class WriterLarge {
        @System
        void tick(@Read DirtyTag tag, @Write Mut<LargeComp> lc) {
            if (tag.seed() % 10 == 0) {
                var c = lc.get();
                lc.set(new LargeComp(c.x() + 1, c.y() + 1, c.z() + 1,
                                     c.w() + 1, c.u() + 1, c.v() + 1));
            }
        }
    }

    // ---- Observer systems ----

    // 1. Single @Read
    public static final class Observer1Read {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a) {
            if (bh != null) {
                bh.consume(a.x());
                bh.consume(a.y());
                bh.consume(a.z());
            }
        }
    }

    // 2. Three @Read params (UnifiedDelta shape)
    public static final class Observer3Read {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a, @Read CompB b, @Read CompC c) {
            if (bh != null) {
                bh.consume(a.x());
                bh.consume(b.value());
                bh.consume(c.id());
            }
        }
    }

    // 3. Four @Read params (at component param limit)
    public static final class Observer4Read {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a, @Read CompB b, @Read CompC c, @Read CompD d) {
            if (bh != null) {
                bh.consume(a.x());
                bh.consume(b.value());
                bh.consume(c.id());
                bh.consume(d.weight());
            }
        }
    }

    // 4a. Three @Read, accumulates ALL values into a sum
    public static final class Observer3ReadUsed {
        public static long sum;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a, @Read CompB b, @Read CompC c) {
            sum += (long) a.x() + (long) a.y() + (long) a.z()
                 + b.value() + c.id();
        }
    }

    // 4b. Three @Read, ignores all values (counter only)
    public static final class Observer3ReadIgnored {
        public static long count;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a, @Read CompB b, @Read CompC c) {
            count++;
        }
    }

    // 5. Single @Read on a 6-field record
    public static final class ObserverLargeRecord {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = LargeComp.class)
        void observe(@Read LargeComp lc) {
            if (bh != null) {
                bh.consume(lc.x());
                bh.consume(lc.y());
                bh.consume(lc.z());
                bh.consume(lc.w());
                bh.consume(lc.u());
                bh.consume(lc.v());
            }
        }
    }

    // 6. Two observers on the same Changed target (megamorphic dispatch test)
    public static final class ObserverDualA {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a) {
            if (bh != null) {
                bh.consume(a.x());
                bh.consume(a.y());
                bh.consume(a.z());
            }
        }
    }

    public static final class ObserverDualB {
        public static Blackhole bh;
        @System
        @Filter(value = Changed.class, target = CompA.class)
        void observe(@Read CompA a) {
            if (bh != null) bh.consume(a.x() + a.y() + a.z());
        }
    }

    // ---- Worlds ----

    World worldRead1;
    World worldRead3;
    World worldRead4;
    World worldRead3Used;
    World worldRead3Ignored;
    World worldLargeRecord;
    World worldTwoObservers;

    @Setup(Level.Trial)
    public void setup() {
        // --- read1 ---
        worldRead1 = World.builder()
            .addSystem(WriterA.class)
            .addSystem(Observer1Read.class)
            .build();
        spawnStandard(worldRead1, false);

        // --- read3 ---
        worldRead3 = World.builder()
            .addSystem(WriterA.class)
            .addSystem(Observer3Read.class)
            .build();
        spawnStandard(worldRead3, false);

        // --- read4 ---
        worldRead4 = World.builder()
            .addSystem(WriterA.class)
            .addSystem(Observer4Read.class)
            .build();
        spawnWithD(worldRead4);

        // --- read3Used ---
        worldRead3Used = World.builder()
            .addSystem(WriterA.class)
            .addSystem(Observer3ReadUsed.class)
            .build();
        spawnStandard(worldRead3Used, false);

        // --- read3Ignored ---
        worldRead3Ignored = World.builder()
            .addSystem(WriterA.class)
            .addSystem(Observer3ReadIgnored.class)
            .build();
        spawnStandard(worldRead3Ignored, false);

        // --- largeRecord ---
        worldLargeRecord = World.builder()
            .addSystem(WriterLarge.class)
            .addSystem(ObserverLargeRecord.class)
            .build();
        spawnLarge(worldLargeRecord);

        // --- twoObservers ---
        worldTwoObservers = World.builder()
            .addSystem(WriterA.class)
            .addSystem(ObserverDualA.class)
            .addSystem(ObserverDualB.class)
            .build();
        spawnStandard(worldTwoObservers, false);

        // Prime all worlds so observer watermarks are past the spawn tick.
        worldRead1.tick();
        worldRead3.tick();
        worldRead4.tick();
        worldRead3Used.tick();
        worldRead3Ignored.tick();
        worldLargeRecord.tick();
        worldTwoObservers.tick();
    }

    private void spawnStandard(World w, boolean unused) {
        for (int i = 0; i < ENTITY_COUNT; i++) {
            w.spawn(new DirtyTag(i), new CompA(i, i, i), new CompB(i), new CompC(i));
        }
    }

    private void spawnWithD(World w) {
        for (int i = 0; i < ENTITY_COUNT; i++) {
            w.spawn(new DirtyTag(i), new CompA(i, i, i), new CompB(i), new CompC(i),
                    new CompD(i * 0.1));
        }
    }

    private void spawnLarge(World w) {
        for (int i = 0; i < ENTITY_COUNT; i++) {
            w.spawn(new DirtyTag(i),
                    new LargeComp(i, i, i, i, i, i));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (worldRead1 != null) worldRead1.close();
        if (worldRead3 != null) worldRead3.close();
        if (worldRead4 != null) worldRead4.close();
        if (worldRead3Used != null) worldRead3Used.close();
        if (worldRead3Ignored != null) worldRead3Ignored.close();
        if (worldLargeRecord != null) worldLargeRecord.close();
        if (worldTwoObservers != null) worldTwoObservers.close();
    }

    // ---- Benchmarks ----

    @Benchmark
    public void read1(Blackhole bh) {
        Observer1Read.bh = bh;
        worldRead1.tick();
    }

    @Benchmark
    public void read3(Blackhole bh) {
        Observer3Read.bh = bh;
        worldRead3.tick();
    }

    @Benchmark
    public void read4(Blackhole bh) {
        Observer4Read.bh = bh;
        worldRead4.tick();
    }

    @Benchmark
    public void read3Used(Blackhole bh) {
        Observer3ReadUsed.sum = 0;
        worldRead3Used.tick();
        bh.consume(Observer3ReadUsed.sum);
    }

    @Benchmark
    public void read3Ignored(Blackhole bh) {
        Observer3ReadIgnored.count = 0;
        worldRead3Ignored.tick();
        bh.consume(Observer3ReadIgnored.count);
    }

    @Benchmark
    public void largeRecord(Blackhole bh) {
        ObserverLargeRecord.bh = bh;
        worldLargeRecord.tick();
    }

    @Benchmark
    public void twoObservers(Blackhole bh) {
        ObserverDualA.bh = bh;
        ObserverDualB.bh = bh;
        worldTwoObservers.tick();
    }
}
