package zzuegg.ecs.bench.valhalla.scenario;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Changed;
import zzuegg.ecs.system.Filter;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Valhalla variant of {@code SparseDeltaBenchmark}. {@code Health} is a
 * {@code value record} so the backing storage is a flat {@code int[]} once
 * Valhalla's frontend flattens the record layout.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SparseDeltaBenchmarkValhalla {

    public value record Health(int hp) {}

    public static class ChangedObserver {
        public static Blackhole bh;
        public long count;

        @System
        @Filter(value = Changed.class, target = Health.class)
        void observe(@Read Health h) {
            count++;
            if (bh != null) bh.consume(h);
        }
    }

    @Param({"10000"})
    int entityCount;

    static final int BATCH = 100;

    World world;
    List<Entity> handles;
    int cursor;

    @Setup(Level.Iteration)
    public void setup() {
        world = World.builder().addSystem(ChangedObserver.class).build();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            handles.add(world.spawn(new Health(1000)));
        }
        world.tick();
        cursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        ChangedObserver.bh = bh;
        for (int i = 0; i < BATCH; i++) {
            var e = handles.get(cursor);
            cursor = (cursor + 1) % handles.size();
            var h = world.getComponent(e, Health.class);
            world.setComponent(e, new Health(h.hp() - 1));
        }
        world.tick();
    }
}
