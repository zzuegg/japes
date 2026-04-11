package zzuegg.ecs.bench.zayes;

import com.simsilica.es.*;
import com.simsilica.es.base.DefaultEntityData;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Zay-ES counterpart of {@code RealisticTickBenchmark}.
 *
 * 10 000 entities with {Position, Velocity, Health, Mana}; per tick the
 * driver mutates 100 entities each for Position, Health, Mana via rotating
 * cursors (offset so the three slices don't overlap), then three
 * "AppState" passes each call {@code applyChanges()} on their own
 * {@link EntitySet} and iterate {@link EntitySet#getChangedEntities()}.
 *
 * This is the canonical Zay-ES multi-observer shape: one EntitySet per
 * observer, one {@code applyChanges()} call per update, iterate the delta
 * view. The japes / Dominion / Artemis / Bevy counterparts run the same
 * mutations-then-three-observers shape; putting the Zay-ES version next to
 * them lets the cross-library comparison include every library that has
 * native change detection.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ZayEsRealisticTickBenchmark {

    public record Position(float x, float y, float z) implements EntityComponent {}
    public record Velocity(float dx, float dy, float dz) implements EntityComponent {}
    public record Health(int hp) implements EntityComponent {}
    public record Mana(int points) implements EntityComponent {}

    @Param({"10000", "100000"})
    int entityCount;

    static final int BATCH = 100;

    EntityData data;
    // One EntitySet per observer — canonical Zay-ES AppState shape.
    EntitySet positionSet;
    EntitySet healthSet;
    EntitySet manaSet;
    List<EntityId> handles;
    int positionCursor, healthCursor, manaCursor;
    long sumX, sumHp, sumMana;

    @Setup(Level.Iteration)
    public void setup() {
        data = new DefaultEntityData();
        handles = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            var id = data.createEntity();
            data.setComponents(id,
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Health(1_000_000),
                new Mana(0));
            handles.add(id);
        }
        positionSet = data.getEntities(Position.class);
        healthSet   = data.getEntities(Health.class);
        manaSet     = data.getEntities(Mana.class);
        // Prime the sets past the initial spawn so the first benchmark tick
        // measures a steady-state delta, not the bulk-add event.
        positionSet.applyChanges();
        healthSet.applyChanges();
        manaSet.applyChanges();
        positionCursor = 0;
        healthCursor = BATCH;
        manaCursor = 2 * BATCH;
        sumX = sumHp = sumMana = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (positionSet != null) positionSet.release();
        if (healthSet != null) healthSet.release();
        if (manaSet != null) manaSet.release();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        int n = handles.size();
        // Driver: 300 sparse mutations, rotating cursors.
        for (int i = 0; i < BATCH; i++) {
            var id = handles.get(positionCursor);
            positionCursor = (positionCursor + 1) % n;
            var p = data.getComponent(id, Position.class);
            data.setComponent(id, new Position(p.x() + 1, p.y(), p.z()));
        }
        for (int i = 0; i < BATCH; i++) {
            var id = handles.get(healthCursor);
            healthCursor = (healthCursor + 1) % n;
            var h = data.getComponent(id, Health.class);
            data.setComponent(id, new Health(h.hp() - 1));
        }
        for (int i = 0; i < BATCH; i++) {
            var id = handles.get(manaCursor);
            manaCursor = (manaCursor + 1) % n;
            var m = data.getComponent(id, Mana.class);
            data.setComponent(id, new Mana(m.points() + 1));
        }

        // Three "AppState" observer passes, each reading its own delta view.
        positionSet.applyChanges();
        for (Entity e : positionSet.getChangedEntities()) {
            sumX += (long) e.get(Position.class).x();
        }
        healthSet.applyChanges();
        for (Entity e : healthSet.getChangedEntities()) {
            sumHp += e.get(Health.class).hp();
        }
        manaSet.applyChanges();
        for (Entity e : manaSet.getChangedEntities()) {
            sumMana += e.get(Mana.class).points();
        }

        bh.consume(sumX);
        bh.consume(sumHp);
        bh.consume(sumMana);
    }
}
