# Methodology

All numbers in the benchmarks section are µs per benchmark op —
**lower is better**. Each Java library is tested in its own idiomatic
shape (see the per-library benchmark source for what that means);
Bevy is the Rust reference. Same workload across every column.

## Hardware and JMH configuration

Single workstation, single-threaded unless noted. Every benchmark is
annotated with:

- `@Fork = 2`
- `@Warmup = 3 × 2s`
- `@Measurement = 5 × 2s`

Stock numbers are **JDK 26** with `--enable-preview`. Valhalla numbers
use an EA build of **JDK 27** (`openjdk 27-jep401ea3`) with JEP 401
preview. Treat absolute values as a point-in-time snapshot — relative
ordering across libraries is what matters.

!!! tip "Why a 9-cell grid"

    Several benchmarks (iteration micros, predator/prey) sweep two
    parameters across three values each. The grid shape lets a single
    run expose how the measured cost scales with each axis
    independently — without a grid the reader can't distinguish
    "O(N) with small constant" from "O(N²) with tiny constant" when
    the headline cell is the small end.

## Stock vs. Valhalla

- **Stock japes** uses ordinary `record` component declarations on
  JDK 26. The backing storage is a reference array; reads are a
  pointer-chase, writes allocate a fresh heap record.
- **japes-v** (Valhalla) uses `value record` components with
  `@LooselyConsistentValue` on JDK 27 EA. The same Java source
  targets the `ecs-benchmark-valhalla` module; only the component
  declaration and the runtime JVM differ.

Both runs use the same JMH settings, the same tier-1 generator and
the same scheduler configuration. The difference in numbers is
attributable to the JVM + component declaration, nothing else. See
the [Valhalla page](valhalla.md) for the read/write/scenario
breakdown and the flat-array opt-in A/B.

## How measurements are taken

Every read-side iteration benchmark consumes the loaded component
through a JMH `Blackhole` — on japes / Zay-ES / Dominion via
`bh.consume(pos)`, on Artemis via the static-field `Blackhole`
pattern, and on Bevy via `std::hint::black_box(pos)`. This is not
optional: an earlier revision of the japes iteration micros used
empty system bodies (`void iterate(@Read Position p) {}`), which let
the JIT escape-analyse the loaded record and delete the whole
iteration loop. That is where the historical "20–51× Valhalla
speedup" artifact came from. Every `@Read` row in the current tables
defeats that path.

Write-side benchmarks do not need a `Blackhole` — the write is
observable externally via the next tick's iteration — but they do
need to be fair. japes and Zay-ES allocate a new component record
per mutation; Bevy / Dominion / Artemis mutate primitive fields in
place. That asymmetry is kept, not erased, because it reflects the
real user-visible API each library offers. See the [write-path tax
section](iteration-micros.md#the-write-path-tax) for how to read
those numbers.

## What changed in this sweep

The tables below were re-taken after a third-party code review
landed a batch of correctness, thread-safety and benchmark-fairness
fixes (PR [#1](https://github.com/zzuegg/japes/pull/1)):

- **`ChangeTracker.swapRemove` dirty-bit propagation.** Real silent
  correctness bug. Entities that were dirty at the moment another
  entity was swap-removed became invisible to every
  `@Filter(Changed/Added)` observer. Fixed; all observer benchmarks
  now see every mutation. The measured numbers barely move because
  the benchmarks didn't combine despawns with mutations in the same
  archetype, but the bug was there.
- **`ArchetypeGraph.findMatchingCache` / `ComponentRegistry`
  `ConcurrentHashMap`.** Fixes two real races under
  `MultiThreadedExecutor`. The `RealisticTick mt` row is now
  deterministic across runs; the previous noise floor was partially
  due to map corruption under contention.
- **`Archetype.findOrCreateChunkIndex`.** O(n) linear scan replaced
  with an O(1) `openChunkIndex`. Helped `ParticleScenarioBenchmark`
  (which respawns ~100 entities per tick) drop from ~157 to
  **149 µs/op**.
- **`ChangeDetectionBenchmark.removedComponentsDrainAfterBulkDespawn`
  fairness.** The old measurement body included re-spawn + second
  tick, charging ~2× the work. Restructured to match the Zay-ES
  counterpart exactly. New number: **372 µs/op** for 10k entities
  drained through the removal log.
- **`SparseDeltaBenchmark` javadoc.** Adds an explicit fairness note
  that the japes benchmark body includes full `world.tick()` overhead
  (event swap, stage traversal, dirty-list pruning) while the
  Artemis / Dominion counterparts hand-roll a tight loop without any
  of it. Material at only 100 dirty entities; the japes number still
  drops because the PR's fixes reduce that tick overhead.
- **`NBodyBenchmark` Javadoc + `@TearDown`.** Clarifies that this is
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

## Reproducing

Full sweep, stock JDK 26:

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

Valhalla sweep (needs `VALHALLA_HOME` or the default
`~/.sdkman/candidates/java/valhalla-ea` path, plus JEP 401 preview):

```bash
./gradlew :benchmark:ecs-benchmark-valhalla:jmhJar

$VALHALLA_HOME/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar
```

Opt-in experiments available via system properties:

```bash
-Dzzuegg.ecs.useFlatStorage=true    # enable JEP 401 flat arrays
-Dzzuegg.ecs.debugFlat=true         # log per-storage flat/non-flat
```

Bevy (Rust) reference:

```bash
cd benchmark/bevy-benchmark
cargo bench
```

Each per-benchmark page repeats the relevant single-cell invocation
in its own *Reproducing* section at the bottom, so you can re-run
just one row without paying for the whole sweep.
