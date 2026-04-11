package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.*;

/**
 * Dominion counterpart of {@code RealisticTickBenchmark} — the
 * "naive scan" implementation of a realistic multi-observer tick.
 *
 * Dominion has no change detection, so the fair "no extra engineering"
 * observer shape is: walk the entire component archetype each tick. Three
 * observers × 10 000 entities = 30 000 entity touches per tick,
 * regardless of how sparse the mutations were.
 *
 * The hand-rolled-dirty-list alternative is cheaper on the micro (see
 * {@code DominionSparseDeltaBenchmark}), but it has to be maintained
 * per-component, per-observer, at every mutation site — which is exactly
 * what the japes library does for you via {@code @Filter(Changed)}. This
 * benchmark measures the "lazy user who doesn't want to hand-roll three
 * dirty lists for every tick" path, which is still realistic Dominion code.
 *
 * {@code @Param executor}: "st" = sequential passes, "mt" = manual
 * ExecutorService-based fan-out of the three observer passes (japes does
 * this automatically from the system access metadata).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class DominionRealisticTickBenchmark {

    public static final class Position {
        public float x, y, z;
        public Position(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    public static final class Velocity {
        public float dx, dy, dz;
        public Velocity(float dx, float dy, float dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
    }

    public static final class Health {
        public int hp;
        public Health(int hp) { this.hp = hp; }
    }

    public static final class Mana {
        public int points;
        public Mana(int points) { this.points = points; }
    }

    @Param({"10000", "100000"})
    int entityCount;

    @Param({"st"})
    String executor;

    static final int BATCH = 100;

    Dominion world;
    Entity[] handles;
    int positionCursor, healthCursor, manaCursor;
    ExecutorService pool;

    long sumX, sumHp, sumMana;

    @Setup(Level.Iteration)
    public void setup() {
        world = Dominion.create();
        handles = new Entity[entityCount];
        for (int i = 0; i < entityCount; i++) {
            handles[i] = world.createEntity(
                new Position(i, i, i),
                new Velocity(1, 1, 1),
                new Health(1_000_000),
                new Mana(0));
        }
        sumX = sumHp = sumMana = 0;
        positionCursor = 0;
        healthCursor = BATCH;
        manaCursor = 2 * BATCH;
        pool = "mt".equals(executor)
            ? java.util.concurrent.Executors.newFixedThreadPool(3)
            : null;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
        if (pool != null) pool.shutdownNow();
    }

    // Full-scan observer passes. Dominion has no Changed filter so each
    // pass iterates every entity that has the component.
    private long observePositions() {
        long sum = 0;
        var rs = world.findEntitiesWith(Position.class);
        for (var r : rs) sum += (long) r.comp().x;
        return sum;
    }
    private long observeHealths() {
        long sum = 0;
        var rs = world.findEntitiesWith(Health.class);
        for (var r : rs) sum += r.comp().hp;
        return sum;
    }
    private long observeManas() {
        long sum = 0;
        var rs = world.findEntitiesWith(Mana.class);
        for (var r : rs) sum += r.comp().points;
        return sum;
    }

    @Benchmark
    public void tick(Blackhole bh) throws Exception {
        int n = handles.length;
        // Driver: 300 sparse mutations, rotating cursors.
        for (int i = 0; i < BATCH; i++) {
            var e = handles[positionCursor];
            positionCursor = (positionCursor + 1) % n;
            var p = e.get(Position.class);
            p.x += 1;
        }
        for (int i = 0; i < BATCH; i++) {
            var e = handles[healthCursor];
            healthCursor = (healthCursor + 1) % n;
            e.get(Health.class).hp -= 1;
        }
        for (int i = 0; i < BATCH; i++) {
            var e = handles[manaCursor];
            manaCursor = (manaCursor + 1) % n;
            e.get(Mana.class).points += 1;
        }

        // Observer phase — three full-scan passes, either serially or
        // dispatched manually to a thread pool.
        if (pool == null) {
            sumX += observePositions();
            sumHp += observeHealths();
            sumMana += observeManas();
        } else {
            var f1 = pool.submit(this::observePositions);
            var f2 = pool.submit(this::observeHealths);
            var f3 = pool.submit(this::observeManas);
            sumX += f1.get();
            sumHp += f2.get();
            sumMana += f3.get();
        }
        bh.consume(sumX);
        bh.consume(sumHp);
        bh.consume(sumMana);
    }
}
