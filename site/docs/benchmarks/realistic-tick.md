# Realistic multi-observer tick

`RealisticTickBenchmark` is the shape a real game-loop actually has.
It exists to answer the obvious follow-up to the [sparse-delta
page](sparse-delta.md): *"but what if I add more observers?"*

!!! tip "What this workload measures"

    - **Population.** 10 000 (and, for the scaling cell, 100 000)
      entities carrying `{Position, Velocity, Health, Mana}`.
    - **Mutation.** 1% turnover per tick — 100 sparse mutations per
      component, three rotating cursors so the three slices don't
      overlap.
    - **Observation.** Three `@Filter(Changed)` observers, one per
      component, each accumulating a per-observer sum into a shared
      `Stats` resource.
    - **Executors.** Two variants: `st` (single-threaded scheduler)
      and `mt` (japes `MultiThreadedExecutor`, ForkJoinPool-backed,
      parallelises disjoint systems automatically).

    Counterparts exist for every library in the comparison. Bevy and
    Zay-ES use their native change-detection primitives (`Changed<T>`
    query filter and `EntitySet.getChangedEntities()` respectively).
    Dominion and Artemis have no built-in change detection, so their
    observer passes do full iterations over every entity with the
    component — the "lazy user" path. The `mt` variant in Dominion /
    Artemis dispatches the three observer passes to a fixed
    `ExecutorService`, exactly what japes does for you from the
    declared system access metadata.

## Results

100 dirty per component per tick, µs/op — lower is better. Copied
verbatim from `DEEP_DIVE.md`.

| library              | 10k µs/op | 100k µs/op | scaling |  cost model                       |
|----------------------|----------:|-----------:|--------:|-----------------------------------|
| **japes** st         |  **5.86** |   **7.91** |   1.35× | dirty-list skip (scales with K)   |
| zay-es               |      15.4 |       19.6 |   1.27× | dirty-list skip (scales with K)   |
| bevy (native Rust)   |      8.81 |       76.9 |   8.73× | full archetype scan (scales w/ N) |
| artemis st           |      24.5 |        279 |  11.4×  | full archetype scan (no CD)       |
| dominion st          |      44.6 |        389 |   8.72× | full archetype scan (no CD)       |

The libraries split into two cost-model camps, empirically:

- **Dirty-list skip** (japes, Zay-ES) — a per-archetype list of slot
  indices that were mutated since the last prune. `@Filter(Changed)` /
  `EntitySet.getChangedEntities()` walks only that list. Per-tick
  cost is O(K) where K is the dirty count, not O(N) where N is total
  entities. Scaling from 10k→100k costs ~35% more on japes (larger
  handle array for the driver's `getComponent` lookups) and ~27%
  more on Zay-ES.
- **Full-archetype scan** (Bevy, Dominion, Artemis) — observers
  iterate the full archetype and either tick-compare every entity
  (Bevy's `Changed<T>`) or walk every component regardless (Dominion
  `findEntitiesWith`, Artemis `IteratingSystem` with no filter).
  Per-tick cost is O(N) because that's the algorithmic shape.
  Scaling from 10k→100k costs ~8–11× more.

**At 10k entities japes beats Bevy by 1.50×.** The gap looks modest
because 10k is small enough that Bevy's tight cache-friendly tick
scan is only paying ~3 µs of pure scan cost. **At 100k entities the
same workload is a 9.72× gap** — Bevy pays ~69 µs extra to scan
90 000 more tick words that japes never touches.

Worth calling out: **Zay-ES beats Bevy at 100k** (19.6 vs 76.9).
Zay-ES has higher per-mutation overhead than japes (more allocations
in the driver side, per-set `applyChanges()` calls) but its
`EntitySet.getChangedEntities()` is a dirty-list skip, so it scales
the same shape as japes. The two dirty-list libraries stay in the
same cost bucket at any entity count; the three scan libraries scale
out of it past ~50k.

## How the two cost models separate

The per-additional-entity cost at the 10k → 100k step tells the
whole story:

| library            | Δ µs for Δ 90k entities | per-entity overhead |
|--------------------|------------------------:|--------------------:|
| **japes** st       |                   +2.05 |       23 ns / entity |
| zay-es             |                   +4.20 |       47 ns / entity |
| bevy               |                  +68.1  |      757 ns / entity |
| artemis st         |                  +254   |    2 828 ns / entity |
| dominion st        |                  +344   |    3 827 ns / entity |

japes's ~23 ns/entity is driver-side cost (the handle list grows,
the archetype's chunk list grows, `getComponent` walks slightly
further). The observer side is ~flat because the dirty list is
still 300 slots.

Bevy's ~757 ns/entity breaks down as 3 observers × ~252 ns = each
observer does roughly one tick-word load + compare + branch per
entity, which at ~0.25 ns/check × 100k entities × 3 observers ≈
76 µs. Matches.

Dominion / Artemis pay more per entity because their full scans
happen in the user-facing benchmark driver too (each observer calls
`findEntitiesWith` / `IteratingSystem.process` which rebuilds its
iterator state), not just inside a tight Bevy-style `Changed<T>`
filter.

