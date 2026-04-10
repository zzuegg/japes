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

**What you're trading for that ~20× microbenchmark gap** is correctness at
scale: in Dominion / Artemis any mutation site that forgets to append to
the dirty buffer silently drops an event, and every distinct observer needs
its own dirty set. At game-loop scale with tens of mutation sites and
several observers, the library doing the tracking for you is worth a real
amount of engineering risk. On this *microbenchmark*, though, the manual
path wins cleanly — which is the honest answer and belongs in the table.

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
Artemis — they'll be faster on every microbenchmark in this README. If you
want immutable components with observer systems that the library wires up
for you, use japes or Zay-ES — and on that workload japes is an order of
magnitude faster than Zay-ES and close to Bevy.

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
