package zzuegg.ecs.bench.artemis;

import com.artemis.Aspect;
import com.artemis.Component;
import com.artemis.ComponentMapper;
import com.artemis.World;
import com.artemis.WorldConfigurationBuilder;
import com.artemis.systems.IteratingSystem;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * N-body integration benchmark for Artemis-odb.
 *
 * Counterpart of {@code ecs-benchmark.NBodyBenchmark}. Uses the idiomatic
 * Artemis pattern: mutable {@code Component} subclasses, {@code IteratingSystem}
 * with {@code ComponentMapper} field injection, per-entity in-place mutation.
 *
 * Artemis has no resource concept, so {@code dt} is a world-level field
 * consulted by the integration system.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ArtemisNBodyBenchmark {

    public static class Position extends Component {
        public float x, y, z;
    }

    public static class Velocity extends Component {
        public float dx, dy, dz;
    }

    public static class Mass extends Component {
        public float m;
    }

    public static class IntegrateSystem extends IteratingSystem {
        ComponentMapper<Position> pm;
        ComponentMapper<Velocity> vm;
        public float dt = 0.001f;

        public IntegrateSystem() { super(Aspect.all(Position.class, Velocity.class)); }

        @Override
        protected void process(int id) {
            var p = pm.get(id);
            var v = vm.get(id);
            p.x += v.dx * dt;
            p.y += v.dy * dt;
            p.z += v.dz * dt;
        }
    }

    @Param({"1000", "10000"})
    int bodyCount;

    World world;

    @Setup
    public void setup() {
        world = new World(new WorldConfigurationBuilder()
            .with(new IntegrateSystem()).build());

        var pm = world.getMapper(Position.class);
        var vm = world.getMapper(Velocity.class);
        var mm = world.getMapper(Mass.class);
        for (int i = 0; i < bodyCount; i++) {
            int e = world.create();
            float angle = (float)(2 * Math.PI * i / bodyCount);
            var p = pm.create(e);
            p.x = (float)Math.cos(angle) * 100;
            p.y = (float)Math.sin(angle) * 100;
            p.z = 0;
            var v = vm.create(e);
            v.dx = -(float)Math.sin(angle) * 10;
            v.dy = (float)Math.cos(angle) * 10;
            v.dz = 0;
            var m = mm.create(e);
            m.m = 1.0f;
        }
    }

    @Benchmark
    public void simulateOneTick() {
        world.process();
    }

    @Benchmark
    public void simulateTenTicks() {
        for (int i = 0; i < 10; i++) {
            world.process();
        }
    }
}