!!! note "Why Bevy doesn't ship a dirty-slot list for `Changed<T>`"

    It's a deliberate API trade-off, not a missed optimisation.
    Tick-per-slot is cheaper *per mutation* (one store, no dedup,
    no append), which matters for Bevy's target workload — dense
    simulation where most components get touched every tick and the
    dirty list would contain most of the world. The catch with the
    dirty-list is opposite: it wins on sparse delta, loses on dense.

    japes pays ~5–10 ns extra per mutation for the dirty-list
    maintenance, which is invisible at 300 mutations/tick (total
    ~3 µs) but would start to hurt at millions of mutations/tick.
    Run japes on `iterateWithWrite` (every entity touched every
    tick, K = N) and Bevy wins by ~6× — the opposite direction,
    same cost model.

## DCE safety

Before trusting these numbers, the obvious question is "are we
hitting a dead-code-elimination trap anywhere?" The Bevy observer
body writes into `ResMut<RtStats>` which is never read outside the
benchmark closure — if the compiler can prove the writes have no
observable effect, it's allowed to delete the observer bodies
entirely.

Explicitly checked:

- **japes.** The `@Benchmark` body calls
  `bh.consume(stats.sumX)` / `sumHp` / `sumMana` at the end of
  every tick. JMH's `Blackhole.consume` is opaque to the JIT, so
  the accumulation chain is preserved.
- **Bevy.** The `b.iter(||)` closure calls
  `world.resource::<RtStats>()` + `std::hint::black_box(stats.sum_x)`
  / `sum_hp` / `sum_mana` after `schedule.run`. `black_box` is
  rustc's equivalent of `Blackhole.consume`.

Re-ran Bevy after adding the `black_box` guards: result 8.81 µs at
10k (was 8.80 µs without the guard). Delta is pure measurement
noise, which means **DCE wasn't happening even without the guard** —
the cross-crate call chain
`schedule.run → system fn pointer → observer body` already defeats
rustc's DCE at the default `cargo bench` opt level (`opt-level = 3`,
no LTO). The guard is there as insurance for future readers.

## Same-work audit — driver parity

Each library's driver does 300 sparse mutations per tick via three
rotating cursors. The operation shapes differ slightly:

| library    | operation per mutation                                  | per-mutation alloc |
|------------|---------------------------------------------------------|--------------------|
| japes      | `world.setComponent(e, new Position(...))` (new record) | **allocates**      |
| zay-es     | `data.setComponent(id, new Position(...))`              | **allocates**      |
| bevy       | `world.get_mut::<Position>(e).x += 1.0`                 | in-place           |
| dominion   | `e.get(Position.class).x += 1` (mutable POJO)           | in-place           |
| artemis    | `pm.get(e).x += 1` (mutable Component subclass)         | in-place           |

This is an asymmetry on the driver side: japes and Zay-ES allocate
300 record instances per tick that Bevy / Dominion / Artemis don't.
Direction of the asymmetry: **favours Bevy / Dominion / Artemis**.
japes is paying extra allocation cost its comparison-peers aren't —
and **still winning**. If we fixed the asymmetry (either by making
japes's driver mutate in place somehow, or by making Bevy's driver
allocate new records), the 9.72× gap at 100k would widen further,
not shrink.

## Code comparison (single-threaded path)

The japes observer is 11 lines including the class declaration:

```java
public static final class HealthObserver {
    final Stats stats;
    HealthObserver(Stats stats) { this.stats = stats; }

    @System(stage = "PostUpdate")
    @Filter(value = Changed.class, target = Health.class)
    void observe(@Read Health h) {
        stats.sumHp += h.hp();
    }
}

// ... and the builder:
World.builder()
    .executor(Executors.multiThreaded())   // <-- parallelism opt-in
    .addSystem(new PositionObserver(stats))
    .addSystem(new HealthObserver(stats))
    .addSystem(new ManaObserver(stats))
    .build();
// scheduler knows these observers read disjoint components
// and fans them out across the ForkJoinPool automatically.
```

And the Dominion / Artemis counterpart (in the `mt` path — the `st`
path is similar but without the executor):

```java
ExecutorService pool = Executors.newFixedThreadPool(3);

private long observePositions() {
    long sum = 0;
    var rs = world.findEntitiesWith(Position.class);
    for (var r : rs) sum += (long) r.comp().x;
    return sum;
}
// ... and two more observer functions ...

public void tick() {
    // ... sparse mutations ...
    var f1 = pool.submit(this::observePositions);
    var f2 = pool.submit(this::observeHealths);
    var f3 = pool.submit(this::observeManas);
    sumX += f1.get();
    sumHp += f2.get();
    sumMana += f3.get();
}
```

The Dominion / Artemis version has to know a priori that the three
observers don't conflict, know to dispatch them in parallel, own
the thread-pool lifecycle, and do all of this over again every time
an observer is added or removed. japes knows all of that from
`@Read` / `@Write` / `@Filter` annotations and the scheduler's DAG
builder.

## Valhalla delta

| benchmark            |      case | **japes** | **japes-v** | Δ              |
|----------------------|----------:|----------:|------------:|---------------:|
| `RealisticTick tick` | 10k / st  |      5.86 |        11.9 | 0.49× slower   |
| `RealisticTick tick` | 10k / mt  |      10.3 |        17.8 | 0.58× slower   |

Valhalla regresses this benchmark by 42–52% (down from 74% in
earlier rounds). The same root cause as on the [particle
scenario](particle-scenario.md) — value records crossing the erased
`Record` parameter of `World.setComponent` box into a heap wrapper.
See the [Valhalla page](valhalla.md) for full breakdown.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "RealisticTickBenchmark" \
  -p entityCount=10000,100000
```
