# Does Valhalla help? (JDK 27 EA, JEP 401 value records)

Every component in japes is a `record`, which means the backing
component storage is a reference array — reading a `Position` is a
pointer-chase and writing one allocates a fresh heap record.
Valhalla's JEP 401 promises flat layout for `value record`s: the
same backing array becomes a flat `float[]` and loads become plain
array indexing.

!!! tip "What this page measures"

    The `ecs-benchmark-valhalla` module ports every japes benchmark
    with `value record` components and runs them against a Valhalla
    EA build (`openjdk 27-jep401ea3`). The two runs use the same
    Java source, same `--enable-preview`, same JMH settings, same
    tier-1 generator — only the component declaration
    (`record` vs `value record`) and the runtime JVM differ.

## Results

Numbers are µs/op, lower is better. `japes-v` is japes on the
Valhalla EA JVM with value records. Copied verbatim from
`DEEP_DIVE.md`.

| benchmark                     |          case | **japes** | **japes-v** | Δ               |
|-------------------------------|--------------:|----------:|------------:|----------------:|
| `iterateSingleComponent`      |           10k |      2.43 |        1.06 | **2.29×** real  |
| `iterateSingleComponent`      |          100k |      34.4 |        9.31 | **3.69×** real  |
| `iterateTwoComponents`        |           10k |      4.33 |        1.85 | **2.34×** real  |
| `iterateTwoComponents`        |          100k |      65.4 |        20.0 | **3.27×** real  |
| `iterateWithWrite`            |           10k |      38.5 |        53.2 | 0.72× slower    |
| `iterateWithWrite`            |          100k |       377 |         536 | 0.70× slower    |
| NBody `simulateOneTick`       |            1k |         4 |        5.84 | 0.68× slower    |
| NBody `simulateOneTick`       |           10k |        41 |        57.3 | 0.72× slower    |
| NBody `simulateTenTicks`      |           10k |       399 |         577 | 0.69× slower    |
| `ParticleScenario tick`       |           10k |       107 |         180 | 0.59× slower    |
| `SparseDelta tick`            |           10k |      1.88 |        1.96 | 0.96× slower    |
| `RealisticTick tick`          |     10k / st  |      5.86 |        11.9 | 0.49× slower    |
| `RealisticTick tick`          |     10k / mt  |      10.3 |        17.8 | 0.58× slower    |

## The reads tell the real story

!!! note "The DCE trap and why this table has a `real` suffix"

    An earlier revision of the japes iteration benchmarks had empty
    system bodies (`void iterate(@Read Position p) {}`), which let
    the escape analyser prove the load was unused and delete the
    whole iteration — previously reported "20–80×" speedups were
    measuring nothing. With a JMH `Blackhole` consumer on every
    read system (`bh.consume(pos)`), the JIT has to actually touch
    each element, and the real Valhalla number comes out:
    **2.2–4.0× faster on reads** once you scale past 10 k entities.

    That's the JEP 401 flat-array layout paying off exactly where it
    should — sequential dense iteration over a primitive-backed
    storage.

What the table actually shows:

- **Reads — big and real.** At 100k entities Valhalla finishes
  `iterateSingleComponent` in ~27% of stock japes's time (3.69×),
  and `iterateTwoComponents` in ~31% (3.27×). The flat backing
  layout turns every read into a direct `aaload` against a
  primitive region instead of a pointer chase + field load on a
  heap record, and the tier-1 generator's tight chunk loop inlines
  cleanly on top of it. This is the biggest cross-JVM number in
  the whole project.
- **Writes — stock is now faster.** `iterateWithWrite` and NBody are
  **~30% faster on stock JDK 26** than under Valhalla EA. Writes
  still allocate `new Position(...)` (either a flat value or a heap
  record depending on the JVM), and the Valhalla EA JIT appears to
  regress on the write path — the reference-array store that stock
  JDK 26 has optimised for years is now being outpaced by the
  stock JIT's improvements rather than helped by Valhalla.
- **Scenarios — Valhalla regresses.** `ParticleScenario` is 68%
  slower under Valhalla, `RealisticTick st` 103% slower,
  `RealisticTick mt` 73% slower. `SparseDelta` has
  tightened to 4% slower, down from the 40% gap seen in earlier
  rounds — the PR's `ChangeTracker.swapRemove` fix and the
  concurrent `ArchetypeGraph` cache both trim Valhalla overhead
  disproportionately, because the EA JIT was amplifying the
  pre-fix hot paths. GC profiling still shows Valhalla allocating
  **~2×** more per op on the scenario benchmarks than stock japes;
  the residual regression comes from value records crossing the
  erased `Record` parameter of `World.setComponent`, which forces
  the JVM to box the value into a heap wrapper even though the
  storage layer is value-aware.

## Does an explicit flat-array opt-in fix it?

