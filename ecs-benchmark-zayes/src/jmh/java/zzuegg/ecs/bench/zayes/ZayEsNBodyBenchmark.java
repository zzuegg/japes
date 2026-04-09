package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ZayEsNBodyBenchmark {

    public record Position(float x, float y, float z) implements EntityComponent {}
    public record Velocity(float dx, float dy, float dz) implements EntityComponent {}
    public record Mass(float m) implements EntityComponent {}

    @Param({"1000", "10000"})
    int bodyCount;

    EntityData entityData;
    EntitySet bodySet;
    float dt = 0.001f;

    @Setup
    public void setup() {
        entityData = new DefaultEntityData();

        for (int i = 0; i < bodyCount; i++) {
            float angle = (float)(2 * Math.PI * i / bodyCount);
            EntityId id = entityData.createEntity();
            entityData.setComponents(id,
                new Position((float)Math.cos(angle) * 100, (float)Math.sin(angle) * 100, 0),
                new Velocity(-(float)Math.sin(angle) * 10, (float)Math.cos(angle) * 10, 0),
                new Mass(1.0f)
            );
        }

        bodySet = entityData.getEntities(Position.class, Velocity.class);
        bodySet.applyChanges();
    }

    @TearDown
    public void teardown() {
        if (bodySet != null) bodySet.release();
    }

    @Benchmark
    public void simulateOneTick() {
        for (Entity entity : bodySet) {
            Position pos = entity.get(Position.class);
            Velocity vel = entity.get(Velocity.class);
            entityData.setComponent(entity.getId(),
                new Position(pos.x() + vel.dx() * dt, pos.y() + vel.dy() * dt, pos.z() + vel.dz() * dt));
        }
        bodySet.applyChanges();
    }

    @Benchmark
    public void simulateTenTicks() {
        for (int t = 0; t < 10; t++) {
            for (Entity entity : bodySet) {
                Position pos = entity.get(Position.class);
                Velocity vel = entity.get(Velocity.class);
                entityData.setComponent(entity.getId(),
                    new Position(pos.x() + vel.dx() * dt, pos.y() + vel.dy() * dt, pos.z() + vel.dz() * dt));
            }
            bodySet.applyChanges();
        }
    }
}
