package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.storage.DefaultComponentStorage;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * EA stress test for non-SoA components.
 *
 * Records with non-primitive fields (String, Object references) are not
 * SoA-eligible and fall back to {@link DefaultComponentStorage} (Object[]
 * backing). The generated HiddenMut is not produced for these types.
 *
 * Each scenario has a write system with ~10% mutation rate. We compare:
 *
 * <ul>
 *   <li>{@code Label(String name)} vs {@code PrimLabel(long hash)}</li>
 *   <li>{@code Ref(Object data)} vs {@code PrimRef(long ptr)}</li>
 *   <li>{@code Mixed(int id, String name)} vs {@code PrimMixed(int id, long nameHash)}</li>
 *   <li>{@code PrimForced(float x, float y)} — all-primitive but forced to DefaultComponentStorage</li>
 *   <li>{@code PrimNatural(float x, float y)} — all-primitive with default (SoA) storage</li>
 * </ul>
 *
 * Run with: {@code -prof gc} to see allocation rates.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EAStressNonSoABenchmark {

    // ---- Non-SoA records (reference fields) ----
    public record Label(String name) {}
    public record Ref(Object data) {}
    public record Mixed(int id, String name) {}

    // ---- Primitive-only equivalents (SoA-eligible) ----
    public record PrimLabel(long hash) {}
    public record PrimRef(long ptr) {}
    public record PrimMixed(int id, long nameHash) {}

    // ---- Forced-default vs natural SoA ----
    public record PrimForced(float x, float y) {}
    public record PrimNatural(float x, float y) {}

    // ---- Counter component to read alongside (provides entity identity) ----
    public record Counter(int tick) {}

    // -- Write systems: ~10% mutation rate --
    // Each system reads a Counter to determine whether to mutate.
    // counter.tick() % 10 == 0 → mutate (10% of entities).

    static class LabelWriter {
        @System void run(@Read Counter c, @Write Mut<Label> label) {
            if (c.tick() % 10 == 0) {
                label.set(new Label("updated_" + c.tick()));
            }
        }
    }

    static class PrimLabelWriter {
        @System void run(@Read Counter c, @Write Mut<PrimLabel> label) {
            if (c.tick() % 10 == 0) {
                label.set(new PrimLabel(c.tick() * 31L));
            }
        }
    }

    static class RefWriter {
        @System void run(@Read Counter c, @Write Mut<Ref> ref) {
            if (c.tick() % 10 == 0) {
                ref.set(new Ref(Integer.valueOf(c.tick())));
            }
        }
    }

    static class PrimRefWriter {
        @System void run(@Read Counter c, @Write Mut<PrimRef> ref) {
            if (c.tick() % 10 == 0) {
                ref.set(new PrimRef(c.tick() * 17L));
            }
        }
    }

    static class MixedWriter {
        @System void run(@Read Counter c, @Write Mut<Mixed> mixed) {
            if (c.tick() % 10 == 0) {
                mixed.set(new Mixed(c.tick() + 1, "name_" + c.tick()));
            }
        }
    }

    static class PrimMixedWriter {
        @System void run(@Read Counter c, @Write Mut<PrimMixed> mixed) {
            if (c.tick() % 10 == 0) {
                mixed.set(new PrimMixed(c.tick() + 1, c.tick() * 37L));
            }
        }
    }

    static class PrimForcedWriter {
        @System void run(@Read Counter c, @Write Mut<PrimForced> pf) {
            if (c.tick() % 10 == 0) {
                pf.set(new PrimForced(c.tick() + 0.5f, c.tick() - 0.5f));
            }
        }
    }

    static class PrimNaturalWriter {
        @System void run(@Read Counter c, @Write Mut<PrimNatural> pn) {
            if (c.tick() % 10 == 0) {
                pn.set(new PrimNatural(c.tick() + 0.5f, c.tick() - 0.5f));
            }
        }
    }

    @Param({"10000"})
    int entityCount;

    World labelWorld, primLabelWorld;
    World refWorld, primRefWorld;
    World mixedWorld, primMixedWorld;
    World primForcedWorld, primNaturalWorld;

    @Setup(Level.Iteration)
    public void setup() {
        // Label (non-SoA) vs PrimLabel (SoA)
        labelWorld = World.builder().addSystem(LabelWriter.class).build();
        primLabelWorld = World.builder().addSystem(PrimLabelWriter.class).build();

        // Ref (non-SoA) vs PrimRef (SoA)
        refWorld = World.builder().addSystem(RefWriter.class).build();
        primRefWorld = World.builder().addSystem(PrimRefWriter.class).build();

        // Mixed (non-SoA) vs PrimMixed (SoA)
        mixedWorld = World.builder().addSystem(MixedWriter.class).build();
        primMixedWorld = World.builder().addSystem(PrimMixedWriter.class).build();

        // PrimForced (DefaultComponentStorage) vs PrimNatural (SoA)
        primForcedWorld = World.builder()
            .storageFactory(DefaultComponentStorage::new)
            .addSystem(PrimForcedWriter.class)
            .build();
        primNaturalWorld = World.builder()
            .addSystem(PrimNaturalWriter.class)
            .build();

        for (int i = 0; i < entityCount; i++) {
            labelWorld.spawn(new Counter(i), new Label("entity_" + i));
            primLabelWorld.spawn(new Counter(i), new PrimLabel(i * 31L));

            refWorld.spawn(new Counter(i), new Ref(Integer.valueOf(i)));
            primRefWorld.spawn(new Counter(i), new PrimRef(i * 17L));

            mixedWorld.spawn(new Counter(i), new Mixed(i, "entity_" + i));
            primMixedWorld.spawn(new Counter(i), new PrimMixed(i, i * 37L));

            primForcedWorld.spawn(new Counter(i), new PrimForced(i, i));
            primNaturalWorld.spawn(new Counter(i), new PrimNatural(i, i));
        }

        // Prime tick so change tracking is past spawn.
        labelWorld.tick();
        primLabelWorld.tick();
        refWorld.tick();
        primRefWorld.tick();
        mixedWorld.tick();
        primMixedWorld.tick();
        primForcedWorld.tick();
        primNaturalWorld.tick();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        labelWorld.close();
        primLabelWorld.close();
        refWorld.close();
        primRefWorld.close();
        mixedWorld.close();
        primMixedWorld.close();
        primForcedWorld.close();
        primNaturalWorld.close();
    }

    // ---- Label vs PrimLabel ----
    @Benchmark public void writeLabel()     { labelWorld.tick(); }
    @Benchmark public void writePrimLabel() { primLabelWorld.tick(); }

    // ---- Ref vs PrimRef ----
    @Benchmark public void writeRef()     { refWorld.tick(); }
    @Benchmark public void writePrimRef() { primRefWorld.tick(); }

    // ---- Mixed vs PrimMixed ----
    @Benchmark public void writeMixed()     { mixedWorld.tick(); }
    @Benchmark public void writePrimMixed() { primMixedWorld.tick(); }

    // ---- Forced-default vs natural SoA (both all-primitive) ----
    @Benchmark public void writePrimForced()  { primForcedWorld.tick(); }
    @Benchmark public void writePrimNatural() { primNaturalWorld.tick(); }
}
