package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * EA stress test: megamorphic processAll dispatch.
 *
 * <p>Each system writes a different single-field int record component.
 * All systems do the same trivial work (increment by 1). The only variable
 * is the NUMBER of distinct system classes registered in the world, which
 * controls how many concrete ChunkProcessor implementations the JIT sees
 * at the processAll call-site.
 *
 * <p>With 1-2 systems the JIT can inline (monomorphic/bimorphic). Beyond
 * that the call-site goes megamorphic and escape analysis degrades because
 * the JIT can no longer prove that Mut/record allocations don't escape.
 *
 * <p>We also test whether spreading systems across stages (one per stage)
 * vs packing them into one stage changes the EA outcome.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgs = {"--enable-preview", "-XX:+UseCompressedOops"})
public class EAStressMegamorphicBenchmark {

    // ---- 20 distinct single-field int record component types ----
    public record C01(int v) {}
    public record C02(int v) {}
    public record C03(int v) {}
    public record C04(int v) {}
    public record C05(int v) {}
    public record C06(int v) {}
    public record C07(int v) {}
    public record C08(int v) {}
    public record C09(int v) {}
    public record C10(int v) {}
    public record C11(int v) {}
    public record C12(int v) {}
    public record C13(int v) {}
    public record C14(int v) {}
    public record C15(int v) {}
    public record C16(int v) {}
    public record C17(int v) {}
    public record C18(int v) {}
    public record C19(int v) {}
    public record C20(int v) {}

    // ---- 20 distinct write-system classes (one per component) ----
    // Each increments its component's value by 1. Trivial work so
    // allocation overhead dominates.

    public static final class S01 { @System void run(@Write Mut<C01> m) { m.set(new C01(m.get().v() + 1)); } }
    public static final class S02 { @System void run(@Write Mut<C02> m) { m.set(new C02(m.get().v() + 1)); } }
    public static final class S03 { @System void run(@Write Mut<C03> m) { m.set(new C03(m.get().v() + 1)); } }
    public static final class S04 { @System void run(@Write Mut<C04> m) { m.set(new C04(m.get().v() + 1)); } }
    public static final class S05 { @System void run(@Write Mut<C05> m) { m.set(new C05(m.get().v() + 1)); } }
    public static final class S06 { @System void run(@Write Mut<C06> m) { m.set(new C06(m.get().v() + 1)); } }
    public static final class S07 { @System void run(@Write Mut<C07> m) { m.set(new C07(m.get().v() + 1)); } }
    public static final class S08 { @System void run(@Write Mut<C08> m) { m.set(new C08(m.get().v() + 1)); } }
    public static final class S09 { @System void run(@Write Mut<C09> m) { m.set(new C09(m.get().v() + 1)); } }
    public static final class S10 { @System void run(@Write Mut<C10> m) { m.set(new C10(m.get().v() + 1)); } }
    public static final class S11 { @System void run(@Write Mut<C11> m) { m.set(new C11(m.get().v() + 1)); } }
    public static final class S12 { @System void run(@Write Mut<C12> m) { m.set(new C12(m.get().v() + 1)); } }
    public static final class S13 { @System void run(@Write Mut<C13> m) { m.set(new C13(m.get().v() + 1)); } }
    public static final class S14 { @System void run(@Write Mut<C14> m) { m.set(new C14(m.get().v() + 1)); } }
    public static final class S15 { @System void run(@Write Mut<C15> m) { m.set(new C15(m.get().v() + 1)); } }
    public static final class S16 { @System void run(@Write Mut<C16> m) { m.set(new C16(m.get().v() + 1)); } }
    public static final class S17 { @System void run(@Write Mut<C17> m) { m.set(new C17(m.get().v() + 1)); } }
    public static final class S18 { @System void run(@Write Mut<C18> m) { m.set(new C18(m.get().v() + 1)); } }
    public static final class S19 { @System void run(@Write Mut<C19> m) { m.set(new C19(m.get().v() + 1)); } }
    public static final class S20 { @System void run(@Write Mut<C20> m) { m.set(new C20(m.get().v() + 1)); } }

