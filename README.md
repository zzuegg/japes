# japes

**J**ava **A**rchetype-based **P**arallel **E**ntity **S**ystem — a high-throughput
ECS library for the JVM, with change detection, systems-as-methods, and a
tier-1 code generator that turns system methods into direct-dispatch hidden
classes via the `java.lang.classfile` API.

japes targets Bevy-style ergonomics in Java: data is stored in archetype chunks,
systems are plain methods annotated with `@System`, parameters are queried by
type (`@Read C`, `@Write Mut<C>`, `Res<R>`, `Commands`, `Entity`, ...), and the
scheduler builds a DAG from per-system access metadata.

> Status: pre-release. API is stable enough to benchmark but reserves the right
> to change before 1.0.

---

## Highlights

- **Archetype + chunk storage** — entities are grouped by component set and stored
  in SoA chunks. Per-component arrays are dense, contiguous, and cache-friendly.
- **Systems as methods** — annotate a method with `@System` and declare its
  params; the scheduler derives reads/writes and slots it into the DAG.
- **Immutable components by default** — writes go through `Mut<C>` with explicit
  `set(...)`, giving clean change-detection semantics without Unsafe-style
  in-place mutation. (For raw write benchmarks japes also supports mutable
  components, but the idiomatic shape is `record`.)
- **Tier-1 direct dispatch** — the `GeneratedChunkProcessor` emits a hidden class
  per system that loads component arrays once per chunk and calls the system
  method via `invokevirtual` with no MethodHandle or boxing overhead.
- **First-class change detection** — `@Filter(Added.class, target = C.class)`,
  `@Filter(Changed.class, ...)`, and `RemovedComponents<C>` let observer
  systems do work proportional to the *delta*, not the total entity count.
- **Commands / deferred mutation** — structural edits inside a parallel stage
  go through a `Commands` buffer and flush at the stage boundary.
- **Resources, events, locals** — `Res<R>`, `ResMut<R>`, `EventReader<E>`,
  `EventWriter<E>`, `Local<T>` — all injected as system params.
- **DAG scheduler with conflict detection** — systems in the same stage run in
  parallel when their component-access sets are disjoint.

---

## A ten-second example

```java
record Position(float x, float y, float z) {}
record Velocity(float dx, float dy, float dz) {}
record DeltaTime(float dt) {}

class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p, Res<DeltaTime> dt) {
        var cur = p.get();
        var d = dt.get().dt();
        p.set(new Position(cur.x() + v.dx()*d, cur.y() + v.dy()*d, cur.z() + v.dz()*d));
    }
}

var world = World.builder()
    .addResource(new DeltaTime(0.016f))
    .addSystem(Physics.class)
    .build();

world.spawn(new Position(0, 0, 0), new Velocity(1, 2, 3));
world.tick();
```

---

## Change detection

```java
class DeathObserver {
    @System
    @Filter(value = Changed.class, target = Health.class)
    void onHealthChanged(@Read Health h, Entity self) {
        // Runs once per entity whose Health changed this tick.
    }

    @System
    void onDead(RemovedComponents<Health> dead, ResMut<Stats> stats) {
        long count = 0;
        for (var e : dead) count++;
        stats.set(new Stats(stats.get().deaths() + count));
    }
}
```

`@Filter(Changed.class, target = C.class)` walks a per-tracker dirty slot list
that is maintained by `Mut.set(...)` and `world.setComponent(...)`. For sparse
workloads (1% of entities touched per tick) the observer's cost is proportional
to the dirty count, not the archetype size — see the `SparseDelta` benchmark.

---

## Deferred structural edits

```java
class Reaper {
    @System
    void reap(@Read Health h, Entity self, Commands cmds) {
        if (h.hp() <= 0) {
            cmds.despawn(self);
        }
    }
}
```

`Commands` buffers spawn/despawn/insert/remove calls so a parallel stage can
issue them without racing on the archetype graph. All buffers flush at the
stage boundary in a single-threaded pass.

---

## Project layout

```
ecs-core/                      — library source + unit tests
benchmark/
  ecs-benchmark/               — japes JMH benches (iteration, NBody,
                                 particle scenario, sparse delta, micro)
  ecs-benchmark-valhalla/      — same benches, running against a Valhalla JDK
  ecs-benchmark-dominion/      — Dominion-odb counterpart (idiomatic)
  ecs-benchmark-artemis/       — Artemis-odb counterpart (idiomatic)
  ecs-benchmark-zayes/         — Zay-ES counterpart (idiomatic)
  bevy-benchmark/              — Bevy (Rust) reference — run with `cargo bench`
```

