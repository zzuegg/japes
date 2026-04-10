package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Zay-ES counterpart of SparseDeltaBenchmark — the shape Zay-ES is
 * actually designed for.
 *
 * 10k entities exist; per tick the driver touches 100 of them directly via
 * setComponent, then a single "ObserverState" calls applyChanges() on its
 * EntitySet and iterates getChangedEntities() — which returns exactly the
 * 100 dirty entities, NOT the full 10k.
 *
 * This is the canonical AppState shape: one EntitySet owned by the observer,
 * one applyChanges call per update, iterate the delta view.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ZayEsSparseDeltaBenchmark {

    public record Health(int hp) implements EntityComponent {}

    @Param({"10000"})
    int entityCount;

    static final int BATCH = 100;

    EntityData data;
    EntitySet observerSet;
    List<EntityId> handles;
    int cursor;
    long observedCount;

    @Setup(Level.Iteration)
    public void setup() {
        data = new DefaultEntityData();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = data.createEntity();
            data.setComponents(id, new Health(1000));
            handles.add(id);
        }
        observerSet = data.getEntities(Health.class);
        observerSet.applyChanges(); // prime past the spawn
        cursor = 0;
        observedCount = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (observerSet != null) observerSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // Driver: damage 100 entities via the rotating cursor.
        for (int i = 0; i < BATCH; i++) {
            var id = handles.get(cursor);
            cursor = (cursor + 1) % handles.size();
            var h = data.getComponent(id, Health.class);
            data.setComponent(id, new Health(h.hp() - 1));
        }

        // ObserverState.update() — canonical single applyChanges + iterate delta.
        observerSet.applyChanges();
        for (Entity e : observerSet.getChangedEntities()) {
            observedCount++;
            bh.consume(e.get(Health.class));
        }
        bh.consume(observedCount);
    }
}
