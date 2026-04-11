# Benchmarks

japes ships a cross-library JMH benchmark sweep covering tight iteration
micros, scenario workloads, change-detection sparsity and first-class
relations. Every number on these pages is copy-pasted verbatim from the
project-root [`DEEP_DIVE.md`](https://github.com/zzuegg/japes/blob/main/DEEP_DIVE.md)
run sheet so the tables here never drift from the raw JMH logs.

Start with the [methodology](methodology.md) page if you want to know
how each row is measured; jump straight into any of the per-benchmark
pages below if you already know which workload you care about.

## Benchmark pages

<div class="japes-card-grid" markdown>

<div class="japes-card" markdown>
### [Methodology](methodology.md)
Hardware, JMH configuration, stock JDK 26 vs Valhalla JDK 27 EA, the
9-cell parameter grid convention, and the `@Setup` / `@Benchmark`
contract each library follows.
</div>

<div class="japes-card" markdown>
### [Iteration micros](iteration-micros.md)
`iterateSingleComponent`, `iterateTwoComponents`, `iterateWithWrite`
at 1k / 10k / 100k entities. Pure per-entity read / write cost — the
baseline every other scenario is built on top of.
</div>

<div class="japes-card" markdown>
### [N-body integration](nbody.md)
One integrator system over `{Position, Velocity}`, Euler step with
`dt` supplied via `Res<T>`. One-tick and ten-tick variants at 1k and
10k bodies.
</div>

<div class="japes-card" markdown>
### [Particle scenario](particle-scenario.md)
Move / damage / reap / respawn / stats — the full five-system
game-loop shape at 10 000 entities with ~1% per-tick turnover.
</div>

<div class="japes-card" markdown>
### [Sparse delta](sparse-delta.md)
The canonical change-detection workload: 10 000 entities, 100 dirty
per tick, one observer reacting to `@Filter(Changed)`. japes's
strongest cross-library showing.
</div>

<div class="japes-card" markdown>
### [Realistic multi-observer tick](realistic-tick.md)
10k and 100k entities, three `@Filter(Changed)` observers on disjoint
components, single-threaded and multi-threaded executors. The
cost-model comparison between dirty-list-skip and full-archetype-scan
libraries.
</div>

<div class="japes-card" markdown>
### [Predator / prey relations](predator-prey.md)
9-cell grid (predators × prey) exercising `@Pair`, `@ForEachPair`,
`RELEASE_TARGET` cleanup and `RemovedRelations<T>` — vs Bevy naive
and hand-rolled Bevy reverse-index implementations.
</div>

<div class="japes-card" markdown>
### [Valhalla EA](valhalla.md)
JDK 27 EA with JEP 401 value records on every benchmark. Reads gain
2–4×, writes gain ~10%, scenario benchmarks regress, explicit
flat-array opt-in is currently worse than the reference-array
fallback.
</div>

</div>

## Headline numbers

Summary row per benchmark, stock JDK 26. Lower is better.

| benchmark                                    | entity count | japes µs/op | Bevy µs/op | notes |
|----------------------------------------------|-------------:|------------:|-----------:|---|
| `iterateSingleComponent`                     |         10k  |        2.36 |       2.11 | fastest JVM ECS in this comparison |
| `iterateTwoComponents`                       |         10k  |        4.22 |       3.35 | within 1.3× of Bevy |
| `iterateWithWrite`                           |         10k  |        57.4 |       6.18 | write-path tax visible |
| `simulateOneTick` (N-body)                   |         10k  |        62.5 |       8.79 | record allocation tax |
| `ParticleScenario tick`                      |         10k  |         161 |       22.4 | five-system full scan |
| `SparseDelta tick`                           |         10k  |        1.85 |       4.01 | **2.17× faster than Bevy** |
| `RealisticTick` st                           |         10k  |        5.82 |       8.42 | **1.45× faster than Bevy** |
| `RealisticTick` st                           |        100k  |        7.76 |       73.4 | **9.45× faster than Bevy** |
| `PredatorPrey @ForEachPair` 500 × 2000       |            — |        32.0 |       11.19 (opt) / 261.9 (naive) | first-class relations |

## Speed-up matrix vs. Bevy

From section 10 of `DEEP_DIVE.md`. Lower is better; `1.0×` matches
Bevy; anything below `1.0×` means japes beat the Rust reference on
that workload.

| benchmark                   |         case |  **japes** | **japes-v** | zayes |  dominion | artemis |
|-----------------------------|-------------:|-----------:|------------:|------:|----------:|--------:|
| `iterateSingleComponent`    |          10k |   **1.1×** |  **0.50×**  | 13.9× |     3.3×  |   2.2×  |
| `iterateTwoComponents`      |          10k |   **1.3×** |  **0.55×**  | 10.9× |     3.7×  |   3.5×  |
| `iterateWithWrite`          |          10k |   **9.3×** |    **8.6×** |  277× |     3.7×  |   3.0×  |
| NBody `simulateOneTick`     |          10k |   **7.1×** |    **6.5×** |   50× |     2.7×  |   2.2×  |
| `ParticleScenario tick`     |          10k |   **7.2×** |        8.0× |   83× |     3.0×  |   4.4×  |
| `SparseDelta tick`          |          10k |  **0.46×** |   **0.49×** |  1.2× |  **0.09×**| **0.06×**|

!!! note "Cross-library claim summary"

    - **SparseDelta:** japes 1.85 µs vs Bevy 4.01 µs — **2.17× faster**
      than the Rust reference on the library change-detection path.
    - **Valhalla reads:** stock japes at 10k `iterateSingleComponent` is
      1.1× Bevy; Valhalla (value records, reference arrays) drops that
      to 0.50× — japes beats Bevy on reads under the EA JVM.
    - **Write-path tax:** on naked-write micros japes pays the
      immutable-record allocation tax that Dominion and Artemis avoid
      by mutating POJOs in place. See the [write-path tax
      section](iteration-micros.md#the-write-path-tax) for the
      fairness discussion.
    - **Predator / prey:** japes `@ForEachPair` beats Bevy naive
      reverse-lookup at every cell (up to 12.9× at 1000 × 5000),
      and sits within 2.8–3.7× of hand-rolled optimised Bevy that
      maintains its own reverse index manually.

## How to read these pages

Each benchmark page answers the same four questions:

1. **What does this workload measure?** — a short admonition at the
   top with the per-tick shape and which feature it stresses.
2. **Results.** — the raw JMH table, numbers copied directly from
   `DEEP_DIVE.md`, with every library column.
3. **Per-cell analysis.** — cost-model breakdown, cross-library
   differences, and any fairness caveats.
4. **Reproducing.** — the exact `gradle` + `java -jar …-jmh.jar`
   invocation to re-run just that cell on your own hardware.

If you land here via search and only need one number, the table on
each page is all you need; the surrounding prose exists for readers
trying to understand *why* the numbers look the way they do.
