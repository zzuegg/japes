package zzuegg.ecs.bench.valhalla.macro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class NBodyBenchmarkValhalla {

    // Value records — Valhalla flattens these
    public value record Position(float x, float y, float z) {}
    public value record Velocity(float dx, float dy, float dz) {}
    public value record Mass(float m) {}
    public value record DeltaTime(float dt) {}

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
