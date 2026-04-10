package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Dominion counterpart of {@code SparseDeltaBenchmark}.
 *
 * Dominion has no built-in change detection, so the canonical
 * performance-conscious pattern is to maintain a dirty list at the mutation
 * site. The benchmark measures exactly that:
 *
 *   1. Driver damages 100 entities per tick via the rotating cursor, mutating
 *      Health in place and pushing each entity onto a caller-maintained
 *      dirty buffer.
 *   2. "Observer" pass iterates the dirty buffer, consumes each Health, and
 *      clears the buffer for the next tick.
 *
 * The dirty buffer is a reused {@link ArrayList} with Java's default initial
 * capacity — realistic "declare a field, clear it per tick" code, not a
 * pre-sized array that assumes the benchmark's exact per-tick batch count.
 * Earlier revisions of this benchmark used {@code new Entity[BATCH]} which
 * was cheating: a real Dominion user writing a mutation-driven dirty list
 * wouldn't know the exact number of entities they'll touch each frame.
 *
 * This is still the fastest honest implementation a Dominion user can write,
 * but it relies on the user remembering to update the dirty list everywhere
 * Health is mutated. That contract is enforced by the library in
 * japes/Zay-ES/Bevy and has to be hand-maintained here.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DominionSparseDeltaBenchmark {

    public static final class Health {
        public int hp;
        public Health(int hp) { this.hp = hp; }
    }

    @Param({"10000"})
    int entityCount;

    static final int BATCH = 100;

    Dominion world;
    Entity[] handles;
    int cursor;
    long observedCount;

    // Caller-maintained dirty buffer — the whole point of the benchmark.
    // Default-constructed ArrayList (capacity 10, grows as needed) so we
    // measure the cost a production Dominion user would actually pay.
    ArrayList<Entity> dirtyBuf;

    @Setup(Level.Iteration)
    public void setup() {
        world = Dominion.create();
        handles = new Entity[entityCount];
        for (int i = 0; i < entityCount; i++) {
            handles[i] = world.createEntity(new Health(1000));
        }
        cursor = 0;
        observedCount = 0;
        dirtyBuf = new ArrayList<>();
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // Clear-and-reuse. ArrayList.clear() resets size to 0 without
        // shrinking the backing array, so subsequent ticks don't pay the
        // growth cost once the list has stabilised.
        dirtyBuf.clear();

        // 1. Driver: damage 100 entities, and remember which ones were touched.
        for (int i = 0; i < BATCH; i++) {
            var e = handles[cursor];
            cursor = (cursor + 1) % handles.length;
            var h = e.get(Health.class);
            h.hp -= 1;
            dirtyBuf.add(e);
        }

        // 2. Observer: walk exactly the dirty buffer — per-tick work is
        //    proportional to dirty count, not entity count.
        for (int i = 0, n = dirtyBuf.size(); i < n; i++) {
            observedCount++;
            bh.consume(dirtyBuf.get(i).get(Health.class));
        }
        bh.consume(observedCount);
    }
}
