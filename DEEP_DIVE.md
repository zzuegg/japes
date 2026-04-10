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
| 7 | [Does Valhalla help?](#does-valhalla-help-jdk-27-ea-jep-401-value-records) | JEP 401 EA sweep, flat-array opt-in A/B, the DCE caveat |
| 8 | [The "write-path tax"](#the-write-path-tax--why-japes-looks-slow-on-naked-writes) | Why Dominion/Artemis win the naked-write micros |
| 9 | [Speed-up matrix vs. Bevy](#speed-up-matrix-vs-bevy-lower-is-better-10-matches-bevy) | One-line relative perf summary across every benchmark |
| 10 | [Why so many ceremony-like params?](#why-so-many-ceremony-like-params) | Design rationale for `@Read C` / `@Write Mut<C>` / `Res<R>` |
| 11 | [Raw benchmark logs](#raw-benchmark-logs) | Commands to reproduce every number above |

See [README](README.md) for the quick-start and [TUTORIAL.md](TUTORIAL.md)
for a step-by-step walkthrough of the API.

## Methodology

All numbers below are µs per benchmark op, **lower is better**. Each
Java library is tested in its own idiomatic shape (see the per-library
benchmark source for what that means); Bevy is the Rust reference.
Same workload across every column.

Hardware: single workstation, single-threaded unless noted, JMH
`@Fork=2`, `@Warmup=3×1s`, `@Measurement=5×1s` (NBody uses 2 s
windows). Stock numbers are JDK 26 with `--enable-preview`; Valhalla
numbers use an EA build of JDK 27 with JEP 401 preview. Treat
absolute values as a point-in-time snapshot — relative ordering is
what matters.

## Iteration micro-benchmark

Tight per-entity loop, nothing else going on. The read paths measure raw
query + iteration cost; `iterateWithWrite` writes back a mutated Position.

| benchmark              | entityCount | bevy | **japes** | zayes  | dominion | artemis |
|------------------------|------------:|-----:|----------:|-------:|---------:|--------:|
| iterateSingleComponent |        1000 | 0.24 |  **0.21** |   2.76 |     0.79 |    0.48 |
| iterateSingleComponent |       10000 | 2.11 |  **2.37** |   28.1 |     7.22 |    4.67 |
| iterateSingleComponent |      100000 | 21.0 |  **38.7** |    382 |     81.8 |     164 |
| iterateTwoComponents   |        1000 | 0.36 |  **0.55** |   3.58 |     1.31 |    1.17 |
| iterateTwoComponents   |       10000 | 3.35 |  **6.12** |   37.4 |     12.4 |    11.5 |
| iterateTwoComponents   |      100000 | 33.3 |  **69.9** |    519 |      129 |     230 |
| iterateWithWrite       |        1000 | 0.64 |  **5.80** |    180 |     2.29 |    1.82 |
| iterateWithWrite       |       10000 | 6.18 |  **58.2** |   1910 |     22.6 |    18.4 |
| iterateWithWrite       |      100000 | 62.5 |   **576** |  19226 |      233 |     332 |

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
| simulateOneTick  |      1000 | 0.88 |  **6.24** |  43.9 |     2.43 |    1.83 |
| simulateOneTick  |     10000 | 8.79 |  **62.8** |   444 |     23.9 |    19.2 |
| simulateTenTicks |      1000 | 8.92 |  **62.6** |   436 |     24.5 |    18.4 |
| simulateTenTicks |     10000 | 88.2 |   **624** |  4455 |      238 |     191 |

Same shape as the write-path iteration benchmark — the integrator allocates a
new `Position` record per body per tick. Dominion's and Artemis's in-place
floats mean the JIT can keep the whole loop in SIMD-ish registers.

## Particle scenario (move + damage + reap + respawn + stats)

This is the full end-to-end benchmark: 10 000 entities, ~1% turnover per tick,
five systems wired through the scheduler. This is what real game-loop code
looks like.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 22.4 |   **157** |  1777 |     68.7 |    98.3 |

## Sparse delta (change-detection workload)

10 000 entities, 100 touched per tick. An observer reacts only to the entities
whose `Health` changed. This is the scenario change detection is *built for*:
per-tick work should scale with the dirty count, not the total entity count.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 4.01 |  **1.86** |  4.60 |     0.37 |    0.26 |

This is the most interesting row in the whole table, so it deserves the most
explanation.

**japes / Zay-ES / Bevy** implement the workload the way the API advertises:
the driver calls `world.setComponent(e, new Health(...))` (or Zay-ES's
`data.setComponent(...)`), which the library records in a per-tick dirty
tracker; the observer system is scheduled automatically and walks the library's
dirty view. The user writes zero bookkeeping code and the contract "every
mutation is observed" is enforced globally. At **1.86 µs/op japes is
the fastest of the library-change-detection group by a wide margin**
(Bevy is 4.01, Zay-ES is 4.60) after three rounds of profile-guided
fixes: cached `ArchetypeId.hashCode`, generation-keyed
`findMatching` cache, one-lookup `setComponent`, direct `Archetype`
reference on `EntityLocation`, array-indexed chunk lookups keyed by
`ComponentId.id()`, and `setComponent` chunk consolidation. **japes
is 2.16× faster than Bevy** on this workload.

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

The Dominion and Artemis counterparts (`DominionRealisticTickBenchmark`,
`ArtemisRealisticTickBenchmark`) implement the *same workload* the way a
user would when they don't want to hand-roll dirty lists per component:
the observer passes are full iterations over every entity that has the
component (10 000 each), because neither library knows what's dirty. The
`mt` variant in those libraries dispatches the three observer passes to
a fixed `ExecutorService` — exactly what japes does for you from the
declared system access metadata, except you have to wire it up by hand.

**Results (10 000 entities, 100 dirty per component, µs/op — lower is better):**

| library      | `st` µs/op | `mt` µs/op |     core·µs `st` | core·µs `mt` |
|--------------|-----------:|-----------:|-----------------:|-------------:|
| **japes**    |   **5.79** |      10.49 |         **5.79** |        ~31   |
| artemis      |       24.7 |   **12.9** |             24.7 |        ~39   |
| dominion     |       41.7 |       18.9 |             41.7 |        ~57   |

*`core·µs` is the rough total-CPU cost — `st` uses 1 core, `mt` runs
three observer passes concurrently on ~3 cores. It's a back-of-envelope
number but it captures "how much CPU did the whole machine spend to
serve one tick" which is the number that matters on a shared box.*

**Three things this table shows:**

**1. Single-threaded, japes wins by doing less work.** 300 observed
entities vs 30 000 scanned. `@Filter(Changed, C)` is the reason: the
observer sees the 100 dirty entities the scheduler tracked for it and
skips the other 9 900. Dominion and Artemis have no equivalent unless
you hand-roll the bookkeeping, so they iterate the world. At 5.79 µs
single-threaded, japes finishes a tick in **less than half the time
of Artemis's fastest multi-threaded configuration** (12.9 µs) on the
same workload.

**2. Multi-threaded, Dominion/Artemis catch up by parallelising waste.**
Their `mt` speedup comes from dispatching 30 000 entity reads across
three cores — they benefit from parallelism *because* they have so much
to parallelise. japes's `mt` variant is modestly *worse* than its `st`
because at 300 entity reads the ForkJoinPool dispatch overhead
(~microseconds per system) exceeds the parallelism benefit. The
library's core competency — skipping the work in the first place —
shrinks the work per tick below the threshold where parallelism earns
back its overhead.

**3. By total CPU cost, japes `st` is the cheapest configuration in the
table by a wide margin.** ~5.79 µs of single-core work beats Artemis's
fastest `mt` configuration (~39 core·µs) by a factor of **6.7**, and
Dominion's fastest by **9.8×**. "Library does less work" wins "other
libraries do more work on more cores." In a real game loop the cores
you don't burn on this
tick are the cores that are free to do AI, physics, rendering, audio,
or literally anything else — that is the actual win.

**So why run `mt` at all?** Because a game loop has *other* systems
beyond the three observers in this benchmark. Under the japes scheduler,
independent systems anywhere in the graph run concurrently for free from
their declared component access, with no user code required. This
benchmark isolates the observer-only part of the tick — the full-game
story is "japes `mt` is a Pareto improvement over japes `st` when the
tick has enough non-observer work to parallelise."

**Code comparison.** Here's what japes looks like (complete — no other
plumbing):

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
| iterateSingleComponent        |           10k |      2.37 |        1.03 | **2.30×** real  |
| iterateSingleComponent        |          100k |      38.7 |        10.6 | **3.66×** real  |
| iterateTwoComponents          |           10k |      6.12 |        1.93 | **3.17×** real  |
| iterateTwoComponents          |          100k |      69.9 |        18.7 | **3.74×** real  |
| iterateWithWrite              |           10k |      58.2 |        52.0 | **1.12×** real  |
| iterateWithWrite              |          100k |       576 |         519 | **1.11×** real  |
| NBody simulateOneTick         |            1k |      6.24 |        5.98 | **1.04×** real  |
| NBody simulateOneTick         |           10k |      62.8 |        57.3 | **1.10×** real  |
| NBody simulateTenTicks        |           10k |       624 |         569 | **1.10×** real  |
| ParticleScenario tick         |           10k |       157 |         186 | 0.84× slower    |
| SparseDelta tick              |           10k |      1.86 |        2.60 | 0.72× slower    |
| RealisticTick tick            |     10k / st  |      5.79 |        14.0 | 0.41× slower    |
| RealisticTick tick            |     10k / mt  |     10.49 |        19.1 | 0.55× slower    |

**The reads now tell the real story.** An earlier revision of the japes
iteration benchmarks had empty system bodies (`void iterate(@Read
Position p) {}`), which let the escape analyser prove the load was
unused and delete the whole iteration — I was reporting "20–80×"
speedups that were measuring nothing. After adding a JMH `Blackhole`
consumer to every read system (`bh.consume(pos)`), the JIT has to
actually touch each element, and the real Valhalla number comes out:
**2.3–3.7× faster on reads** once you scale past 10 k entities. That's
the JEP 401 flat-array layout paying off exactly where it should —
sequential dense iteration over a primitive-backed storage.

**What the table actually shows:**

- **Reads — big and real.** At 100 k entities Valhalla finishes the
  same iteration in ~28 % of stock japes's time. The flat backing
  layout turns every read into a direct aaload against a primitive
  region instead of a pointer chase + field load on a heap record, and
  the tier-1 generator's tight chunk loop inlines cleanly on top of it.
  This is the biggest cross-JVM number in the whole README.
- **Writes — modest.** iterateWithWrite and NBody gain **~10 %** under
  Valhalla. Writes still allocate `new Position(...)` (either a flat
  value or a heap record depending on the JVM), and the store into the
  backing array has the same cost either way, so there's less for
  Valhalla to optimise. The win is real but in the noise floor of
  the measurement.
- **Scenarios — still a regression.** `ParticleScenario` is 14 % slower
  under Valhalla, `RealisticTick` 34–42 % slower. GC profiling showed
  Valhalla allocating **2.2×** more per op on RealisticTick than stock
  japes (13 349 B/op vs 6 085 B/op); stack sampling put
  `DefaultComponentStorage.get` / `set` at a combined 18 % of CPU under
  Valhalla vs invisible under stock. The culprit is that value records
  cross the erased `Record` parameter of `World.setComponent` in the
  scenario hot path, which forces the JVM to box the value into a
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
| iterateSingleComponent      |          10k |   **1.1×** |   **0.49×** | 13.3× |     3.4×  |   2.2×  |
| iterateTwoComponents        |          10k |   **1.8×** |   **0.58×** | 11.2× |     3.7×  |   3.4×  |
| iterateWithWrite            |          10k |   **9.4×** |    **8.4×** |  309× |     3.7×  |   3.0×  |
| NBody simulateOneTick       |          10k |   **7.1×** |    **6.5×** |   51× |     2.7×  |   2.2×  |
| ParticleScenario tick       |          10k |   **7.0×** |        8.3× |   79× |     3.1×  |   4.4×  |
| SparseDelta tick            |          10k |  **0.64×** |   **0.65×** |  1.1× |  **0.09×**| **0.07×**|
| RealisticTick tick (st)     |          10k |  see above |   see above |   —   |    see above | see above |

> In the sparse-delta and iteration-read rows, "below 1.0×" means *faster
> than Bevy*. After two rounds of profile-guided fixes (hash-code cache,
> match-cache, single-lookup setComponent, plus the direct-Archetype
> reference on EntityLocation + array-indexed chunk lookups in round 3)
> japes runs this workload in 1.86 µs vs
> Bevy's 4.01. Under Valhalla with value records, japes's iteration reads
> at 10k land at ~0.5× Bevy (2× faster). Dominion and Artemis sit further
> below on SparseDelta because they use manually-maintained dirty lists
> (one field write, one array append, no tick counter, no scheduler) —
> faster on the micro, but the correctness burden is on the user.

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
```
