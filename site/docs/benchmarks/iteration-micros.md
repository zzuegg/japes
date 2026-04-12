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
| `iterateSingleComponent` |        1000 | 0.259 |  **0.302** |   2.75 |     0.780 |    0.470 |
| `iterateSingleComponent` |       10000 | 2.18 |  **2.94** |   28.6 |     7.06 |    4.52 |
| `iterateSingleComponent` |      100000 | 21.3 |  **31.6** |    383 |     79.4 |     166 |
| `iterateTwoComponents`   |        1000 | 0.395 |  **0.594** |   3.55 |     1.31 |    1.18 |
| `iterateTwoComponents`   |       10000 | 3.70 |  **5.92** |   38.6 |     12.4 |    11.6 |
| `iterateTwoComponents`   |      100000 | 36.8 |  **65.8** |    508 |      128 |     237 |
| `iterateWithWrite`       |        1000 | 0.656 |  **0.138** |    182 |     2.32 |    1.83 |
| `iterateWithWrite`       |       10000 | 6.29 |  **1.70** |   1818 |     22.5 |    18.2 |
| `iterateWithWrite`       |      100000 | 63.7 |  **26.0** |  17857 |      234 |     334 |

!!! warning "Methodology change: field-level blackhole on iteration micros"

    The japes numbers above use **field-level blackhole** (`bh.consume(p.x()); bh.consume(p.y())`) instead of object-level (`bh.consume(p)`). This matches real game code where systems compute with field values, not store references — and it lets the JIT scalar-replace records reconstructed from SoA arrays. The Bevy column still uses Criterion's `black_box(p)` on the whole struct, so the **read rows are not directly comparable across languages**. Write rows are unaffected (the write itself is the work). See [One JIT to rule them all](../deep-dive/one-jit-to-rule-them-all.md) for the full EA story.

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
within 35–60% of Bevy at 10k. Note that the japes read rows use
field-level blackhole (`bh.consume(p.x())`) while Bevy uses
object-level `black_box(p)`, so the read-row comparison is not
apples-to-apples. The tier-1 `GeneratedChunkProcessor` is doing
its job — each chunk becomes a tight loop that loads raw SoA
component arrays once and dispatches through `invokevirtual` with no
`MethodHandle` or boxing.

**Writing**, japes is now **faster than Bevy and every Java library**
on this benchmark. With SoA storage as the default, the
`new Position(...)` record in the write path is scalar-replaced by the
JIT — the record fields decompose into primitive `fastore` instructions
on the backing `float[]` arrays, with zero per-entity heap allocation.
At 10k, japes (1.70 µs) is **3.7× faster than Bevy** (6.29 µs) and
**13× faster than Dominion** (22.5 µs). This is the payoff from the
SoA + escape-analysis story documented in
[One JIT to rule them all](../deep-dive/one-jit-to-rule-them-all.md).

## The write-path story (pre-SoA vs post-SoA)

Before SoA storage, japes's `Object[]` backing arrays forced every
`new Position(...)` to be heap-allocated (the `aastore` into
`Object[]` required a heap reference, defeating escape analysis).
That was the historical "write-path tax" — 6.1x slower than Bevy.

With SoA storage (`float[] x`, `float[] y`, `float[] z`), the store
decomposes into `fastore` instructions on primitive arrays. The JIT
can now prove the `Position` record never escapes and scalar-replace
it entirely. Combined with the tier-1 generator's `invokevirtual`
inlining, the full chain from `Mut.set(new Position(...))` through
to the backing array store is register-only.

The `record` + `Mut<C>` API contract is unchanged — `p.set(new
Position(...))` still records a change so `@Filter(Changed.class)`
observers can react automatically. The performance difference is
purely in the storage layer.

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
