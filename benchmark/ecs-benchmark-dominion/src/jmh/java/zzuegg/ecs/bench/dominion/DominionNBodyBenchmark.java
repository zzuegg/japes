package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * N-body integration benchmark for Dominion.
 *
 * Counterpart of {@code ecs-benchmark.NBodyBenchmark}. Dominion has no
 * resource concept, so {@code dt} lives as a field and is captured by the
 * integration loop — the idiomatic Dominion pattern for per-tick constants.
 *
 * Component types are mutable classes (Dominion's canonical shape) so the
 * integrator mutates {@code Position} in place instead of allocating a new
 * instance, matching how real Dominion code is written.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class DominionNBodyBenchmark {

    public static final class Position {
        public float x, y, z;
        public Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    public static final class Velocity {
        public float dx, dy, dz;
        public Velocity(float dx, float dy, float dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
    }

    public static final class Mass {
        public float m;
        public Mass(float m) { this.m = m; }
    }

    @Param({"1000", "10000"})
    int bodyCount;

    Dominion world;
    static final float DT = 0.001f;

    @Setup
    public void setup() {
        world = Dominion.create();
        for (int i = 0; i < bodyCount; i++) {
            float angle = (float)(2 * Math.PI * i / bodyCount);
            world.createEntity(
                new Position((float)Math.cos(angle) * 100, (float)Math.sin(angle) * 100, 0),
                new Velocity(-(float)Math.sin(angle) * 10, (float)Math.cos(angle) * 10, 0),
                new Mass(1.0f));
        }
    }

    private void integrate() {
        var results = world.findEntitiesWith(Position.class, Velocity.class);
        for (var r : results) {
            var p = r.comp1();
            var v = r.comp2();
            p.x += v.dx * DT;
            p.y += v.dy * DT;
            p.z += v.dz * DT;
        }
    }

    @Benchmark
    public void simulateOneTick() {
        integrate();
    }

    @Benchmark
    public void simulateTenTicks() {
        for (int i = 0; i < 10; i++) {
            integrate();
        }
    }
}
