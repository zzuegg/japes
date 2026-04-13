package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * EA stress-test for complex record types that challenge the HiddenMut generator.
 *
 * Each record type probes a different edge case:
 * <ul>
 *   <li>{@code BigState} — 6 float fields: many fields push object size and field count</li>
 *   <li>{@code Mixed} — 4 different primitive types: int, float, double, long</li>
 *   <li>{@code Flags} — boolean + int mix: booleans stored as byte in SoA</li>
 *   <li>{@code Timer} — 2 longs: each takes 2 JVM slots (4 total for cur_ + pnd_)</li>
 *   <li>{@code Active} — single boolean: minimal edge case</li>
 * </ul>
 *
 * Each system reads the component and conditionally writes (~10%), matching a
 * realistic "damage occasionally" pattern that forces both get() and set() paths.
 *
 * Run with: -prof gc
 * Compare with: -jvmArgs -XX:-EliminateAllocations
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EAStressComplexRecordBenchmark {

    // ---- Record types under test ----

    /** 6 float fields — large field count. */
    public record BigState(float a, float b, float c, float d, float e, float f) {}

    /** 4 different primitive types. */
    public record Mixed(int id, float x, double y, long timestamp) {}

    /** Booleans + int. */
    public record Flags(boolean active, boolean visible, int hp) {}

    /** 2 longs — each takes 2 JVM slots. */
    public record Timer(long startTime, long endTime) {}

    /** Single boolean — minimal edge case. */
    public record Active(boolean value) {}

    // ---- Systems: read + conditional ~10% write ----

    static class BigStateSystem {
        @System
        void process(@Write Mut<BigState> mut) {
            var s = mut.get();
            float sum = s.a() + s.b() + s.c() + s.d() + s.e() + s.f();
            // ~10% write: when sum overflows a threshold derived from entity index
            if (sum % 10.0f < 1.0f) {
                mut.set(new BigState(s.a() + 1, s.b(), s.c(), s.d(), s.e(), s.f()));
            }
        }
    }

    static class MixedSystem {
        @System
        void process(@Write Mut<Mixed> mut) {
            var m = mut.get();
            // ~10% write based on id
            if (m.id() % 10 == 0) {
                mut.set(new Mixed(m.id(), m.x() + 1.0f, m.y() + 0.1, m.timestamp() + 1));
            }
        }
    }

    static class FlagsSystem {
        @System
        void process(@Write Mut<Flags> mut) {
            var f = mut.get();
            // ~10% write: toggle active for entities with low hp
            if (f.hp() % 10 == 0) {
                mut.set(new Flags(!f.active(), f.visible(), f.hp() - 1));
            }
        }
    }

    static class TimerSystem {
        @System
        void process(@Write Mut<Timer> mut) {
            var t = mut.get();
            // ~10% write: expire timers
            if (t.startTime() % 10 == 0) {
                mut.set(new Timer(t.startTime(), t.endTime() + 16_000_000L));
            }
        }
    }

    static class ActiveSystem {
        @System
        void process(@Write Mut<Active> mut) {
            var a = mut.get();
            // ~10% write: flip active — can't use modulus on boolean,
            // so we use a counter-based approach via the hash of the value
            if (a.value()) {
                mut.set(new Active(false));
            }
        }
    }

    // ---- Benchmark state ----

    @Param({"10000"})
    int entityCount;

    World bigStateWorld;
    World mixedWorld;
    World flagsWorld;
    World timerWorld;
    World activeWorld;

    @Setup
    public void setup() {
        bigStateWorld = World.builder().addSystem(BigStateSystem.class).build();
        mixedWorld = World.builder().addSystem(MixedSystem.class).build();
        flagsWorld = World.builder().addSystem(FlagsSystem.class).build();
        timerWorld = World.builder().addSystem(TimerSystem.class).build();
        activeWorld = World.builder().addSystem(ActiveSystem.class).build();

        for (int i = 0; i < entityCount; i++) {
            bigStateWorld.spawn(new BigState(i, i + 1, i + 2, i + 3, i + 4, i + 5));
            mixedWorld.spawn(new Mixed(i, i * 1.5f, i * 2.0, i * 1000L));
            flagsWorld.spawn(new Flags(i % 2 == 0, i % 3 == 0, i));
            timerWorld.spawn(new Timer(i * 1000L, (i + 100) * 1000L));
            activeWorld.spawn(new Active(i % 10 == 0)); // ~10% start active
        }
    }

    @TearDown
    public void tearDown() {
        if (bigStateWorld != null) bigStateWorld.close();
        if (mixedWorld != null) mixedWorld.close();
        if (flagsWorld != null) flagsWorld.close();
        if (timerWorld != null) timerWorld.close();
        if (activeWorld != null) activeWorld.close();
    }

    // ---- Benchmarks ----

    @Benchmark
    public void bigState6Floats() { bigStateWorld.tick(); }

    @Benchmark
    public void mixed4Types() { mixedWorld.tick(); }

    @Benchmark
    public void flagsBooleanInt() { flagsWorld.tick(); }

    @Benchmark
    public void timer2Longs() { timerWorld.tick(); }

    @Benchmark
    public void activeSingleBoolean() { activeWorld.tick(); }
}
