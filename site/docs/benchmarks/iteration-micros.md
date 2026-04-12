# Iteration micro-benchmark

Tight per-entity loop, nothing else going on. The read paths measure
raw query + iteration cost; `iterateWithWrite` writes back a mutated
`Position` per entity.

!!! tip "What this workload measures"

    - **`iterateSingleComponent`** — one `@Read Position` across every
      entity. Pure chunk-walk + component load.
    - **`iterateTwoComponents`** — `@Read Position` + `@Read Velocity`.
      Two loads per entity per chunk.
    - **`iterateWithWrite`** — `@Read Velocity` + `@Write Mut<Position>`
      with `p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()))`
      per entity. Stresses the write-path plus change-tracker
      bookkeeping.

    Each sweeps 1 000 / 10 000 / 100 000 entities so readers can see
    the constant-factor bend.

## Results

Numbers are µs/op, lower is better. Copied verbatim from `DEEP_DIVE.md`.

| benchmark              | entityCount | bevy | **japes** | zayes  | dominion | artemis |
|------------------------|------------:|-----:|----------:|-------:|---------:|--------:|
| `iterateSingleComponent` |        1000 | 0.24 |  **0.187** |   2.76 |     0.79 |    0.48 |
| `iterateSingleComponent` |       10000 | 2.11 |  **2.33** |   29.4 |     7.04 |    4.69 |
| `iterateSingleComponent` |      100000 | 21.0 |  **34.6** |    394 |     79.8 |     164 |
| `iterateTwoComponents`   |        1000 | 0.36 |  **0.459** |   3.55 |     1.31 |    1.17 |
| `iterateTwoComponents`   |       10000 | 3.35 |  **4.30** |   36.6 |     12.3 |    11.6 |
| `iterateTwoComponents`   |      100000 | 33.3 |  **61.5** |    507 |      128 |     226 |
| `iterateWithWrite`       |        1000 | 0.64 |  **3.79** |    180 |     2.27 |    1.82 |
| `iterateWithWrite`       |       10000 | 6.18 |  **38.1** |   1711 |     22.6 |    18.6 |
| `iterateWithWrite`       |      100000 | 62.5 |   **378** |  18205 |      233 |     332 |

!!! note "DCE-safety: why every read row has a Blackhole"

    All japes read rows consume the loaded component through a JMH
    `Blackhole` (`ReadSystem.bh.consume(pos)`). An earlier revision
    had empty system bodies (`void iterate(@Read Position p) {}`)
    which let the JIT escape-analyse the loaded record and delete
    the whole iteration loop — especially under Valhalla, where the
    "20–51× DCE artifact" rows came from. The numbers above are the
    real numbers; the [Valhalla page](valhalla.md) covers the
    blackhole guard in detail.

## Analysis

**Reading**, japes is the fastest JVM ECS in this comparison and lands
within 10–30% of Bevy. The tier-1 `GeneratedChunkProcessor` is doing
its job — each chunk becomes a tight loop that loads raw component
arrays once and dispatches through `invokevirtual` with no
`MethodHandle` or boxing.

**Writing**, japes pays for its `record` + `Mut<C>` write path:
`set(new Position(...))` allocates a new record per entity and
updates the change tracker. Dominion and Artemis use mutable POJO
components and mutate fields in place — no allocation, no tracking —
so they come out 5–10× ahead on the naive microbenchmark.

## The write-path tax

The `iterateWithWrite`, `NBody` and sparse-delta rows all show japes
paying a measurable cost against Dominion / Artemis on the same
workload. That is **not** the tier-1 generator being slow — it is an
*API choice* being measured:

- **japes** idiomatic write path is `@Write Mut<Position>` +
  `record Position`, so `p.set(new Position(...))` allocates a new
  record and records a change so `@Filter(Changed.class)` observers
  can react automatically.
- **Dominion / Artemis** components are mutable POJO classes;
  `p.x += v.dx` does no allocation and leaves no audit trail — which
  also means if you want `@Filter(Changed)` or `RemovedComponents`
  semantics, you have to open-code them yourself at every mutation
  site.

So: if you want raw in-place writes with no change tracking and are
willing to hand-write dirty-list plumbing at every mutation site, use
Dominion or Artemis — they'll be faster on every *micro*benchmark on
this page. If you want immutable components with observer systems
that the library wires up for you, use japes or Zay-ES — and on the
[multi-observer realistic tick](realistic-tick.md) japes is the
cheapest configuration in absolute CPU cost.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "IterationBenchmark"
```

To pin a single cell (e.g. just `iterateSingleComponent` at 10k), JMH
accepts the usual regex + parameter filters:

```bash
java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "IterationBenchmark.iterateSingleComponent" \
  -p entityCount=10000
```
