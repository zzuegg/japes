package zzuegg.ecs.bench.artemis;

import com.artemis.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.*;

/**
 * Artemis counterpart of {@code RealisticTickBenchmark} — the "naive scan"
 * implementation of a realistic multi-observer tick.
 *
 * Artemis has no built-in change detection. The fair "no extra engineering"
 * observer shape is: walk the entire aspect each tick. Three observers ×
 * 10 000 entities = 30 000 entity touches per tick, regardless of how
 * sparse the mutations were.
 *
 * The hand-rolled-dirty-list alternative is cheaper on the micro (see
 * {@code ArtemisSparseDeltaBenchmark}), but maintaining a dirty buffer per
 * observed component at every mutation site is exactly the kind of
 * bookkeeping the japes library handles automatically with
 * {@code @Filter(Changed)}. This benchmark measures the realistic "lazy
 * user" path.
 *
 * {@code @Param executor}: "st" = sequential passes, "mt" = manual
 * ExecutorService-based fan-out of the three observer passes (the japes
 * scheduler does this for free from the declared access metadata).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class ArtemisRealisticTickBenchmark {

    public static class Position extends Component {
        public float x, y, z;
    }

    public static class Velocity extends Component {
        public float dx, dy, dz;
    }

    public static class Health extends Component {
        public int hp;
    }

    public static class Mana extends Component {
        public int points;
    }

    @Param({"10000", "100000"})
    int entityCount;

    @Param({"st"})
    String executor;

    static final int BATCH = 100;

    World world;
    ComponentMapper<Position> pm;
    ComponentMapper<Velocity> vm;
    ComponentMapper<Health> hm;
    ComponentMapper<Mana> mm;
    int[] handles;
    int positionCursor, healthCursor, manaCursor;
    ExecutorService pool;

    long sumX, sumHp, sumMana;

    @Setup(Level.Iteration)
    public void setup() {
        world = new World(new WorldConfigurationBuilder().build());
        pm = world.getMapper(Position.class);
        vm = world.getMapper(Velocity.class);
        hm = world.getMapper(Health.class);
        mm = world.getMapper(Mana.class);
        handles = new int[entityCount];
        for (int i = 0; i < entityCount; i++) {
            int e = world.create();
            var p = pm.create(e); p.x = i; p.y = i; p.z = i;
            var v = vm.create(e); v.dx = 1; v.dy = 1; v.dz = 1;
            hm.create(e).hp = 1_000_000;
            mm.create(e).points = 0;
            handles[i] = e;
        }
        world.process(); // prime
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
        if (world != null) world.dispose();
        if (pool != null) pool.shutdownNow();
    }

    // Full-scan observer passes — one per component. No change detection,
    // so each pass iterates every entity in the world that has that
    // component.
    private long observePositions() {
        long sum = 0;
        var ids = handles;
        for (int i = 0, n = ids.length; i < n; i++) sum += (long) pm.get(ids[i]).x;
        return sum;
    }
    private long observeHealths() {
        long sum = 0;
        var ids = handles;
        for (int i = 0, n = ids.length; i < n; i++) sum += hm.get(ids[i]).hp;
        return sum;
    }
    private long observeManas() {
        long sum = 0;
        var ids = handles;
        for (int i = 0, n = ids.length; i < n; i++) sum += mm.get(ids[i]).points;
        return sum;
    }

    @Benchmark
    public void tick(Blackhole bh) throws Exception {
        int n = handles.length;
        // Driver: 300 sparse mutations, rotating cursors.
        for (int i = 0; i < BATCH; i++) {
            int e = handles[positionCursor];
            positionCursor = (positionCursor + 1) % n;
            pm.get(e).x += 1;
        }
        for (int i = 0; i < BATCH; i++) {
            int e = handles[healthCursor];
            healthCursor = (healthCursor + 1) % n;
            hm.get(e).hp -= 1;
        }
        for (int i = 0; i < BATCH; i++) {
            int e = handles[manaCursor];
            manaCursor = (manaCursor + 1) % n;
            mm.get(e).points += 1;
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
