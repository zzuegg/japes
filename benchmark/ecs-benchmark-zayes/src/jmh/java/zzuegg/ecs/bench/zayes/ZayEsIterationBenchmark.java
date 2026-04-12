package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ZayEsIterationBenchmark {

    // Components matching our ECS benchmark
    public record Position(float x, float y, float z) implements EntityComponent {}
    public record Velocity(float dx, float dy, float dz) implements EntityComponent {}

    @Param({"1000", "10000", "100000"})
    int entityCount;

    EntityData entityData;
    EntitySet singleCompSet;
    EntitySet twoCompSet;

    @Setup
    public void setup() {
        entityData = new DefaultEntityData();

        for (int i = 0; i < entityCount; i++) {
            EntityId id = entityData.createEntity();
            entityData.setComponents(id,
                new Position(i, i, i),
                new Velocity(1, 1, 1)
            );
        }

        singleCompSet = entityData.getEntities(Position.class);
        singleCompSet.applyChanges();

        twoCompSet = entityData.getEntities(Position.class, Velocity.class);
        twoCompSet.applyChanges();
    }

    @TearDown
    public void teardown() {
        if (singleCompSet != null) singleCompSet.release();
        if (twoCompSet != null) twoCompSet.release();
    }

    @Benchmark
    public void iterateSingleComponent(Blackhole bh) {
        for (Entity entity : singleCompSet) {
            var p = entity.get(Position.class);
            bh.consume(p.x()); bh.consume(p.y()); bh.consume(p.z());
        }
    }

    @Benchmark
    public void iterateTwoComponents(Blackhole bh) {
        for (Entity entity : twoCompSet) {
            var p = entity.get(Position.class);
            var v = entity.get(Velocity.class);
            bh.consume(p.x()); bh.consume(p.y()); bh.consume(p.z());
            bh.consume(v.dx()); bh.consume(v.dy()); bh.consume(v.dz());
        }
    }

    @Benchmark
    public void iterateWithWrite() {
        for (Entity entity : twoCompSet) {
            Position pos = entity.get(Position.class);
            Velocity vel = entity.get(Velocity.class);
            entityData.setComponent(entity.getId(),
                new Position(pos.x() + vel.dx(), pos.y() + vel.dy(), pos.z() + vel.dz()));
        }
        // Apply changes so the next iteration sees updated data
        twoCompSet.applyChanges();
    }
}
