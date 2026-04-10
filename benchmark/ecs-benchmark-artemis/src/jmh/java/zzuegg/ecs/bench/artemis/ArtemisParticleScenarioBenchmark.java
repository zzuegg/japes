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
 * Particle-scenario benchmark for Artemis-odb.
 *
 * Mirrors {@code ecs-benchmark.ParticleScenarioBenchmark}: move, damage, reap,
 * stats, respawn. Artemis has no change detection or command buffer, so
 * reaping uses {@code world.delete(id)} which is deferred to the next
 * {@code process()} call automatically — that's the idiomatic Artemis way.
 *
 * Stats counts deaths by reading the death counter updated by the damage pass.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ArtemisParticleScenarioBenchmark {

    public static class Position extends Component {
        public float x, y, z;
    }

    public static class Velocity extends Component {
        public float dx, dy, dz;
    }

    public static class Lifetime extends Component {
        public int ttl;
    }

    public static class Health extends Component {
        public int hp;
    }

    public static class MoveSystem extends IteratingSystem {
        ComponentMapper<Position> pm;
        ComponentMapper<Velocity> vm;

        public MoveSystem() { super(Aspect.all(Position.class, Velocity.class)); }

        @Override
        protected void process(int id) {
            var p = pm.get(id);
            var v = vm.get(id);
            p.x += v.dx;
            p.y += v.dy;
            p.z += v.dz;
        }
    }

    public static class DamageReapSystem extends IteratingSystem {
        ComponentMapper<Health> hm;
        public long deaths;

        public DamageReapSystem() { super(Aspect.all(Health.class)); }

        @Override
        protected void begin() { deaths = 0; }

        @Override
        protected void process(int id) {
            var h = hm.get(id);
            h.hp -= 1;
            if (h.hp <= 0) {
                deaths++;
                world.delete(id);
            }
        }
    }

    public static class StatsSystem extends IteratingSystem {
        ComponentMapper<Lifetime> lm;
        public long alive;

        public StatsSystem() { super(Aspect.all(Lifetime.class)); }

        @Override
        protected void begin() { alive = 0; }

        @Override
        protected void process(int id) {
            if (lm.get(id).ttl > 0) alive++;
        }
    }

    @Param({"10000"})
    int entityCount;

    World world;
    MoveSystem moveSystem;
    DamageReapSystem damageReapSystem;
    StatsSystem statsSystem;
    long totalDeaths;

    @Setup(Level.Iteration)
    public void setup() {
        moveSystem = new MoveSystem();
        damageReapSystem = new DamageReapSystem();
        statsSystem = new StatsSystem();
        world = new World(new WorldConfigurationBuilder()
            .with(moveSystem, damageReapSystem, statsSystem).build());

        var pm = world.getMapper(Position.class);
        var vm = world.getMapper(Velocity.class);
        var lm = world.getMapper(Lifetime.class);
        var hm = world.getMapper(Health.class);

        for (int i = 0; i < entityCount; i++) {
            int e = world.create();
            var p = pm.create(e); p.x = i; p.y = i; p.z = i;
            var v = vm.create(e); v.dx = 1; v.dy = 1; v.dz = 1;
            var l = lm.create(e); l.ttl = 1000;
            var h = hm.create(e); h.hp = 1 + (i % 100);
        }
        totalDeaths = 0;
        // Prime once so newly spawned entities are in the system aspects.
        world.process();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.dispose();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        world.process();
        totalDeaths += damageReapSystem.deaths;

        // 5. respawn — pad back to target using current aspect sizes.
        long alive = statsSystem.alive;
        int target = 10_000;
        long shortfall = target - alive;
        var pm = world.getMapper(Position.class);
        var vm = world.getMapper(Velocity.class);
        var lm = world.getMapper(Lifetime.class);
        var hm = world.getMapper(Health.class);
        for (long i = 0; i < shortfall; i++) {
            int e = world.create();
            var p = pm.create(e); p.x = 0; p.y = 0; p.z = 0;
            var v = vm.create(e); v.dx = 1; v.dy = 1; v.dz = 1;
            var l = lm.create(e); l.ttl = 1000;
            var h = hm.create(e); h.hp = 100;
        }
        bh.consume(alive);
    }
}
