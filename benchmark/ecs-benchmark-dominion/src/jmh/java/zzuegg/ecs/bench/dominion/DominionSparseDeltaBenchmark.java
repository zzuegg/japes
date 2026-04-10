package zzuegg.ecs.bench.dominion;

import dev.dominion.ecs.api.Dominion;
import dev.dominion.ecs.api.Entity;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

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
 *      resets the buffer cursor to zero for the next tick.
 *
 * The dirty buffer is a fixed-size array (size = BATCH), reused across ticks
 * so the benchmark doesn't measure allocator throughput. This is the fastest
 * honest implementation a Dominion user can write — but it relies on the
 * user remembering to update the dirty list everywhere Health is mutated.
 * That contract is enforced by the library in japes/Zay-ES/Bevy and has to
 * be hand-maintained here.
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
    Entity[] dirtyBuf;

    @Setup(Level.Iteration)
    public void setup() {
        world = Dominion.create();
        handles = new Entity[entityCount];
        for (int i = 0; i < entityCount; i++) {
            handles[i] = world.createEntity(new Health(1000));
        }
        cursor = 0;
        observedCount = 0;
        dirtyBuf = new Entity[BATCH];
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public void tick(Blackhole bh) {
        // 1. Driver: damage 100 entities, and remember which ones were touched.
        int dirtyCount = 0;
        for (int i = 0; i < BATCH; i++) {
            var e = handles[cursor];
            cursor = (cursor + 1) % handles.length;
            var h = e.get(Health.class);
            h.hp -= 1;
            dirtyBuf[dirtyCount++] = e;
        }

        // 2. Observer: walk exactly the dirty buffer — per-tick work is
        //    proportional to dirtyCount, not entityCount.
        for (int i = 0; i < dirtyCount; i++) {
            observedCount++;
            bh.consume(dirtyBuf[i].get(Health.class));
        }
        bh.consume(observedCount);
    }
}