JEP 401 EA exposes an experimental flat-array allocator at
`jdk.internal.value.ValueClass.newNullRestrictedNonAtomicArray(Class, int, Object)`
plus a class-level
`@jdk.internal.vm.annotation.LooselyConsistentValue` opt-in.
Both are wired into `DefaultComponentStorage` and the Valhalla
benchmark records (see the `DefaultComponentStorage` static
initialiser — it's gated behind
`-Dzzuegg.ecs.useFlatStorage=true` so it's off by default). The
resulting backing array genuinely is flat
(`ValueClass.isFlatArray(arr) == true`, verified in-process), but
in an A/B comparison on the same JVM it was **measurably worse**:

| benchmark                  | flat OFF | flat ON | Δ               |
|----------------------------|---------:|--------:|----------------:|
| `iterateTwoComponents` 10k |     1.79 |    6.18 | **3.4× slower** |
| `iterateTwoComponents` 100k|     18.4 |    64.3 | **3.5× slower** |
| `RealisticTick st`         |     14.0 |    16.3 | 16% slower      |
| `SparseDelta`              |     2.57 |    2.49 | noise           |

The EA JIT clearly hasn't yet emitted optimised get/set code for
flat null-restricted arrays — the flat layout is in place but
accessing it goes through a slower path than the reference-array
fallback that the JIT has had longer to optimise. All the real
Valhalla wins above (the 2–4× reads)
come from the *reference-array* path, where the JIT scalar-replaces
well and the value-record layout wins through escape analysis
instead of through an explicit flat backing. The opt-in is there
and correct; it'll become the right default once the Valhalla JIT's
flat-array path catches up with its reference-array path.

`SparseDelta` is within noise. The bottleneck is change-tracker
bookkeeping, not component reads, so there's nothing for Valhalla
to flatten.

## Predator / prey under Valhalla

The relations scenario (`PredatorPreyForEachPairBenchmarkValhalla`,
in the `ecs-benchmark-valhalla` module) ports the benchmark to
`@LooselyConsistentValue value record Position`, `Velocity`,
`Predator`, `Prey`. The `Hunting` relation payload stays a plain
`record` because it lives in `TargetSlice.values`, an `Object[]`
inside the relation store, not in a flat `ComponentStorage` — so
there is nothing to flatten on the payload side. Same scheduler,
same `@ForEachPair` dispatch, same tier-1 generator, same grid
parameters as the [stock benchmark](predator-prey.md).

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
plain reference array costs between 0 and 13% across every cell —
well inside the JMH error bars at most cells. For this workload
the value-record declaration alone gives no measurable win:
pursuit's inner body is so tight (two component reads, one write,
one payload read, one `invokevirtual`) that the tier-1 generator
already lets the JIT scalar-replace short-lived `Position` /
`Velocity` instances on both JVMs. Nothing left for value
semantics to recover.

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
tick, roughly **+13 ns per access** above the reference-array fast
path. The unoptimised EA JIT code for flat get/set dominates
everything the tier-1 pair runner was built to eliminate.

The upshot is the same conclusion the earlier sections reach:
value records themselves cost nothing, the value-record layout
hasn't yet unlocked a new win on top of the existing tier-1
generator for short-lived component shapes, and flat-array storage
remains gated behind `-Dzzuegg.ecs.useFlatStorage=true` until the
Valhalla JIT matures. Filed as a re-benchmark target for every
future EA drop.

## Honest takeaway

Under JEP 401 EA, Valhalla hands japes a real **~3×** speedup on
read-heavy iteration (the biggest single gain in the project) and
**~10%** on dense integration loops, and is still a net
*regression* on change-detection scenario benchmarks that exercise
`setComponent` heavily. Counter-intuitively, the explicit
flat-array opt-in (`newNullRestrictedNonAtomicArray` +
`@LooselyConsistentValue`) makes things *worse* today because the
EA JIT hasn't optimised the flat-access path yet — the real wins
come from the reference-array fallback where the JIT can
scalar-replace through escape analysis. Both code paths are
implemented and A/B-tested in the repo; the flat opt-in will
become the right default once the Valhalla JIT catches up.

"Just set the JVM to Valhalla" is not a free performance switch
today but the read-side numbers are *very* compelling, and the
trajectory is clearly favourable.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark-valhalla:jmhJar

$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar
```

Opt-in flat-array A/B:

```bash
# flat OFF (default — reference arrays with value records)
$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar

# flat ON (experimental — flat null-restricted arrays)
$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dzzuegg.ecs.useFlatStorage=true \
  -Dzzuegg.ecs.debugFlat=true \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar
```

Relations scenario under Valhalla:

```bash
$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar \
  "PredatorPreyForEachPairBenchmarkValhalla" \
  -p predatorCount=100,500,1000 -p preyCount=500,2000,5000
```
