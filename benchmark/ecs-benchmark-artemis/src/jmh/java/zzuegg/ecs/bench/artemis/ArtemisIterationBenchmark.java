package zzuegg.ecs.bench.artemis;

import com.artemis.Aspect;
import com.artemis.Component;
import com.artemis.ComponentMapper;
import com.artemis.World;
import com.artemis.WorldConfigurationBuilder;
import com.artemis.systems.IteratingSystem;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Iteration benchmarks for Artemis-odb in its idiomatic shape:
 * {@code IteratingSystem} + {@code ComponentMapper}, mutable component
 * subclasses, no per-entity allocation.
 *
 * Counterpart of {@code ecs-benchmark.IterationBenchmark}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ArtemisIterationBenchmark {

    public static class Position extends Component {
        public float x, y, z;
    }

    public static class Velocity extends Component {
        public float dx, dy, dz;
    }

    public static class ReadPositionSystem extends IteratingSystem {
        ComponentMapper<Position> pm;
        public static Blackhole bh;

        public ReadPositionSystem() { super(Aspect.all(Position.class)); }

        @Override
        protected void process(int id) {
            if (bh != null) {
                var p = pm.get(id);
                bh.consume(p.x); bh.consume(p.y); bh.consume(p.z);
            }
        }
    }

    public static class ReadTwoSystem extends IteratingSystem {
        ComponentMapper<Position> pm;
        ComponentMapper<Velocity> vm;
        public static Blackhole bh;

        public ReadTwoSystem() { super(Aspect.all(Position.class, Velocity.class)); }

        @Override
        protected void process(int id) {
            if (bh != null) {
                var p = pm.get(id);
                var v = vm.get(id);
                bh.consume(p.x); bh.consume(p.y); bh.consume(p.z);
                bh.consume(v.dx); bh.consume(v.dy); bh.consume(v.dz);
            }
        }
    }

    public static class WriteSystem extends IteratingSystem {
        ComponentMapper<Position> pm;
        ComponentMapper<Velocity> vm;

        public WriteSystem() { super(Aspect.all(Position.class, Velocity.class)); }

        @Override
        protected void process(int id) {
            var p = pm.get(id);
            var v = vm.get(id);
            p.x += v.dx;
            p.y += v.dy;
            p.z += v.dz;
        }
    }

    @Param({"1000", "10000", "100000"})
    int entityCount;

    World singleCompWorld;
    World twoCompWorld;
    World writeWorld;

    @Setup
    public void setup() {
        singleCompWorld = new World(new WorldConfigurationBuilder()
            .with(new ReadPositionSystem()).build());
        twoCompWorld = new World(new WorldConfigurationBuilder()
            .with(new ReadTwoSystem()).build());
        writeWorld = new World(new WorldConfigurationBuilder()
            .with(new WriteSystem()).build());

        var pm1 = singleCompWorld.getMapper(Position.class);
        for (int i = 0; i < entityCount; i++) {
            int e = singleCompWorld.create();
            var p = pm1.create(e);
            p.x = i; p.y = i; p.z = i;
        }

        var pm2 = twoCompWorld.getMapper(Position.class);
        var vm2 = twoCompWorld.getMapper(Velocity.class);
        for (int i = 0; i < entityCount; i++) {
            int e = twoCompWorld.create();
            var p = pm2.create(e); p.x = i; p.y = i; p.z = i;
            var v = vm2.create(e); v.dx = 1; v.dy = 1; v.dz = 1;
        }

        var pm3 = writeWorld.getMapper(Position.class);
        var vm3 = writeWorld.getMapper(Velocity.class);
        for (int i = 0; i < entityCount; i++) {
            int e = writeWorld.create();
            var p = pm3.create(e); p.x = i; p.y = i; p.z = i;
            var v = vm3.create(e); v.dx = 1; v.dy = 1; v.dz = 1;
        }

        // Prime process queues.
        singleCompWorld.process();
        twoCompWorld.process();
        writeWorld.process();
    }

    @Benchmark
    public void iterateSingleComponent(Blackhole bh) {
        ReadPositionSystem.bh = bh;
        singleCompWorld.process();
    }

    @Benchmark
    public void iterateTwoComponents(Blackhole bh) {
        ReadTwoSystem.bh = bh;
        twoCompWorld.process();
    }

    @Benchmark
    public void iterateWithWrite() {
        writeWorld.process();
    }
}
