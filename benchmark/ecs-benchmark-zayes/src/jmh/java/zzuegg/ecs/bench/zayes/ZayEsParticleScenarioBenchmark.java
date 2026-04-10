package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Zay-ES counterpart of ParticleScenarioBenchmark.
 *
 * Written in the canonical Zay-ES "AppState per logical system" shape: each
 * system owns its own {@code EntitySet} and calls {@code applyChanges()}
 * exactly once at the start of its update — the same pattern real Zay-ES
 * games use.
 *
 * Per tick:
 *   1. MoveState.update()    — iterate (Position, Velocity), setComponent Position
 *   2. DamageState.update()  — iterate Health, setComponent with hp-1
 *   3. ReapState.update()    — iterate getChangedEntities over Health, removeEntity if hp<=0
 *   4. StatsState.update()   — drain getRemovedEntities for death count;
 *                              count alive via Lifetime set
 *   5. RespawnState.update() — recreate N-alive entities to keep total at 10k
 *
 * This is a strict superset of what a real AppState pipeline does, but
 * nothing more — no extra applyChanges calls between unrelated sets, no
 * sharing of EntitySets across logically distinct concerns.
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

    // One EntitySet per logical AppState. Each state owns its own set and
    // calls applyChanges() once per tick at the start of its update.
    EntitySet moveSet;     // MoveState
    EntitySet damageSet;   // DamageState
    EntitySet reapSet;     // ReapState   — separate from DamageState's set
    EntitySet statsSet;    // StatsState  — owns its own delta views
    EntitySet aliveSet;    // StatsState's "count alive" auxiliary

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
        reapSet = data.getEntities(Health.class);
        statsSet = data.getEntities(Health.class);
        aliveSet = data.getEntities(Lifetime.class);

        // Prime all sets so the first benchmark tick is steady-state.
        moveSet.applyChanges();
        damageSet.applyChanges();
        reapSet.applyChanges();
        statsSet.applyChanges();
        aliveSet.applyChanges();
        totalDeaths = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (moveSet != null) moveSet.release();
        if (damageSet != null) damageSet.release();
        if (reapSet != null) reapSet.release();
        if (statsSet != null) statsSet.release();
        if (aliveSet != null) aliveSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // 1. MoveState.update()
        moveSet.applyChanges();
        for (Entity e : moveSet) {
            var pos = e.get(Position.class);
            var vel = e.get(Velocity.class);
            data.setComponent(e.getId(),
                new Position(pos.x() + vel.dx(), pos.y() + vel.dy(), pos.z() + vel.dz()));
        }

        // 2. DamageState.update()
        damageSet.applyChanges();
        for (Entity e : damageSet) {
            var h = e.get(Health.class);
            data.setComponent(e.getId(), new Health(h.hp() - 1));
        }

        // 3. ReapState.update()
        //    applyChanges here picks up the Health writes from DamageState.
        //    Iterating getChangedEntities() is the idiomatic "react to what
        //    changed since my last run" pattern — for this workload every
        //    entity is dirty so it's functionally the same as full iteration,
        //    but it's the shape a real Zay-ES system would use.
        reapSet.applyChanges();
        var toRemove = new ArrayList<EntityId>();
        for (Entity e : reapSet.getChangedEntities()) {
            if (e.get(Health.class).hp() <= 0) {
                toRemove.add(e.getId());
            }
        }
        for (var id : toRemove) {
            data.removeEntity(id);
        }

        // 4. StatsState.update()
        //    This state's applyChanges now sees the removes from ReapState.
        //    The aliveSet is an auxiliary view owned by the same state for
        //    the "count alive" statistic.
        statsSet.applyChanges();
        long deathsThisTick = 0;
        for (Entity e : statsSet.getRemovedEntities()) {
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

        // 5. RespawnState.update() — recreate to keep total at 10k.
        for (int i = 0; i < deathsThisTick; i++) {
            var id = data.createEntity();
            data.setComponents(id,
                new Position(0, 0, 0),
                new Velocity(1, 1, 1),
                new Lifetime(1000),
                new Health(100));
        }

        bh.consume(totalDeaths);
    }
}
