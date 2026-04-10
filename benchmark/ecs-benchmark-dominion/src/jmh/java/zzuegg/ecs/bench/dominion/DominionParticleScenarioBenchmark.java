package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Particle-scenario benchmark for Dominion.
 *
 * Mirrors {@code ecs-benchmark.ParticleScenarioBenchmark}: move, damage, reap,
 * stats, respawn. Dominion has no built-in change detection or command buffer,
 * so reaping is done by collecting dead-entity handles during the damage pass
 * and deleting them with {@code deleteEntity} — the idiomatic Dominion pattern.
 *
 * Stats reports the death count from the reap pass directly (Dominion has no
 * {@code RemovedComponents} equivalent).
 *
 * Components are mutable classes so per-entity work is in-place mutation,
 * matching how Dominion is designed to be used.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DominionParticleScenarioBenchmark {

    public static final class Position {
        public float x, y, z;
        public Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    public static final class Velocity {
        public float dx, dy, dz;
        public Velocity(float dx, float dy, float dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
    }

    public static final class Lifetime {
        public int ttl;
        public Lifetime(int ttl) { this.ttl = ttl; }
    }

    public static final class Health {
        public int hp;
        public Health(int hp) { this.hp = hp; }
    }

    @Param({"10000"})
    int entityCount;

    Dominion world;
    long totalDeaths;
    long totalAlive;

    // Reused scratch buffer so reap doesn't allocate per tick.
    final List<Entity> reapBuf = new ArrayList<>(256);

    @Setup(Level.Iteration)
    public void setup() {
        world = Dominion.create();
        totalDeaths = 0;
        totalAlive = 0;
        reapBuf.clear();
        for (int i = 0; i < entityCount; i++) {
            int startHp = 1 + (i % 100);
            world.createEntity(
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(startHp));
        }
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // 1. move — in-place.
        var moves = world.findEntitiesWith(Position.class, Velocity.class);
        for (var r : moves) {
            var p = r.comp1();
            var v = r.comp2();
            p.x += v.dx;
            p.y += v.dy;
            p.z += v.dz;
        }

        // 2. damage + 3. reap — fused so we can collect dead handles cheaply.
        reapBuf.clear();
        var dmg = world.findEntitiesWith(Health.class);
        for (var r : dmg) {
            var h = r.comp();
            h.hp -= 1;
            if (h.hp <= 0) {
                reapBuf.add(r.entity());
            }
        }
        long deaths = reapBuf.size();
        for (int i = 0, n = reapBuf.size(); i < n; i++) {
            world.deleteEntity(reapBuf.get(i));
        }

        // 4. stats — count alive-with-lifetime inline.
        long alive = 0;
        var lifes = world.findEntitiesWith(Lifetime.class);
        for (var r : lifes) {
            if (r.comp().ttl > 0) alive++;
        }
        totalDeaths += deaths;
        totalAlive = alive;

        // 5. respawn — pad back to target.
        int target = 10_000;
        long shortfall = target - alive;
        for (long i = 0; i < shortfall; i++) {
            world.createEntity(
                new Position(0, 0, 0),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(100));
        }

        bh.consume(alive);
    }
}
