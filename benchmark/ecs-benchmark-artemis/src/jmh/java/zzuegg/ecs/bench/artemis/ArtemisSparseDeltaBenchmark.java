package zzuegg.ecs.bench.artemis;

import com.artemis.Component;
import com.artemis.ComponentMapper;
import com.artemis.World;
import com.artemis.WorldConfigurationBuilder;
import com.artemis.utils.IntBag;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Artemis counterpart of {@code SparseDeltaBenchmark}.
 *
 * Artemis-odb has no built-in component-value change detection, so the
 * canonical performance-conscious pattern is to maintain a dirty list at the
 * mutation site. This benchmark measures exactly that:
 *
 *   1. Driver damages 100 entities per tick via the rotating cursor, mutating
 *      Health in place and pushing each id onto a caller-maintained
 *      {@link IntBag} (the Artemis-idiomatic int collection).
 *   2. "Observer" pass iterates the IntBag, consumes each Health, and clears
 *      the bag for the next tick.
 *
 * The bag is a default-constructed {@link IntBag} (Artemis's default capacity,
 * grows as needed) so we measure the cost a production Artemis user would
 * actually pay. An earlier revision used {@code new IntBag(BATCH)}, pre-sizing
 * the bag to the exact per-tick count — that was cheating, because a real
 * user writing a mutation-driven dirty list wouldn't know the exact number
 * of entities touched per frame.
 *
 * This is still the fastest honest implementation an Artemis user can write,
 * but it relies on the user remembering to append to the bag at every Health
 * mutation site — a contract the library enforces automatically in
 * japes/Zay-ES/Bevy.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ArtemisSparseDeltaBenchmark {

    public static class Health extends Component {
        public int hp;
    }

    @Param({"10000"})
    int entityCount;

    static final int BATCH = 100;

    World world;
    ComponentMapper<Health> hm;
    int[] handles;
    int cursor;
    long observedCount;

    // Caller-maintained dirty buffer — the point of the benchmark.
    // Default-constructed IntBag so the benchmark pays the realistic growth
    // cost instead of being pre-sized to the exact per-tick batch.
    IntBag dirtyBuf;

    @Setup(Level.Iteration)
    public void setup() {
        world = new World(new WorldConfigurationBuilder().build());
        hm = world.getMapper(Health.class);
        handles = new int[entityCount];
        for (int i = 0; i < entityCount; i++) {
            int e = world.create();
            var h = hm.create(e);
            h.hp = 1000;
            handles[i] = e;
        }
        // Prime so the aspect is populated.
        world.process();
        cursor = 0;
        observedCount = 0;
        dirtyBuf = new IntBag();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.dispose();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // 1. Driver: damage 100 entities, mark them dirty.
        dirtyBuf.setSize(0);
        for (int i = 0; i < BATCH; i++) {
            int e = handles[cursor];
            cursor = (cursor + 1) % handles.length;
            var h = hm.get(e);
            h.hp -= 1;
            dirtyBuf.add(e);
        }

        // 2. Observer: walk exactly the dirty bag — work is proportional to
        //    dirtyBuf.size(), not the world entity count.
        int size = dirtyBuf.size();
        int[] data = dirtyBuf.getData();
        for (int i = 0; i < size; i++) {
            observedCount++;
            bh.consume(hm.get(data[i]));
        }
        bh.consume(observedCount);
    }
}