    // ---- Staged variants: each system in a different stage ----
    public static final class S01staged { @System(stage = "First")      void run(@Write Mut<C01> m) { m.set(new C01(m.get().v() + 1)); } }
    public static final class S02staged { @System(stage = "PreUpdate")  void run(@Write Mut<C02> m) { m.set(new C02(m.get().v() + 1)); } }
    public static final class S03staged { @System(stage = "Update")     void run(@Write Mut<C03> m) { m.set(new C03(m.get().v() + 1)); } }
    public static final class S04staged { @System(stage = "PostUpdate") void run(@Write Mut<C04> m) { m.set(new C04(m.get().v() + 1)); } }
    public static final class S05staged { @System(stage = "Last")       void run(@Write Mut<C05> m) { m.set(new C05(m.get().v() + 1)); } }
    public static final class S06staged { @System(stage = "First")      void run(@Write Mut<C06> m) { m.set(new C06(m.get().v() + 1)); } }

    // All component classes in order, for spawning
    @SuppressWarnings("unchecked")
    private static final Class<? extends Record>[] COMP_CLASSES = new Class[]{
        C01.class, C02.class, C03.class, C04.class, C05.class,
        C06.class, C07.class, C08.class, C09.class, C10.class,
        C11.class, C12.class, C13.class, C14.class, C15.class,
        C16.class, C17.class, C18.class, C19.class, C20.class,
    };

    private static Record makeComponent(int idx, int value) {
        return switch (idx) {
            case  0 -> new C01(value); case  1 -> new C02(value);
            case  2 -> new C03(value); case  3 -> new C04(value);
            case  4 -> new C05(value); case  5 -> new C06(value);
            case  6 -> new C07(value); case  7 -> new C08(value);
            case  8 -> new C09(value); case  9 -> new C10(value);
            case 10 -> new C11(value); case 11 -> new C12(value);
            case 12 -> new C13(value); case 13 -> new C14(value);
            case 14 -> new C15(value); case 15 -> new C16(value);
            case 16 -> new C17(value); case 17 -> new C18(value);
            case 18 -> new C19(value); case 19 -> new C20(value);
            default -> throw new IllegalArgumentException("idx=" + idx);
        };
    }

    private static final Object[] ALL_SYSTEMS = {
        new S01(), new S02(), new S03(), new S04(), new S05(),
        new S06(), new S07(), new S08(), new S09(), new S10(),
        new S11(), new S12(), new S13(), new S14(), new S15(),
        new S16(), new S17(), new S18(), new S19(), new S20(),
    };

    private static final Object[] STAGED_SYSTEMS = {
        new S01staged(), new S02staged(), new S03staged(),
        new S04staged(), new S05staged(), new S06staged(),
    };

    private static final int ENTITY_COUNT = 1_000;

    // --- One world per system-count variant ---
    World world1, world3, world6, world10, world20;
    World world6staged;

    @Setup(Level.Trial)
    public void setup() {
        world1  = buildWorld(1, false);
        world3  = buildWorld(3, false);
        world6  = buildWorld(6, false);
        world10 = buildWorld(10, false);
        world20 = buildWorld(20, false);
        world6staged = buildWorld(6, true);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (world1 != null) world1.close();
        if (world3 != null) world3.close();
        if (world6 != null) world6.close();
        if (world10 != null) world10.close();
        if (world20 != null) world20.close();
        if (world6staged != null) world6staged.close();
    }

    private World buildWorld(int systemCount, boolean staged) {
        var builder = World.builder();
        if (staged) {
            for (int i = 0; i < systemCount && i < STAGED_SYSTEMS.length; i++) {
                builder.addSystem(STAGED_SYSTEMS[i]);
            }
        } else {
            for (int i = 0; i < systemCount; i++) {
                builder.addSystem(ALL_SYSTEMS[i]);
            }
        }
        World w = builder.build();

        // Spawn entities — each entity has ALL components that the registered
        // systems need (one component per system).
        for (int e = 0; e < ENTITY_COUNT; e++) {
            Record[] comps = new Record[systemCount];
            for (int c = 0; c < systemCount; c++) {
                comps[c] = makeComponent(c, e);
            }
            w.spawn(comps);
        }
        // Warm-up tick to trigger archetype creation
        w.tick();
        return w;
    }

    // ---- Benchmarks: same-stage (Update) ----

    @Benchmark
    public void systems_01() { world1.tick(); }

    @Benchmark
    public void systems_03() { world3.tick(); }

    @Benchmark
    public void systems_06() { world6.tick(); }

    @Benchmark
    public void systems_10() { world10.tick(); }

    @Benchmark
    public void systems_20() { world20.tick(); }

    // ---- Benchmark: spread across stages ----

    @Benchmark
    public void systems_06_staged() { world6staged.tick(); }
}