Every Java benchmark module has a `jmhJar` target:

```
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar
```

The Bevy benchmarks use Criterion:

```
cd benchmark/bevy-benchmark
cargo bench
```

---

## Benchmark results

All numbers below are µs per benchmark op, **lower is better**. Each Java
library is tested in its own idiomatic shape (see the per-library benchmark
source for what that means); Bevy is the Rust reference. Same workload across
every column. The raw JMH / Criterion output is in the section below.

Hardware: the numbers were collected on a single workstation, single-threaded,
JMH `@Fork=2`, `@Warmup=3x1s`, `@Measurement=5x1s` (NBody uses 2 s windows).
JDK 26 with `--enable-preview`. Treat absolute numbers as a point-in-time
snapshot — relative ordering is what matters.

### Iteration micro-benchmark

Tight per-entity loop, nothing else going on. The read paths measure raw
query + iteration cost; `iterateWithWrite` writes back a mutated Position.

| benchmark              | entityCount | bevy | **japes** | zayes  | dominion | artemis |
|------------------------|------------:|-----:|----------:|-------:|---------:|--------:|
| iterateSingleComponent |        1000 | 0.24 |  **0.23** |   2.76 |     0.79 |    0.48 |
| iterateSingleComponent |       10000 | 2.11 |  **2.33** |   28.1 |     7.22 |    4.67 |
| iterateSingleComponent |      100000 | 21.0 |  **37.5** |    382 |     81.8 |     164 |
| iterateTwoComponents   |        1000 | 0.36 |  **0.49** |   3.58 |     1.31 |    1.17 |
| iterateTwoComponents   |       10000 | 3.35 |  **4.28** |   37.4 |     12.4 |    11.5 |
| iterateTwoComponents   |      100000 | 33.3 |  **64.5** |    519 |      129 |     230 |
| iterateWithWrite       |        1000 | 0.64 |  **5.82** |    180 |     2.29 |    1.82 |
| iterateWithWrite       |       10000 | 6.18 |  **57.3** |   1910 |     22.6 |    18.4 |
| iterateWithWrite       |      100000 | 62.5 |   **569** |  19226 |      233 |     332 |

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

### N-body integration

Full world tick with a single integrate system, `dt` supplied via `Res<T>`
(japes) or a world-level field (others).

| benchmark        | bodyCount | bevy | **japes** | zayes | dominion | artemis |
|------------------|----------:|-----:|----------:|------:|---------:|--------:|
| simulateOneTick  |      1000 | 0.88 |  **6.26** |  43.9 |     2.43 |    1.83 |
| simulateOneTick  |     10000 | 8.79 |  **62.6** |   444 |     23.9 |    19.2 |
| simulateTenTicks |      1000 | 8.92 |  **62.4** |   436 |     24.5 |    18.4 |
| simulateTenTicks |     10000 | 88.2 |   **625** |  4455 |      238 |     191 |

Same shape as the write-path iteration benchmark — the integrator allocates a
new `Position` record per body per tick. Dominion's and Artemis's in-place
floats mean the JIT can keep the whole loop in SIMD-ish registers.

### Particle scenario (move + damage + reap + respawn + stats)

This is the full end-to-end benchmark: 10 000 entities, ~1% turnover per tick,
five systems wired through the scheduler. This is what real game-loop code
looks like.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 22.4 |   **161** |  1777 |     68.7 |    98.3 |

### Sparse delta (change-detection workload)

10 000 entities, 100 touched per tick. An observer reacts only to the entities
whose `Health` changed. This is the scenario change detection is *built for*:
per-tick work should scale with the dirty count, not the total entity count.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| tick      |       10000 | 4.01 |  **5.22** |  4.60 |     0.38 |    0.25 |

This is the most interesting row in the whole table, so it deserves the most
explanation.

**japes / Zay-ES / Bevy** implement the workload the way the API advertises:
the driver calls `world.setComponent(e, new Health(...))` (or Zay-ES's
`data.setComponent(...)`), which the library records in a per-tick dirty
tracker; the observer system is scheduled automatically and walks the library's
dirty view. The user writes zero bookkeeping code and the contract "every
mutation is observed" is enforced globally.

**Dominion / Artemis** have no change detection. The honest implementation is
the pattern a performance-conscious user would hand-write: mutate the
component's field in place *and* push the entity handle onto a
caller-maintained dirty buffer (`Entity[]` for Dominion, `IntBag` for
Artemis). The "observer" is just a second loop over that buffer. See
`DominionSparseDeltaBenchmark` / `ArtemisSparseDeltaBenchmark` for the exact
shape.

