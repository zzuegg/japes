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
| `iterateSingleComponent` |        1000 | 0.259 |  **0.187** |   2.75 |     0.780 |    0.470 |
| `iterateSingleComponent` |       10000 | 2.18 |  **2.43** |   28.6 |     7.06 |    4.52 |
| `iterateSingleComponent` |      100000 | 21.3 |  **34.4** |    383 |     79.4 |     166 |
| `iterateTwoComponents`   |        1000 | 0.395 |  **0.463** |   3.55 |     1.31 |    1.18 |
| `iterateTwoComponents`   |       10000 | 3.70 |  **4.33** |   38.6 |     12.4 |    11.6 |
| `iterateTwoComponents`   |      100000 | 36.8 |  **65.4** |    508 |      128 |     237 |
| `iterateWithWrite`       |        1000 | 0.656 |  **3.83** |    182 |     2.32 |    1.83 |
| `iterateWithWrite`       |       10000 | 6.29 |  **38.5** |   1818 |     22.5 |    18.2 |
| `iterateWithWrite`       |      100000 | 63.7 |   **377** |  17857 |      234 |     334 |

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
