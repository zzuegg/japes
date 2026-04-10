package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Iteration benchmarks for Dominion in its idiomatic shape: mutable POJO
 * components, {@code findEntitiesWith} queries, iterator-based traversal.
 *
 * Counterpart of {@code ecs-benchmark.IterationBenchmark}. Numbers should
 * be directly comparable — same entity counts, same per-entity workload.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DominionIterationBenchmark {

    public static final class Position {
        public float x, y, z;
        public Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    public static final class Velocity {
        public float dx, dy, dz;
        public Velocity(float dx, float dy, float dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
    }

    @Param({"1000", "10000", "100000"})
    int entityCount;

    Dominion singleCompWorld;
    Dominion twoCompWorld;
    Dominion writeWorld;

    @Setup
    public void setup() {
        singleCompWorld = Dominion.create();
        twoCompWorld = Dominion.create();
        writeWorld = Dominion.create();

        for (int i = 0; i < entityCount; i++) {
            singleCompWorld.createEntity(new Position(i, i, i));
            twoCompWorld.createEntity(new Position(i, i, i), new Velocity(1, 1, 1));
            writeWorld.createEntity(new Position(i, i, i), new Velocity(1, 1, 1));
        }
    }

    @Benchmark
    public void iterateSingleComponent(Blackhole bh) {
        var results = singleCompWorld.findEntitiesWith(Position.class);
        for (var r : results) {
            bh.consume(r.comp());
        }
    }

    @Benchmark
    public void iterateTwoComponents(Blackhole bh) {
        var results = twoCompWorld.findEntitiesWith(Position.class, Velocity.class);
        for (var r : results) {
            bh.consume(r.comp1());
            bh.consume(r.comp2());
        }
    }

    @Benchmark
    public void iterateWithWrite() {
        // In-place mutation — Dominion's native pattern, zero allocation per
        // entity because components are mutable classes.
        var results = writeWorld.findEntitiesWith(Position.class, Velocity.class);
        for (var r : results) {
            var p = r.comp1();
            var v = r.comp2();
            p.x += v.dx;
            p.y += v.dy;
            p.z += v.dz;
        }
    }
}
