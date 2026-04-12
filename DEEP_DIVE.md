# japes — deep dive

Full cross-library benchmark sweep, per-benchmark analysis, the
Valhalla investigation, and the API design commentary. For the
quick-start + headline numbers, see the [README](README.md).

## Contents

| # | Section | What it covers |
|--:|---|---|
| 1 | [Methodology](#methodology) | Hardware, JMH config, stock vs Valhalla setup |
| 2 | [Iteration micro-benchmark](#iteration-micro-benchmark) | Tight read/write loops at 1k / 10k / 100k entities |
| 3 | [N-body integration](#n-body-integration) | One integrator system, full-world tick |
| 4 | [Particle scenario](#particle-scenario-move--damage--reap--respawn--stats) | Move / damage / reap / respawn / stats, 10k entities |
| 5 | [Sparse delta](#sparse-delta-change-detection-workload) | 100 changed per tick, the canonical change-detection workload |
| 6 | [Realistic multi-observer tick](#realistic-multi-observer-tick) | Three observers on disjoint components, st vs mt |
| 7 | [Predator / prey — the relations scenario](#predator--prey--the-relations-scenario) | First-class pairs vs naive `Component<Entity>`: the forward + reverse walk benchmark |
| 8 | [Does Valhalla help?](#does-valhalla-help-jdk-27-ea-jep-401-value-records) | JEP 401 EA sweep, flat-array opt-in A/B, the DCE caveat |
| 9 | [The "write-path tax"](#the-write-path-tax--why-japes-looks-slow-on-naked-writes) | Why Dominion/Artemis win the naked-write micros |
| 10 | [Speed-up matrix vs. Bevy](#speed-up-matrix-vs-bevy-lower-is-better-10-matches-bevy) | One-line relative perf summary across every benchmark |
| 11 | [Benchmark fairness audit](#benchmark-fairness-audit) | Cross-library check that every benchmark measures the same work, plus the one fix that came out of it |
| 12 | [Why so many ceremony-like params?](#why-so-many-ceremony-like-params) | Design rationale for `@Read C` / `@Write Mut<C>` / `Res<R>` |
| 13 | [Raw benchmark logs](#raw-benchmark-logs) | Commands to reproduce every number above |

See [README](README.md) for the quick-start and [TUTORIAL.md](TUTORIAL.md)
for a step-by-step walkthrough of the API.

## Methodology

All numbers below are µs per benchmark op, **lower is better**. Each
Java library is tested in its own idiomatic shape (see the per-library
benchmark source for what that means); Bevy is the Rust reference.
Same workload across every column.

Hardware: single workstation, single-threaded unless noted, JMH
`@Fork=2`, `@Warmup=3×2s`, `@Measurement=5×2s`. Stock numbers are
JDK 26 with `--enable-preview`; Valhalla numbers use an EA build of
JDK 27 with JEP 401 preview. Treat absolute values as a point-in-time
snapshot — relative ordering is what matters.

### What changed in this sweep

The numbers below were re-taken after a third-party code review (PR
[#1](https://github.com/zzuegg/japes/pull/1)) landed a batch of
correctness, thread-safety and benchmark-fairness fixes. The
user-visible effect on the tables:

- **`ChangeTracker.swapRemove` dirty-bit propagation** — real silent
  correctness bug. Entities that were dirty at the moment another
  entity was swap-removed became invisible to every
  `@Filter(Changed/Added)` observer. Fixed; all observer benchmarks
  now see every mutation. The measured numbers barely move because
  the benchmarks didn't combine despawns with mutations in the same
  archetype, but the bug was there.
- **`ArchetypeGraph.findMatchingCache` / `ComponentRegistry`
  `ConcurrentHashMap`** — fixes two real races under
  `MultiThreadedExecutor`. The `RealisticTick mt` row is now
  deterministic across runs; previous noise floor was partially due
  to map corruption under contention.
- **`Archetype.findOrCreateChunkIndex`** — O(n) linear scan replaced
  with an O(1) `openChunkIndex`. Helped `ParticleScenarioBenchmark`
  (which respawns ~100 entities per tick) drop from ~157 to
  **149 µs/op**.
- **`ChangeDetectionBenchmark.removedComponentsDrainAfterBulkDespawn`
  fairness** — the old measurement body included re-spawn + second
  tick, charging ~2× the work. Restructured to match the ZayES
  counterpart exactly. New number: **372 µs/op** for 10 k entities
  drained through the removal log.
- **`SparseDeltaBenchmark` javadoc** — adds an explicit fairness note
  that the japes benchmark body includes full `world.tick()` overhead
  (event swap, stage traversal, dirty-list pruning) while the
  Artemis/Dominion counterparts hand-roll a tight loop without any of
  it. Material at only 100 dirty entities. The japes number still
  drops because the PR's fixes reduce that tick overhead; the
  disclosure simply makes the comparison honest.
- **`NBodyBenchmark` Javadoc + `@TearDown`** — clarifies that this is
  Euler integration, not a pairwise gravitational N-body simulation
  (don't compare against external N-body benchmarks), and closes the
  FJP thread-pool leak the old benchmark had under `multiThreaded()`.

The PR also fixed an unrelated pre-existing issue it uncovered in
`ParticleScenarioBenchmark.RespawnSystem`: its `@Exclusive` system
took a `World` parameter directly, which `resolveServiceParam`
previously accepted by returning `null` (relying on the tick-time
executor to fill it in). With the PR's hardening
(`IllegalArgumentException` on unknown service param types) this
broke at world-build time. Fix: added `World.class` as a recognised
service parameter type — documents the existing contract and
restores the benchmark.

## Iteration micro-benchmark

Tight per-entity loop, nothing else going on. The read paths measure raw
query + iteration cost; `iterateWithWrite` writes back a mutated Position.

| benchmark              | entityCount | bevy | **japes** | zayes  | dominion | artemis |
|------------------------|------------:|-----:|----------:|-------:|---------:|--------:|
| iterateSingleComponent |        1000 | 0.24 |  **0.21** |   2.76 |     0.79 |    0.48 |
| iterateSingleComponent |       10000 | 2.11 |  **2.36** |   29.4 |     7.04 |    4.69 |
| iterateSingleComponent |      100000 | 21.0 |  **37.5** |    394 |     79.8 |     164 |
| iterateTwoComponents   |        1000 | 0.36 |  **0.46** |   3.55 |     1.31 |    1.17 |
| iterateTwoComponents   |       10000 | 3.35 |  **4.22** |   36.6 |     12.3 |    11.6 |
| iterateTwoComponents   |      100000 | 33.3 |  **67.3** |    507 |      128 |     226 |
| iterateWithWrite       |        1000 | 0.64 |  **5.79** |    180 |     2.27 |    1.82 |
| iterateWithWrite       |       10000 | 6.18 |  **57.4** |   1711 |     22.6 |    18.6 |
| iterateWithWrite       |      100000 | 62.5 |   **576** |  18205 |      233 |     332 |

> All japes read rows consume the loaded component through a JMH
> {@code Blackhole} (`ReadSystem.bh.consume(pos)`). An earlier revision
> had empty system bodies (`void iterate(@Read Position p) {}`) which
> let the JIT escape-analyse the loaded record and delete the whole
> iteration loop — especially under Valhalla, where the ["20–51× DCE
> artifact"](#does-valhalla-help-jdk-27-ea-jep-401-value-records) rows
> came from. These are the real numbers.

**Reading**, japes is the fastest JVM ECS in this comparison and lands within
10–30% of Bevy. The tier-1 GeneratedChunkProcessor is doing its job — each
chunk becomes a tight loop that loads raw component arrays once and dispatches
through `invokevirtual` with no MethodHandle or boxing.

**Writing**, japes pays for its `record` + `Mut<C>` write path: `set(new
Position(...))` allocates a new record per entity and updates the change
tracker. Dominion and Artemis use mutable POJO components and mutate fields
in place — no allocation, no tracking — so they come out 5–10× ahead on
the naive microbenchmark. See "The write-path tax" below for what the fair
comparison looks like once you want change detection.

## N-body integration

Full world tick with a single integrate system, `dt` supplied via `Res<T>`
(japes) or a world-level field (others).

| benchmark        | bodyCount | bevy | **japes** | zayes | dominion | artemis |
|------------------|----------:|-----:|----------:|------:|---------:|--------:|
| simulateOneTick  |      1000 | 0.88 |  **6.23** |  43.6 |     2.44 |    1.84 |
| simulateOneTick  |     10000 | 8.79 |  **62.5** |   440 |     24.1 |    19.2 |
| simulateTenTicks |      1000 | 8.92 |  **62.3** |   436 |     24.5 |    18.5 |
| simulateTenTicks |     10000 | 88.2 |   **625** |  4437 |      239 |     193 |

Same shape as the write-path iteration benchmark — the integrator allocates a
new `Position` record per body per tick. Dominion's and Artemis's in-place
floats mean the JIT can keep the whole loop in SIMD-ish registers.

## Particle scenario (move + damage + reap + respawn + stats)

This is the full end-to-end benchmark: 10 000 entities, ~1% turnover per tick,
five systems wired through the scheduler. This is what real game-loop code
looks like.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 22.4 |   **161** |  1859 |     68.3 |    98.2 |

> A [cross-library audit](#benchmark-fairness-audit) caught that japes's
> `StatsSystem` was reusing the previous-tick `alive` count instead of
> re-computing it — every other library scans 10 000 Lifetime entities
> per tick to count alive, japes was skipping the scan. Fixed in the
> current numbers above; japes now does the same work the others do
> (about +8% tick time vs the buggy version).

## Sparse delta (change-detection workload)

10 000 entities, 100 touched per tick. An observer reacts only to the entities
whose `Health` changed. This is the scenario change detection is *built for*:
per-tick work should scale with the dirty count, not the total entity count.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 4.01 |  **1.85** |  4.68 |     0.37 |    0.26 |

This is the most interesting row in the whole table, so it deserves the most
explanation.

**japes / Zay-ES / Bevy** implement the workload the way the API advertises:
the driver calls `world.setComponent(e, new Health(...))` (or Zay-ES's
`data.setComponent(...)`), which the library records in a per-tick dirty
tracker; the observer system is scheduled automatically and walks the library's
dirty view. The user writes zero bookkeeping code and the contract "every
mutation is observed" is enforced globally. At **1.85 µs/op japes is
the fastest of the library-change-detection group by a wide margin**
(Bevy is 4.01, Zay-ES is 4.68) after three rounds of profile-guided
fixes (cached `ArchetypeId.hashCode`, generation-keyed
`findMatching` cache, one-lookup `setComponent`, direct `Archetype`
reference on `EntityLocation`, array-indexed chunk lookups keyed by
`ComponentId.id()`, `setComponent` chunk consolidation) plus a
round of correctness / thread-safety fixes from the code-review PR
that shipped the concurrent `ArchetypeGraph` cache and plugged the
`ChangeTracker.swapRemove` dirty-bit loss. **japes is 2.17× faster
than Bevy** on this workload.

**Dominion / Artemis** have no change detection. The honest implementation is
the pattern a performance-conscious user would hand-write: mutate the
component's field in place *and* push the entity handle onto a
caller-maintained dirty buffer (`ArrayList<Entity>` for Dominion, `IntBag`
for Artemis, both default-constructed). The "observer" is just a second
loop over that buffer. See `DominionSparseDeltaBenchmark` /
`ArtemisSparseDeltaBenchmark` for the exact shape.

> Earlier revisions of these two benchmarks cheated by pre-sizing the dirty
> buffer to exactly the per-tick batch count (`new Entity[BATCH]`,
> `new IntBag(BATCH)`), which a real game-code author couldn't know in
> advance. They now use default-capacity growing containers; the numbers
> only moved by ~5 % because after the first tick the backing array has
> stabilised at its steady-state size and subsequent ticks pay
> amortised-constant append cost, but the shape of the code is now
> realistic.

That hand-written path turns out to be **still faster** on this
microbenchmark — Artemis is ~13× faster than japes, Dominion ~8×. The
reason is unsurprising once you unpack it:

- Dominion / Artemis do a `hp -= 1` int write and append an entity reference.
  No allocation, no tick-counter comparison, no atomic state update, no
  scheduler.
- japes / Bevy / Zay-ES pay for change-tracking bookkeeping at every
  `setComponent` call *and* at the observer side when walking the dirty view.
  In japes this is one indexed bitmap update + one dirty-slot list append
  per call, plus scheduler overhead on the observer side — and it amortises
  poorly across *just 100* entities with nothing else running.

**What you're trading for that ~13× microbenchmark gap.** The hand-rolled
pattern is only cheap because the microbenchmark has exactly one mutation
site, one observer, and one component. At real-codebase scale the costs
show up:

- **Correctness is a contract the compiler can't check.** Every place in
  your code that mutates `Health` has to remember to append to the dirty
  buffer. Add a new system a year later, forget the append, silently drop
  events. The library maintains the invariant globally — you cannot forget.
- **It doesn't compose across observers.** N observers × M mutation sites
  = N×M append calls you have to keep in sync. The library indexes this
  once, centrally. Adding a new observer in japes is one annotation; in
  manual land it's "find every mutation site and add another append."
- **Dedup costs perf or correctness.** Mutate the same entity twice in a
  tick and the naive list sees it twice. Either the observer does
  duplicate work, or you add a `Set<Entity>` on every append — which kills
  the perf advantage that made the manual path attractive in the first
  place. japes uses a per-tracker bitmap for O(1) dedup.
- **Frame-boundary coordination is your problem.** With multiple observers
  you have to agree on who clears the buffer and when. The library handles
  it at tick boundaries.
- **`Added`/`Removed` need their own plumbing.** japes ships
  `@Filter(Added.class)` and `RemovedComponents<T>` which work together
  with `Changed`. In manual land each is a separate buffer appended to
  from every `create`/`destroy`/`remove` site.
- **You lose filter composition.** `@Filter(Changed, Health) @Without(Dead)
  @With(Player)` is one annotation combination in japes that the scheduler
  resolves statically. In manual land you iterate the dirty list and
  re-check `!dead.has(e) && player.has(e)` per entry.
- **No free parallelism.** japes's scheduler runs disjoint observers in
  parallel for free from the declared access metadata — see
  `RealisticTickBenchmark`. A manual dirty list has no access metadata so
  the scheduler can't help; if you want multi-core you wire up
  `ExecutorService` yourself.
- **No tick history.** Bevy's change detection is tick-indexed — a system
  running every 3 ticks can ask "did this change since I last ran?"
  correctly. A bare dirty buffer is tick-local and forgets.
- **Debuggability.** Library change tracking knows the tick, the system
  that wrote, and the slot. A `Entity[]` has none of that.
- **The perf win shrinks with population.** At 100 dirty out of 10 000 the
  fixed library overhead dominates and manual wins ~20×. Push dirty past
  ~5 % of total and the constant-factor overhead amortises away — it
  becomes "iterate an array either way."

On **this microbenchmark** — one observer, one mutation site, ultra-sparse
dirty set — the hand-rolled path wins by a mile and that's what the
numbers show. On a **realistic multi-observer tick** the library path
wins by a mile in the other direction, because it does the work that no
single microbenchmark measures. The next section is that benchmark.

## Realistic multi-observer tick

`RealisticTickBenchmark` is the shape a real game-loop actually has:
10 000 entities with `{Position, Velocity, Health, Mana}`, 1% turnover
per tick (100 sparse mutations per component — different cursors so the
three slices don't overlap), and **three observers**, each reacting to
`@Filter(Changed)` of one component. Two executors: `st` (single-threaded
scheduler) and `mt` (japes `MultiThreadedExecutor` — ForkJoinPool-backed,
parallelises disjoint systems automatically).

Counterparts exist for every library in the comparison. Bevy and Zay-ES
use their native change-detection primitives (`Changed<T>` query filter
and `EntitySet.getChangedEntities()` respectively). Dominion and Artemis
have no built-in change detection, so their observer passes do full
iterations over every entity with the component (10 000 each) — the
"lazy user" path. The `mt` variant in Dominion/Artemis dispatches the
three observer passes to a fixed `ExecutorService`, exactly what japes
does for you from the declared system access metadata, except you have
to wire it up by hand.

**Results (100 dirty per component per tick, µs/op — lower is better):**

| library              | 10k µs/op | 100k µs/op | scaling |  cost model                       |
|----------------------|----------:|-----------:|--------:|-----------------------------------|
| **japes** st         |  **5.82** |   **7.76** |   1.33× | dirty-list skip (scales with K)   |
| zay-es               |      15.7 |       19.6 |   1.25× | dirty-list skip (scales with K)   |
| bevy (native Rust)   |      8.42 |       73.4 |   8.72× | full archetype scan (scales w/ N) |
| artemis st           |      24.8 |        282 |  11.4×  | full archetype scan (no CD)       |
| dominion st          |      45.1 |        392 |   8.70× | full archetype scan (no CD)       |

The libraries split into two cost-model camps, empirically:

- **Dirty-list skip** (japes, Zay-ES) — a per-archetype list of slot
  indices that were mutated since the last prune. `@Filter(Changed)` /
  `EntitySet.getChangedEntities()` walks only that list. Per-tick cost
  is O(K) where K is the dirty count, not O(N) where N is total
  entities. Scaling from 10k→100k costs ~33% more on japes (larger
  handle array for the driver's `getComponent` lookups) and ~25% more
  on Zay-ES.
- **Full-archetype scan** (Bevy, Dominion, Artemis) — observers
  iterate the full archetype and either tick-compare every entity
  (Bevy's `Changed<T>`) or walk every component regardless (Dominion
  `findEntitiesWith`, Artemis `IteratingSystem` with no filter).
  Per-tick cost is O(N) because that's the algorithmic shape.
  Scaling from 10k→100k costs ~8–11× more.

**At 10k entities japes beats Bevy by 1.45×.** The gap looks modest
because 10k is small enough that Bevy's tight cache-friendly tick
scan is only paying ~2 µs of pure scan cost. **At 100k entities the
same workload is a 9.45× gap** — Bevy pays ~65 µs extra to scan
90 000 more tick words that japes never touches. The cost model
predicted this exactly (see the
[scaling analysis](#how-the-two-cost-models-separate) below).

Worth calling out: **Zay-ES beats Bevy at 100k** (19.6 vs 73.4). Zay-ES
has higher per-mutation overhead than japes (more allocations in the
driver side, per-set `applyChanges()` calls) but its
`EntitySet.getChangedEntities()` is a dirty-list skip, so it scales the
same shape as japes. The two dirty-list libraries stay in the same
cost bucket at any entity count; the three scan libraries scale out
of it past ~50k.

### How the two cost models separate

The per-additional-entity cost at the 10k → 100k step tells the whole
story:

| library | Δ µs for Δ 90k entities | per-entity overhead |
|---|---:|---:|
| **japes st**        |   +1.94 |   22 ns / entity |
| zay-es              |   +3.89 |   43 ns / entity |
| bevy                |  +65.0  |  722 ns / entity |
| artemis st          |  +257   | 2 860 ns / entity |
| dominion st         |  +347   | 3 860 ns / entity |

japes's ~22 ns/entity is driver-side cost (the handle list grows, the
archetype's chunk list grows, `getComponent` walks slightly further).
The observer side is ~flat because the dirty list is still 300 slots.

Bevy's ~722 ns/entity breaks down as 3 observers × ~240 ns = each
observer does roughly one tick-word load + compare + branch per entity,
which at ~0.24 ns/check × 100k entities × 3 observers ≈ 72 µs. Matches.

Dominion/Artemis pay more per entity because their full scans happen
in the user-facing benchmark driver too (each observer calls
`findEntitiesWith` / `IteratingSystem.process` which rebuilds its
iterator state), not just inside a tight Bevy-style Changed<T> filter.

**Why Bevy doesn't ship a dirty-slot list for Changed<T>** (since the
question inevitably comes up): it's a deliberate API trade-off, not a
missed optimisation. Tick-per-slot is cheaper *per mutation* (one
store, no dedup, no append), which matters for Bevy's target
workload — dense simulation where most components get touched every
tick and the dirty list would contain most of the world. The catch
with the dirty-list is opposite: it wins on sparse delta, loses on
dense. japes pays ~5-10 ns extra per mutation for the dirty-list
maintenance, which is invisible at 300 mutations/tick (total ~3 µs)
but would start to hurt at millions of mutations/tick. Run japes on
`iterateWithWrite` (every entity touched every tick, K = N) and Bevy
wins by ~9× — the opposite direction, same cost model.

### DCE safety

Before trusting these numbers, the obvious question is "are we hitting
a dead-code-elimination trap anywhere?" The Bevy observer body writes
into `ResMut<RtStats>` which is never read outside the benchmark
closure — if the compiler can prove the writes have no observable
effect, it's allowed to delete the observer bodies entirely.

Explicitly checked:

- **japes**: the `@Benchmark` body calls `bh.consume(stats.sumX)` /
  `sumHp` / `sumMana` at the end of every tick. JMH's `Blackhole.consume`
  is opaque to the JIT, so the accumulation chain is preserved.
- **Bevy**: the `b.iter(||)` closure now calls
  `world.resource::<RtStats>()` + `std::hint::black_box(stats.sum_x)` /
  `sum_hp` / `sum_mana` after `schedule.run`. `black_box` is rustc's
  equivalent of `Blackhole.consume` — the compiler must materialise
  the read.

Re-ran Bevy after adding the `black_box` guards: result 8.42 µs at
10 k (was 8.41 µs without the guard). Delta is pure measurement noise,
which means **DCE wasn't happening even without the guard** — the
cross-crate call chain `schedule.run → system fn pointer → observer
body` already defeats rustc's DCE at the default `cargo bench` opt
level (`opt-level = 3`, no LTO). The guard is there as insurance
for future readers, not because it was needed to get the number.

### Same-work audit — driver parity

Each library's driver does 300 sparse mutations per tick via three
rotating cursors. The operation shapes differ slightly:

| library    | operation per mutation                                  | per-mutation alloc |
|------------|---------------------------------------------------------|---------------------|
| japes      | `world.setComponent(e, new Position(...))` (new record) | **allocates**       |
| zay-es     | `data.setComponent(id, new Position(...))`              | **allocates**       |
| bevy       | `world.get_mut::<Position>(e).x += 1.0`                 | in-place            |
| dominion   | `e.get(Position.class).x += 1` (mutable POJO)           | in-place            |
| artemis    | `pm.get(e).x += 1` (mutable Component subclass)         | in-place            |

This is an asymmetry on the driver side: japes and Zay-ES allocate
300 record instances per tick that Bevy / Dominion / Artemis don't.
Direction of the asymmetry: **favours Bevy / Dominion / Artemis**.
japes is paying extra allocation cost its comparison-peers aren't —
and **still winning**. If we fixed the asymmetry (either by making
japes's driver mutate in place somehow, or by making Bevy's driver
allocate new records), the 9.45× gap at 100 k would widen further,
not shrink.

### Code comparison (single-threaded path)

The japes observer is **11 lines** including the class declaration:

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

The Dominion/Artemis version has to know a priori that the three
observers don't conflict, know to dispatch them in parallel, own the
thread-pool lifecycle, and do all of this over again every time an
observer is added or removed. japes knows all of that from
`@Read`/`@Write`/`@Filter` annotations and the scheduler's DAG builder.

## Predator / prey — the relations scenario

`PredatorPreyBenchmark` is the reference workload for the first-class
relations feature (`@Relation` record + `RelationStore` +
`@Pair(T.class)` / `@ForEachPair(T.class)` dispatch). It's built to
stress every relation hot path the design doc calls out:

- `Commands.setRelation` — idle predators acquire a new hunt target.
- Per-predator forward walk that reads each hunt pair's target
  position — the "pursuit" system.
- Per-prey reverse-index walk counting "how many predators are
  hunting me" (this is the cell the whole feature exists for).
- `world.despawn(prey) → RELEASE_TARGET` cleanup — the reverse index
  drops the matching forward entries automatically; no user bookkeeping.
- `RemovedRelations<Hunting>` — drains the removal log with a
  per-subscriber watermark.

Per-tick steady state: one random prey respawns per catch, so entity
counts and per-tick work stay stable across iterations. Counters are
blackholed at the end of each tick to defeat DCE on the observer
accumulators.

### Two dispatch models: `@Pair` and `@ForEachPair`

japes ships two alternative ways to run a relation-driven system; the
benchmark exercises both and the DEEP_DIVE results grid shows both
columns side-by-side.

**`@Pair(Hunting.class)`** — *set-oriented*. The system is called
**once per entity** that carries at least one pair. The user body walks
the entity's pairs with `PairReader.fromSource(self)` or
`PairReader.withTarget(self)`:

```java
@System
@Pair(Hunting.class)
void pursuit(@Read Position p, Entity self, PairReader<Hunting> r, @Write Mut<Velocity> v) {
    for (var pair : r.fromSource(self)) { /* steer toward pair.target() */ }
}
```

This shape is the right default when a system needs to see *the whole
set* of pairs attached to one entity (e.g. "sum the focus values over
all my hunt targets", "pick the closest target of any type"). It's
also what `@Pair(role = TARGET)` + `withTarget` is for — reverse-side
systems that want "every predator hunting me" in one call.

**`@ForEachPair(Hunting.class)`** — *tuple-oriented*. The system is
called **once per live pair**. No walker, no `fromSource` call: the
parameters bind directly to source-side components (default),
target-side components (opt-in with `@FromTarget`), the relation
payload, and source/target entity ids:

```java
@System
@ForEachPair(Hunting.class)
void pursuit(
        @Read Position sourcePos,             // source (predator)
        @Write Mut<Velocity> sourceVel,       // source, writable
        @FromTarget @Read Position targetPos, // target (prey)
        Hunting hunting,                      // payload, type-matched
        ResMut<Counters> counters             // normal service param
) {
    float dx = targetPos.x() - sourcePos.x();
    float dy = targetPos.y() - sourcePos.y();
    float mag = (float) Math.sqrt(dx*dx + dy*dy);
    if (mag > 1e-4f) sourceVel.set(new Velocity(dx / mag * 0.1f, dy / mag * 0.1f));
    counters.get().pursuitCalls++;
}
```

This shape is right when the body only cares about one pair at a
time — steering, distance checks, damage propagation, constraint
solves. It's also the shape the scheduler can make *fast*, because
the tier-1 bytecode generator can walk the store's forward index
directly and bind all source/target/payload arguments in straight-line
bytecode, with no `PairReader.fromSource(…)` call, no `Pair<T>` record
allocation, and no `world.getComponent` lookup for target-side reads.

**Decision table.** Both APIs are first-class; they just solve
different problems.

| You want to… | Use | Why |
|---|---|---|
| See the *set* of pairs per source entity (sum, max, pick-best) | `@Pair(T.class)` | The reader gives you `Iterable<Pair<T>>` on the entity; `@ForEachPair` would force you to reassemble the set. |
| See the set of pairs per target entity (`withTarget`) | `@Pair(T.class)` w/ `role = TARGET` | Same reason, reverse direction. |
| Do one thing per pair, in isolation | `@ForEachPair(T.class)` | Tier-1 bytecode-gen path; ~25% faster than `@Pair` on this benchmark, no allocations per pair. |
| Mix set-oriented reads with per-pair updates | start with `@Pair`, move the per-pair body into a separate `@ForEachPair` system | Two systems beat one in the scheduler: the set-oriented read can run in parallel with a disjoint-access pair walker. |

### Three comparison points

Bevy 0.15 has no generic relations primitive, so the comparison is a
four-way (two japes shapes × two Bevy shapes):

**japes @Pair** / **japes @ForEachPair** — the two first-class APIs
above. Both use the same `@Relation record Hunting` record, the same
`Commands.setRelation` acquisition path, the same `RELEASE_TARGET`
despawn cleanup, the same `RemovedRelations<Hunting>` log drain. They
differ only in the pursuit system's dispatch model.

**Bevy "naive"** does what a first-pass Bevy user actually writes: store
the target `Entity` in a component field, then manually scan every
predator when a prey wants to know who's hunting it. O(predators × prey)
per tick in the awareness system. See `pp_awareness` in
[`benchmark/bevy-benchmark/benches/ecs_benchmark.rs`](benchmark/bevy-benchmark/benches/ecs_benchmark.rs).

**Bevy "optimized"** hand-rolls exactly what the relation store
maintains for japes automatically — a `HuntedBy(Vec<Entity>)` component
on every prey, with two extra writes per `Hunting` acquisition to keep
the reverse index consistent. The awareness system then reads
`hunted_by.0.len()` in O(1) per prey. See `pp_opt_acquire_hunt` /
`pp_opt_awareness` in the same file. This is the "what if the user
ignored the library and wrote it by hand" upper bound.

### Results

Same 9-cell parameter grid. Lower is better; bold marks each row's
winner; the **japes @ForEachPair** column is the library's
recommended shape for per-pair work.

| predators | prey | japes `@Pair` | **japes `@ForEachPair`** | Bevy naive | **Bevy optimized** |
|---:|---:|---:|---:|---:|---:|
| 100  |  500 |   8.5 µs |   **6.3 µs** |   14.1 µs |  **1.97 µs** |
| 100  | 2000 |  16.2 µs |  **14.0 µs** |   51.8 µs |  **3.99 µs** |
| 100  | 5000 |  28.0 µs |  **26.4 µs** |  126.3 µs |  **7.30 µs** |
| 500  |  500 |  31.4 µs |  **22.1 µs** |   67.6 µs |  **7.01 µs** |
| 500  | 2000 |  43.3 µs |  **31.7 µs** |  261.9 µs | **11.19 µs** |
| 500  | 5000 |  69.2 µs |  **55.9 µs** |  632.1 µs | **19.13 µs** |
| 1000 |  500 |  62.6 µs |  **43.1 µs** |  128.8 µs | **13.15 µs** |
| 1000 | 2000 |  83.1 µs |  **55.3 µs** |  476.4 µs | **19.68 µs** |
| 1000 | 5000 | 118.7 µs |  **88.4 µs** |   1198 µs | **32.73 µs** |

The honest takeaways are layered.

**japes `@ForEachPair` beats `@Pair` at every cell** by 9–29%.
That's the tier-1 bytecode generator paying off: the generated
`run(long tick)` method walks the `RelationStore.forwardKeysArray()`
directly, caches the source-side storages per archetype transition
(so per-source reads are just one storage.get + slot-index load —
no `componentStorage()` lookup, no chunk refetch), hoists the
`Mut<T>` references to locals once per run, and invokes the user
method via plain `invokevirtual` with every component argument in a
local. No `PairReader.fromSource(…)` allocation, no `Pair<T>` record
per pair, no `world.getComponent` call for the target read, no
`SystemInvoker.invoke` reflection. See
`GeneratedPairIterationProcessor` for the full emission logic.

**japes beats naive Bevy at every cell, up to 12.9× at 1000 × 5000**.
The earlier `@Pair`-vs-naive crossover story is gone: even the
set-oriented `@Pair` column wins every cell now, and `@ForEachPair`
extends the lead further. The reverse-index advantage that was
originally masked by constant-factor overhead is now visible from
the smallest workload up.

**japes vs optimized Bevy** — the ratio on the `@ForEachPair` column
sits at 2.8–3.7× across every cell (it was 11–28× on the first
PR-landing numbers). That remaining gap is structurally out of reach
without giving up features: the japes scheduler pays for per-pair
change tracking (`PairChangeTracker`), deferred `Commands`, archetype
marker maintenance on first/last pair, and `RemovedRelations<T>`
log drain. The hand-rolled Rust version skips every one of those.
Closing the gap further means cutting features the library exists
to provide.

**The optimization journey.** The 500 × 2000 cell started at
**167 µs/op** at PR-landing (reflective tier-3 dispatch, `HashMap`
forward/reverse indices, `ArrayList<Pair<T>>` per `fromSource` call,
`world.getComponent` probes per target read). Successive rounds
replaced those with: a primitive-keyed `Long2ObjectOpenMap`, flat
`TargetSlice` / `SourceSlice` inner maps, per-archetype
`ComponentReader` caches on the pair reader, `@Pair(role = TARGET)`
narrowing, tier-1 `@Pair` bytecode generation, the tier-1
`@ForEachPair` path documented below, per-archetype caching of
*every* source-side storage ref (not just write storages) so
cache-hit transitions skip the chunk refetch and the per-source
`componentStorage()` lookup entirely, a raw-long
`forEachPairLong` / `ComponentReader.getById(long)` bulk-scan path
that avoids per-pair `Entity` allocation in cleanup systems, a
tier-1 bytecode-generated path for service-only `@Exclusive`
systems, and a primitive `LongArrayList` utility replacing
`ArrayList<Long>` in the catch buffer. End-to-end the cell now
runs at **31.7 µs/op** — a **5.27× speedup** with the API surface
staying stable the whole time.

### What the four columns actually tell you

1. **If you need a reverse index, you need a reverse index.** The
   naive Bevy story falls apart hard by 1000 × 5000 (1.2 ms/tick —
   not shippable), and the relations feature exists precisely because
   this shape is common in game code.
2. **`@ForEachPair` is the path to pick when the system only needs
   one pair at a time.** It's tier-1 generated, it walks flat arrays,
   it doesn't allocate. `@Pair` stays for set-oriented work where the
   system genuinely needs to see all the pairs attached to one
   entity.
3. **Use relations for correctness-by-default and now for speed too.**
   The point of automatic reverse-index maintenance is that you
   cannot forget to update it. You cannot forget to drop pairs on
   despawn. You cannot accidentally iterate while mutating. All of
   that is the library's job — and with tier-1 `@ForEachPair` the
   library's job is now within ~3× of a hand-rolled Rust reverse
   index on the same workload.

### How tier-1 `@ForEachPair` generation works

The generator (`GeneratedPairIterationProcessor`, ~790 lines)
emits a hidden class per system using Java's `java.lang.classfile`
API, with a `run(long tick)` method that:

1. Loads `store.forwardKeysArray()` / `forwardValuesArray()` into
   locals once at the start — these are the raw backing arrays of the
   primitive-keyed forward map, exposed by `RelationStore` exactly so
   tier-1 can skip `Long2ObjectOpenMap.forEach` and walk the table
   slot-by-slot.
2. Outer loop: skip null slots, unpack the source `Entity`, look up
   the source's `EntityLocation`.
3. Per-source archetype cache: compare `(archetype, chunkIdx)` to the
   previous source's cache; on a miss, re-resolve every source-side
   component storage and `ChangeTracker` once, store them in locals.
4. Load every `@Read` source component value into a local.
5. For every `@Write Mut<T>` slot, `setContext` + `resetValue` the
   reusable `Mut<T>` once per source (*not* once per pair).
6. Inner loop: walk `TargetSlice.targetIdsArray()` /
   `valuesArray()` directly — these are also flat `long[]` and
   `Object[]` backing arrays, again exposed as public by the slice
   so tier-1 can skip the `Iterator`.
7. Per-pair: look up the target's `EntityLocation`, hit a
   target-side archetype cache, load every `@FromTarget @Read` value.
8. Direct `invokevirtual` to the user method — no `MethodHandle`, no
   `SystemInvoker.invoke`, no reflection. Every argument is already
   in a local.
9. After the inner loop: flush every `@Write Mut` back to the
   source's storage.

Unsupported shapes (≤4 source-reads, ≤2 source-writes, ≤2
target-reads, instance method) fall back to `PairIterationProcessor`
(reflective tier-2), which does the same thing with
`MethodHandle.invokeExact` and per-pair `world.getComponent` probes.
The benchmark shape (1 source-read + 1 source-write + 1 target-read
+ 1 payload + 1 service) is comfortably inside the fast path.

Most of the per-cell speedup in the journey table above comes from
moving work *out* of the inner loop: archetype caching, per-source
`Mut` setup, hoisted storage arrays. The innermost body is now a
component-load, a component-load, an `invokevirtual`, a
store-back — basically the same shape as a tier-1 per-entity chunk
loop, with the addition of the outer source-iteration scaffolding.

### Remaining v2 levers

- **Tier-1 for `@Exclusive` cleanup systems** — `resolveCatches` and
  `respawnPrey` still go through the reflective exclusive path.
  Worth ~3 samples × ~300 ns per tick; not load-bearing but free.
- **Chunk-level source grouping** — when multiple consecutive outer
  slots land in the same source archetype, the current generator
  still redoes the archetype cache probe. Sorting the forward keys
  by archetype at set time would turn the cache into a run-length
  walk.
- **Caller-site change-tracker hoisting** — generator currently
  re-reads `ChangeTracker.dirtySet()` once per source cache miss;
  could be hoisted to once per run.
- **`@ForEachPair(role = TARGET)`** — target-side iteration shape
  (for when you want "one call per pair, but you're writing the
  target, not the source"). v2.

None of these change the API surface. Filed for v2.

### Reproducing

```bash
# japes (Java) — both dispatch shapes on the same workload
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "PredatorPreyBenchmark" \
  -p predatorCount=100,500,1000 -p preyCount=500,2000,5000
java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "PredatorPreyForEachPairBenchmark" \
  -p predatorCount=100,500,1000 -p preyCount=500,2000,5000

# Bevy (Rust) — both naive and optimized variants
cd benchmark/bevy-benchmark
cargo bench -- predator_prey/naive_tick
cargo bench -- predator_prey/optimized_tick
```

## Does Valhalla help? (JDK 27 EA, JEP 401 value records)

Every component in japes is a `record`, which means the backing
component storage is a reference array — reading a `Position` is a
pointer-chase and writing one allocates a fresh heap record. Valhalla's
JEP 401 promises flat layout for `value record`s: the same backing array
becomes a flat `float[]` and loads become plain array indexing.

The `ecs-benchmark-valhalla` module ports every japes benchmark with
`value record` components and runs them against a Valhalla EA build
(`openjdk 27-jep401ea3`). The two runs use the same Java source, same
`--enable-preview`, same JMH settings, same tier-1 generator — only the
component declaration (`record` vs `value record`) and the runtime JVM
differ.

**Results (µs/op, lower is better — `japes-v` is japes on the Valhalla EA JVM with value records):**

| benchmark                     |          case | **japes** | **japes-v** | Δ               |
|-------------------------------|--------------:|----------:|------------:|----------------:|
| iterateSingleComponent        |           10k |      2.36 |        1.06 | **2.23×** real  |
| iterateSingleComponent        |          100k |      37.5 |        9.31 | **4.03×** real  |
| iterateTwoComponents          |           10k |      4.22 |        1.85 | **2.28×** real  |
| iterateTwoComponents          |          100k |      67.3 |        20.0 | **3.37×** real  |
| iterateWithWrite              |           10k |      57.4 |        53.2 | **1.08×** real  |
| iterateWithWrite              |          100k |       576 |         536 | **1.07×** real  |
| NBody simulateOneTick         |            1k |      6.23 |        5.84 | **1.07×** real  |
| NBody simulateOneTick         |           10k |      62.5 |        57.3 | **1.09×** real  |
| NBody simulateTenTicks        |           10k |       625 |         577 | **1.08×** real  |
| ParticleScenario tick         |           10k |       161 |         180 | 0.89× slower    |
| SparseDelta tick              |           10k |      1.85 |        1.96 | 0.94× slower    |
| RealisticTick tick            |     10k / st  |      5.76 |        11.9 | 0.48× slower    |
| RealisticTick tick            |     10k / mt  |      10.3 |        17.8 | 0.58× slower    |

**The reads tell the real story.** An earlier revision of the japes
iteration benchmarks had empty system bodies (`void iterate(@Read
Position p) {}`), which let the escape analyser prove the load was
unused and delete the whole iteration — previously reported "20–80×"
speedups were measuring nothing. With a JMH `Blackhole` consumer on
every read system (`bh.consume(pos)`), the JIT has to actually touch
each element, and the real Valhalla number comes out: **2.2–4.0×
faster on reads** once you scale past 10 k entities. That's the JEP
401 flat-array layout paying off exactly where it should — sequential
dense iteration over a primitive-backed storage.

**What the table actually shows:**

- **Reads — big and real.** At 100 k entities Valhalla finishes
  `iterateSingleComponent` in ~25 % of stock japes's time (4.03×),
  and `iterateTwoComponents` in ~30 % (3.37×). The flat backing layout
  turns every read into a direct aaload against a primitive region
  instead of a pointer chase + field load on a heap record, and the
  tier-1 generator's tight chunk loop inlines cleanly on top of it.
  This is the biggest cross-JVM number in the whole README.
- **Writes — modest.** iterateWithWrite and NBody gain **~7–10 %**
  under Valhalla. Writes still allocate `new Position(...)` (either a
  flat value or a heap record depending on the JVM), and the store
  into the backing array has the same cost either way, so there's
  less for Valhalla to optimise. The win is real but small.
- **Scenarios — narrowing regression.** `ParticleScenario` is 12 %
  slower under Valhalla (was 14 %), `RealisticTick st` 52 % slower
  (was 74 %), `RealisticTick mt` 42 % slower. `SparseDelta` has
  tightened to 6 % slower, down from the 40 % gap seen in earlier
  rounds — the PR's `ChangeTracker.swapRemove` fix and the concurrent
  `ArchetypeGraph` cache both trim Valhalla overhead disproportionately,
  because the EA JIT was amplifying the pre-fix hot paths. GC profiling
  still shows Valhalla allocating **~2×** more per op on the scenario
  benchmarks than stock japes; the residual regression comes from
  value records crossing the erased `Record` parameter of
  `World.setComponent`, which forces the JVM to box the value into a
  heap wrapper even though the storage layer is value-aware.

**Does an explicit flat-array opt-in fix it?** JEP 401 EA exposes an
experimental flat-array allocator at `jdk.internal.value.ValueClass.
newNullRestrictedNonAtomicArray(Class, int, Object)` plus a
class-level `@jdk.internal.vm.annotation.LooselyConsistentValue`
opt-in. I wired both into `DefaultComponentStorage` and the Valhalla
benchmark records (see `DefaultComponentStorage` static initialiser —
it's gated behind `-Dzzuegg.ecs.useFlatStorage=true` so it's off by
default). The resulting backing array genuinely is flat
(`ValueClass.isFlatArray(arr) == true`, verified in-process), but in
an A/B comparison on the same JVM it was **measurably worse**:

  | benchmark                | flat OFF | flat ON | Δ              |
  |--------------------------|---------:|--------:|---------------:|
  | iterateTwoComponents 10k |   1.79   |   6.18  | **3.4× slower**|
  | iterateTwoComponents 100k|   18.4   |   64.3  | **3.5× slower**|
  | RealisticTick st         |   14.0   |   16.3  | 16% slower     |
  | SparseDelta              |   2.57   |   2.49  | noise          |

  The EA JIT clearly hasn't yet emitted optimised get/set code for flat
  null-restricted arrays — the flat layout is in place but accessing
  it goes through a slower path than the reference-array fallback that
  the JIT has had longer to optimise. All the real Valhalla wins above
  (the 2–4× reads and the ~10% NBody numbers) come from the
  *reference-array* path, where the JIT scalar-replaces well and the
  value-record layout wins through escape analysis instead of through
  an explicit flat backing. The opt-in is there and correct; it'll
  become the right default once the Valhalla JIT's flat-array path
  catches up with its reference-array path.
- **SparseDelta** is within noise. The bottleneck is change-tracker
  bookkeeping, not component reads, so there's nothing for Valhalla
  to flatten.

**Honest takeaway.** Under JEP 401 EA, Valhalla hands japes a real
~**3×** speedup on read-heavy iteration (the biggest single gain in
this README) and ~**10%** on dense integration loops, and is still a
net *regression* on change-detection scenario benchmarks that exercise
`setComponent` heavily. Counter-intuitively, the explicit
flat-array opt-in (`newNullRestrictedNonAtomicArray` +
`@LooselyConsistentValue`) makes things *worse* today because the EA
JIT hasn't optimised the flat-access path yet — the real wins come
from the reference-array fallback where the JIT can scalar-replace
through escape analysis. Both code paths are implemented and
A/B-tested in the repo; the flat opt-in will become the right default
once the Valhalla JIT catches up. "Just set the JVM to Valhalla" is
not a free performance switch today but the read-side numbers are
*very* compelling, and the trajectory is clearly favourable.

### Predator / prey under Valhalla

The relations scenario (`PredatorPreyForEachPairBenchmarkValhalla`,
in the `ecs-benchmark-valhalla` module) ports the benchmark to
`@LooselyConsistentValue value record Position`, `Velocity`,
`Predator`, `Prey`. The `Hunting` relation payload stays a plain
`record` because it lives in `TargetSlice.values`, an `Object[]`
inside the relation store, not in a flat `ComponentStorage` — so
there is nothing to flatten on the payload side. Same scheduler,
same `@ForEachPair` dispatch, same tier-1 generator, same grid
parameters as the stock benchmark.

| predators × prey | Stock JDK 26 | Valhalla EA (value records, ref arrays) | Valhalla EA (value records, **flat arrays**) |
|---|---:|---:|---:|
| 100 × 500  |  **6.3 µs** |   6.7 µs (+6 %)  |  18.6 µs (+195 %) |
| 100 × 2000 | **14.0 µs** |  14.3 µs (+2 %)  |  25.8 µs ( +85 %) |
| 100 × 5000 | **26.4 µs** |  26.7 µs (+1 %)  |  37.7 µs ( +43 %) |
| 500 × 500  | **22.1 µs** |  25.0 µs (+13 %) |  80.9 µs (+266 %) |
| 500 × 2000 | **31.7 µs** |  33.9 µs (+7 %)  |  90.0 µs (+184 %) |
| 500 × 5000 | **55.9 µs** |  57.9 µs (+4 %)  | 108.8 µs ( +95 %) |
| 1000 × 500 | **43.1 µs** |  48.9 µs (+13 %) | 161.0 µs (+274 %) |
| 1000 × 2000| **55.3 µs** |  61.1 µs (+10 %) | 169.3 µs (+206 %) |
| 1000 × 5000| **88.4 µs** |  93.1 µs (+5 %)  | 195.7 µs (+121 %) |

Two things jump out.

**Value-record + reference-array storage is essentially a tie with
stock.** Declaring `Position` / `Velocity` as `value record` with
`@LooselyConsistentValue` while keeping the backing storage a
plain reference array costs between 0 and 13 % across every cell —
well inside the JMH error bars at most cells. For this workload
the value-record declaration alone gives no measurable win:
pursuit's inner body is so tight (two component reads, one write,
one payload read, one `invokevirtual`) that the tier-1 generator
already lets the JIT scalar-replace short-lived `Position` /
`Velocity` instances on both JVMs. Nothing left for value semantics
to recover.

**Flat-array storage is a 1.4×–3.7× regression** at every grid
cell, matching the same warning already documented on the
iteration micro-benchmarks. The absolute overhead scales with
predator count, not with prey count:

| predators | 500 prey Δ | 2000 prey Δ | 5000 prey Δ |
|---:|---:|---:|---:|
|  100 | +12.3 µs | +11.8 µs | +11.3 µs |
|  500 | +58.8 µs | +58.0 µs | +52.9 µs |
| 1000 |+117.9 µs |+114.0 µs |+107.3 µs |

That shape fingerprints the overhead as per-pair component access:
`~predators × 3 pairs × (2 reads + 1 write)` of flat-array I/O per
tick, roughly **+13 ns per access** above the reference-array
fast path. The unoptimised EA JIT code for flat get/set dominates
everything the tier-1 pair runner was built to eliminate.

The upshot is the same conclusion the earlier sections reach:
value records themselves cost nothing, the value-record layout
hasn't yet unlocked a new win on top of the existing tier-1
generator for short-lived component shapes, and flat-array
storage remains gated behind `-Dzzuegg.ecs.useFlatStorage=true`
until the Valhalla JIT matures. Filed as a re-benchmark target
for every future EA drop.

## The "write-path tax" — why japes looks slow on naked writes

The `iterateWithWrite`, `NBody` and sparse-delta rows all show japes paying a
measurable cost against Dominion/Artemis on the same workload. That is not
the tier-1 generator being slow — it is an *API choice* being measured:

- **japes**'s idiomatic write path is `@Write Mut<Position>` + `record Position`,
  so `p.set(new Position(...))` allocates and records a change so
  `@Filter(Changed.class)` observers can react automatically.
- **Dominion / Artemis** components are mutable POJO classes; `p.x += v.dx`
  does no allocation and leaves no audit trail — which also means if you
  want `@Filter(Changed)` or `RemovedComponents` semantics, you have to
  open-code them yourself at every mutation site.

So: if you want raw in-place writes with no change tracking and are willing
to hand-write dirty-list plumbing at every mutation site, use Dominion or
Artemis — they'll be faster on every *micro*benchmark in this README. If you
want immutable components with observer systems that the library wires up
for you, use japes or Zay-ES — and on the multi-observer realistic tick
japes is the cheapest configuration in absolute CPU cost.

## Speed-up matrix vs. Bevy (lower is better, `1.0×` = matches Bevy)

| benchmark                   |         case |  **japes** | **japes-v** | zayes |  dominion | artemis |
|-----------------------------|-------------:|-----------:|------------:|------:|----------:|--------:|
| iterateSingleComponent      |          10k |   **1.1×** |  **0.50×**  | 13.9× |     3.3×  |   2.2×  |
| iterateTwoComponents        |          10k |   **1.3×** |  **0.55×**  | 10.9× |     3.7×  |   3.5×  |
| iterateWithWrite            |          10k |   **9.3×** |    **8.6×** |  277× |     3.7×  |   3.0×  |
| NBody simulateOneTick       |          10k |   **7.1×** |    **6.5×** |   50× |     2.7×  |   2.2×  |
| ParticleScenario tick       |          10k |   **7.2×** |        8.0× |   83× |     3.0×  |   4.4×  |
| SparseDelta tick            |          10k |  **0.46×** |   **0.49×** |  1.2× |  **0.09×**| **0.06×**|

> In rows where the number is below 1.0×, japes is *faster* than Bevy
> on the same workload. Stock japes now runs `SparseDelta` at **1.85 µs
> vs Bevy's 4.01** — 2.17× faster on the library change-detection
> path. Under Valhalla with value records, japes's iteration reads at
> 10 k land at ~0.5× Bevy. Dominion and Artemis sit even further below
> on `SparseDelta` because they use manually-maintained dirty lists
> (one field write, one array append, no tick counter, no scheduler) —
> faster on the micro, but the correctness burden is on the user.

## Benchmark fairness audit

I manually cross-referenced every cross-library benchmark against
every other library's implementation to check that all of them are
actually doing the same work. Results:

**What's identical across all five libraries ✓**

- **Iteration reads** (`iterateSingleComponent`, `iterateTwoComponents`):
  Bevy's `black_box(pos)`, japes/Dominion/Zay-ES's `bh.consume(pos)`,
  Artemis's static-field Blackhole pattern all prevent JIT
  dead-code-elimination of the loaded component. Every library
  iterates every matching entity and hands the value to an opaque
  sink. ✓
- **NBody body generation** (angles around a circle, unit velocities,
  same start state). ✓
- **`ChangeDetectionBenchmark.removedComponentsDrainAfterBulkDespawn`**
  after PR #1's fairness restructure — `@Setup(Trial)` world creation,
  `@Setup(Invocation)` entity re-seeding, `@Benchmark` body only
  despawns + ticks. Matches the Zay-ES counterpart exactly. ✓

**What differs by *design* (and is documented elsewhere)**

- **`iterateWithWrite` / `NBody` / `SparseDelta` driver writes**:
  Bevy/Dominion/Artemis do in-place primitive field writes
  (`pos.x += vel.dx`) while japes/Zay-ES allocate a new component
  record per mutation (`pos.set(new Position(...))`). The two-camp
  split reflects the libraries' API philosophies — immutable records
  + change tracking vs mutable POJOs. This is the
  ["write-path tax"](#the-write-path-tax--why-japes-looks-slow-on-naked-writes)
  already documented in the section of that name. Direction: favours
  Bevy/Dominion/Artemis; japes and Zay-ES numbers are inflated by
  the allocation cost on these benchmarks. Keeping it, because it
  measures the real user-visible cost.

**What was a bug (now fixed)**

- **`ParticleScenarioBenchmark.StatsSystem` alive count**: japes was
  the *only* library not computing `alive` per tick — it reused the
  previous tick's value via `stats.set(new Stats(..., cur.alive()))`.
  Bevy iterates `Query<&Lifetime>`, Dominion does
  `findEntitiesWith(Lifetime.class).forEach`, Artemis uses a
  `StatsSystem extends IteratingSystem` with `Aspect.all(Lifetime)`,
  Zay-ES drains an `aliveSet` of Lifetime entities — all four other
  libraries run a full 10 k-entity scan per tick that japes was
  skipping. Fixed by splitting `StatsSystem` into `countAlive(@Read
  Lifetime l)` for the per-entity accumulation and `drain(
  RemovedComponents<Health>, ResMut<Stats>)` in the `PostUpdate`
  stage for the write-and-reset. Instance-field accumulator shared
  across both methods; reset-at-end-of-tick pattern.
  - **Impact**: japes ParticleScenario went from 149 → **161 µs/op**
    (+8 %). japes-v went from 169 → **180 µs/op** (+6.5 %). Both
    still dominate Zay-ES and still lose to Dominion/Artemis on this
    benchmark — the ordering is stable, the magnitude is now honest.
  - **Why japes still loses to Dominion/Artemis on this one**:
    MoveSystem, DamageSystem and StatsSystem all iterate every
    entity, and japes's immutable-record writes allocate per entity.
    Five-system full-scan scenarios are exactly where the
    write-path tax bites hardest; the Dominion/Artemis mutable-POJO
    path has no equivalent cost. DEEP_DIVE's "write-path tax"
    section already owns this.

**What I didn't find any asymmetry in**

- `RealisticTickBenchmark` (now has counterparts in every library —
  japes/japes-v/Bevy/Zay-ES/Dominion/Artemis). The original audit
  flagged that only japes/Dominion/Artemis had this benchmark;
  Bevy and Zay-ES counterparts were added afterwards (Bevy using
  `Changed<T>` query filter, Zay-ES using three `EntitySet`s with
  `applyChanges()` + `getChangedEntities()`). All six libraries now
  run the same 3-mutator + 3-observer shape with 100 dirty entities
  per component per tick.
- `SparseDeltaBenchmark` observer behaviour (all four change-detection
  libraries use their native `Changed` filter; Dominion/Artemis use
  hand-rolled dirty lists documented in their own benchmark files).
- `NBodyBenchmark` setup and integrator body (same angle-distributed
  start state, same `pos += vel * dt` formula).
- Entity micros (`bulkSpawn1k`, `bulkSpawn100k`, `bulkDespawn1k`).

## Why so many ceremony-like params?

Every system parameter is a self-describing query token. `@Read C`,
`@Write Mut<C>`, `Res<R>`, `ResMut<R>`, `Commands`, `Entity`,
`RemovedComponents<C>`, `EventReader<E>`, `EventWriter<E>`, `Local<T>`
— each maps to a declarative access in the scheduler's access map,
which is how the DAG builder knows two systems are disjoint and can
run concurrently.

This is also what powers the tier-1 bytecode generator: because
parameter semantics are statically known, the generator can load each
param once per chunk and emit a tight per-entity loop with no virtual
dispatch. The same information drives `@Filter(Changed)` dirty-list
walking, `RemovedComponents<T>` subscription, and the scheduler's
disjoint-access parallelism — everything falls out of "what does this
system ask for, statically?" The ceremony is the contract.

## Raw benchmark logs

Reproducing the numbers in this document:

```bash
# Stock JDK 26 full sweep:
./gradlew :benchmark:ecs-benchmark:jmhJar \
          :benchmark:ecs-benchmark-zayes:jmhJar \
          :benchmark:ecs-benchmark-dominion:jmhJar \
          :benchmark:ecs-benchmark-artemis:jmhJar

java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-zayes/build/libs/ecs-benchmark-zayes-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-dominion/build/libs/ecs-benchmark-dominion-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-artemis/build/libs/ecs-benchmark-artemis-jmh.jar

# Valhalla JDK 27 EA sweep (needs VALHALLA_HOME or the default
# ~/.sdkman/candidates/java/valhalla-ea path, plus JEP 401 preview):
./gradlew :benchmark:ecs-benchmark-valhalla:jmhJar
$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar

# Opt-in experiments available via system properties:
#   -Dzzuegg.ecs.useFlatStorage=true    → enable JEP 401 flat arrays
#   -Dzzuegg.ecs.debugFlat=true         → log per-storage flat/non-flat

# Bevy (Rust) reference:
cd benchmark/bevy-benchmark
cargo bench

# Relations scenario only (both Bevy variants):
cargo bench -- predator_prey/naive_tick
cargo bench -- predator_prey/optimized_tick
```