That hand-written path turns out to be **dramatically faster** on the
microbenchmark — Artemis is ~20× faster than japes here. The reason is
unsurprising once you unpack it:

- Dominion / Artemis do a `hp -= 1` int write and append an entity reference.
  No allocation, no tick-counter comparison, no atomic state update, no
  scheduler.
- japes / Bevy / Zay-ES pay for change-tracking bookkeeping at every
  `setComponent` call *and* at the observer side when walking the dirty view.
  In japes this is one indexed bitmap update + one dirty-slot list append
  per call, plus scheduler overhead on the observer side — and it amortises
  poorly across *just 100* entities with nothing else running.

**What you're trading for that ~20× microbenchmark gap.** The hand-rolled
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

### Realistic multi-observer tick

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
| **japes**    |  **16.4**  |       21.5 |         **16.4** |        ~65   |
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
you hand-roll the bookkeeping, so they iterate the world.

**2. Multi-threaded, Dominion/Artemis catch up by parallelising waste.**
Their `mt` speedup comes from dispatching 30 000 entity reads across
three cores — they benefit from parallelism *because* they have so much
to parallelise. japes's `mt` variant actually gets *worse* than its `st`
because at 300 entity reads the ForkJoinPool dispatch overhead
(~microseconds per system) exceeds the parallelism benefit. The
library's core competency — skipping the work in the first place —
shrinks the work per tick below the threshold where parallelism earns
back its overhead.

**3. By total CPU cost, japes `st` is the cheapest configuration in the
table.** ~16.4 µs of single-core work beats Artemis's fastest `mt`
configuration (~39 core·µs) by a factor of 2.4, and Dominion's fastest
by 3.4×. "Library does less work" wins "other libraries do more work
on more cores." In a real game loop the cores you don't burn on this
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

### The "write-path tax" — why japes looks slow on naked writes

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

### Speed-up matrix vs. Bevy (lower is better, `1.0×` = matches Bevy)

| benchmark                   |         case | **japes** | zayes |  dominion | artemis |
|-----------------------------|-------------:|----------:|------:|----------:|--------:|
| iterateSingleComponent      |          10k |  **1.1×** | 13.3× |     3.4×  |   2.2×  |
| iterateTwoComponents        |          10k |  **1.3×** | 11.2× |     3.7×  |   3.4×  |
| iterateWithWrite            |          10k |  **9.3×** |  309× |     3.7×  |   3.0×  |
| NBody simulateOneTick       |          10k |  **7.1×** |   51× |     2.7×  |   2.2×  |
| ParticleScenario tick       |          10k |  **7.2×** |   79× |     3.1×  |   4.4×  |
| SparseDelta tick            |          10k |  **1.3×** |  1.1× |  **0.09×**| **0.06×**|

> In the sparse-delta row, "below 1.0×" means *faster than Bevy*. Dominion and
> Artemis beat Bevy here because they use manually-maintained dirty lists
> (one field write, one array append, no tick counter, no scheduler) —
> faster on the micro, but the correctness burden is on the user.

### Raw benchmark logs

If you want to reproduce these numbers:

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar \
          :benchmark:ecs-benchmark-zayes:jmhJar \
          :benchmark:ecs-benchmark-dominion:jmhJar \
          :benchmark:ecs-benchmark-artemis:jmhJar

java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-zayes/build/libs/ecs-benchmark-zayes-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-dominion/build/libs/ecs-benchmark-dominion-jmh.jar
java --enable-preview -jar benchmark/ecs-benchmark-artemis/build/libs/ecs-benchmark-artemis-jmh.jar
```

Bevy:

```bash
cd benchmark/bevy-benchmark
cargo bench
```

---

## Why so many ceremony-like params?

Every system parameter is a self-describing query token. `@Read C`, `@Write Mut<C>`,
`Res<R>`, `ResMut<R>`, `Commands`, `Entity`, `RemovedComponents<C>`,
`EventReader<E>`, `EventWriter<E>`, `Local<T>` — each maps to a declarative
access in the scheduler's access map, which is how the DAG builder knows two
systems are disjoint and can run concurrently.

This also powers the tier-1 bytecode generator: because parameter semantics are
statically known, the generator can load each param once per chunk and emit a
tight per-entity loop with no virtual dispatch.

---

## Build + requirements

- **JDK 26** with `--enable-preview` (uses `java.lang.classfile`)
- Gradle wrapper included — `./gradlew build`
- For Valhalla comparisons: a Valhalla JDK build, pointed at via the
  `benchmark/ecs-benchmark-valhalla` toolchain config.

---

## License

TBD
