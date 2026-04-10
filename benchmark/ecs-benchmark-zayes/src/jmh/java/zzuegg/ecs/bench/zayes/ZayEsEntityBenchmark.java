package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ZayEsEntityBenchmark {

    public record Position(float x, float y, float z) implements EntityComponent {}

    @Benchmark
    public void bulkSpawn1k() {
        var ed = new DefaultEntityData();
        for (int i = 0; i < 1000; i++) {
            EntityId id = ed.createEntity();
            ed.setComponent(id, new Position(i, i, i));
        }
    }

    @Benchmark
    public void bulkSpawn100k() {
        var ed = new DefaultEntityData();
        for (int i = 0; i < 100_000; i++) {
            EntityId id = ed.createEntity();
            ed.setComponent(id, new Position(i, i, i));
        }
    }

    @State(Scope.Benchmark)
    public static class DespawnState {
        EntityData entityData;
        ArrayList<EntityId> entities;

        @Setup(Level.Invocation)
        public void setup() {
            entityData = new DefaultEntityData();
            entities = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                EntityId id = entityData.createEntity();
                entityData.setComponent(id, new Position(i, i, i));
                entities.add(id);
            }
        }
    }

    @Benchmark
    public void bulkDespawn1k(DespawnState state) {
        for (var id : state.entities) {
            state.entityData.removeEntity(id);
        }
    }
}
