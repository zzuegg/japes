package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.entity.EntityAllocator;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EntityBenchmark {

    record Position(float x, float y, float z) {}

    @Benchmark
    public Entity singleSpawn() {
        var alloc = new EntityAllocator();
        return alloc.allocate();
    }

    @Benchmark
    public void bulkSpawn1k() {
        var world = World.builder().build();
        for (int i = 0; i < 1000; i++) {
            world.spawn(new Position(i, i, i));
        }
    }

    @Benchmark
    public void bulkSpawn100k() {
        var world = World.builder().build();
        for (int i = 0; i < 100_000; i++) {
            world.spawn(new Position(i, i, i));
        }
    }

    @State(Scope.Benchmark)
    public static class DespawnState {
        World world;
        ArrayList<Entity> entities;

        @Setup(Level.Invocation)
        public void setup() {
            world = World.builder().build();
            entities = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
        }
    }

    @Benchmark
    public void bulkDespawn1k(DespawnState state) {
        for (var e : state.entities) {
            state.world.despawn(e);
        }
    }
}
