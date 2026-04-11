package zzuegg.ecs.bench.macro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

/**
 * Integration benchmark: each tick applies first-order Euler integration
 * ({@code position += velocity * dt}) to every body.
 *
 * <b>Naming note:</b> The class is named "NBody" for historical consistency with
 * the Artemis/Dominion/ZayES counterparts and the bevy-benchmark "nbody" group,
 * but this is <em>not</em> a pairwise gravitational N-body simulation. A true
 * N-body simulation would scale O(N²) with force calculations between every pair
 * of bodies; this benchmark is a pure iteration + write workload that scales
 * O(N). Direct comparisons with external N-body benchmarks are not meaningful.
 */
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class NBodyBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}
    record Mass(float m) {}
    record DeltaTime(float dt) {}

    static class IntegrateSystems {
        @System
        void integrate(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d, p.z() + vel.dz() * d));
        }
    }

    @Param({"1000", "10000"})
    int bodyCount;

    World world;

    @Setup
    public void setup() {
        world = World.builder()
            .addResource(new DeltaTime(0.001f))
            .addSystem(IntegrateSystems.class)
            .build();

        for (int i = 0; i < bodyCount; i++) {
            float angle = (float)(2 * Math.PI * i / bodyCount);
            world.spawn(
                new Position((float)Math.cos(angle) * 100, (float)Math.sin(angle) * 100, 0),
                new Velocity(-(float)Math.sin(angle) * 10, (float)Math.cos(angle) * 10, 0),
                new Mass(1.0f)
            );
        }
    }

    @TearDown
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void simulateOneTick() {
        world.tick();
    }

    @Benchmark
    public void simulateTenTicks() {
        for (int i = 0; i < 10; i++) {
            world.tick();
        }
    }
}
