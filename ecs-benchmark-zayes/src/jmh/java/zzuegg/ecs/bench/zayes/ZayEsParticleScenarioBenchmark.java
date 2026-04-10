package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Zay-ES counterpart of ParticleScenarioBenchmark.
 *
 * Same per-tick pipeline as the reference:
 *   1. move     — Position += Velocity
 *   2. damage   — Health.hp -= 1
 *   3. reap     — despawn entities with hp <= 0
 *   4. stats    — count deaths (via getRemovedEntities) and alive count
 *   5. respawn  — replenish to 10k
 *
 * Written in Zay-ES idiom: one EntitySet per query shape, applyChanges at
 * stage boundaries, direct setComponent / removeEntity calls (no deferred
 * command buffer because Zay-ES doesn't have one).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ZayEsParticleScenarioBenchmark {

    public record Position(float x, float y, float z) implements EntityComponent {}
    public record Velocity(float dx, float dy, float dz) implements EntityComponent {}
    public record Lifetime(int ttl) implements EntityComponent {}
    public record Health(int hp) implements EntityComponent {}

    @Param({"10000"})
    int entityCount;

    EntityData data;
    EntitySet moveSet;     // Position + Velocity
    EntitySet damageSet;   // Health
    EntitySet healthSet;   // Health — change/remove tracking for deaths
    EntitySet aliveSet;    // Lifetime — alive-count stat
    long totalDeaths;

    @Setup(Level.Iteration)
    public void setup() {
        data = new DefaultEntityData();
        for (int i = 0; i < entityCount; i++) {
            var id = data.createEntity();
            int startHp = 1 + (i % 100);
            data.setComponents(id,
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(startHp));
        }
        moveSet = data.getEntities(Position.class, Velocity.class);
        damageSet = data.getEntities(Health.class);
        healthSet = data.getEntities(Health.class);
        aliveSet = data.getEntities(Lifetime.class);

        moveSet.applyChanges();
        damageSet.applyChanges();
        healthSet.applyChanges();
        aliveSet.applyChanges();
        totalDeaths = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (moveSet != null) moveSet.release();
        if (damageSet != null) damageSet.release();
        if (healthSet != null) healthSet.release();
        if (aliveSet != null) aliveSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // 1. Move — equivalent of the MoveSystem.
        for (Entity e : moveSet) {
            var pos = e.get(Position.class);
            var vel = e.get(Velocity.class);
            data.setComponent(e.getId(),
                new Position(pos.x() + vel.dx(), pos.y() + vel.dy(), pos.z() + vel.dz()));
        }

        // 2. Damage — DamageSystem.
        for (Entity e : damageSet) {
            var h = e.get(Health.class);
            data.setComponent(e.getId(), new Health(h.hp() - 1));
        }

        // Stage boundary: apply mutations before reap observes them.
        damageSet.applyChanges();
        healthSet.applyChanges();

        // 3. Reap — despawn entities at hp <= 0. Collect first, remove after
        //    iteration to avoid CME on the live EntitySet.
        var toRemove = new ArrayList<EntityId>();
        for (Entity e : healthSet) {
            if (e.get(Health.class).hp() <= 0) {
                toRemove.add(e.getId());
            }
        }
        for (var id : toRemove) {
            data.removeEntity(id);
        }

        // 4. Stats — drain the removed view for the death count, count alive.
        healthSet.applyChanges();
        long deathsThisTick = 0;
        for (Entity e : healthSet.getRemovedEntities()) {
            deathsThisTick++;
            bh.consume(e.getId());
        }
        totalDeaths += deathsThisTick;

        aliveSet.applyChanges();
        long alive = 0;
        for (Entity e : aliveSet) {
            if (e.get(Lifetime.class).ttl() > 0) alive++;
        }
        bh.consume(alive);

        // 5. Respawn — keep the total at 10_000.
        for (int i = 0; i < deathsThisTick; i++) {
            var id = data.createEntity();
            data.setComponents(id,
                new Position(0, 0, 0),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(100));
        }

        // End-of-tick: propagate the respawn additions so the next iteration
        // sees them in the sets.
        moveSet.applyChanges();
        damageSet.applyChanges();
        healthSet.applyChanges();
        aliveSet.applyChanges();

        bh.consume(totalDeaths);
    }
}
