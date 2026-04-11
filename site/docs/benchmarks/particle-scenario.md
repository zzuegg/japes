# Particle scenario

Move + damage + reap + respawn + stats — the full end-to-end
benchmark at 10 000 entities with ~1% per-tick turnover and five
systems wired through the scheduler. This is what real game-loop
code looks like.

!!! tip "What this workload measures"

    Five systems running every tick against 10 000 particles:

    - **`MoveSystem`** — `@Read Velocity` + `@Write Mut<Position>`
      across every entity.
    - **`DamageSystem`** — `@Write Mut<Health>` decrementing each
      particle's hit-points by a fixed amount.
    - **`ReapSystem`** — despawns particles whose health dropped to
      zero this tick (~100 per tick in the steady state).
    - **`RespawnSystem`** — an `@Exclusive` system using `Commands`
      to spawn replacements for the reaped entities, keeping the
      population stable.
    - **`StatsSystem`** — scans every live `Lifetime` entity per
      tick to recount `alive`, plus drains `RemovedComponents<Health>`
      in the `PostUpdate` stage.

    Five systems, full-scan, per-entity writes, structural edits via
    `Commands`. Every cost japes can pay shows up in this one row.

## Results

Numbers are µs/op, lower is better. Copied verbatim from `DEEP_DIVE.md`.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| `tick`    |       10000 | 22.4 |   **161** |  1859 |     68.3 |    98.2 |

## Analysis

!!! note "The StatsSystem fairness fix"

    A [cross-library audit](methodology.md) caught that japes's
    `StatsSystem` was reusing the previous-tick `alive` count
    instead of re-computing it — every other library scans 10 000
    `Lifetime` entities per tick to count alive, japes was skipping
    the scan. Fixed in the current number above; japes now does the
    same work the others do (about +8% tick time vs the buggy
    version).

    Impact on the rows: japes ParticleScenario went from 149 →
    **161 µs/op** (+8%). japes-v (Valhalla) went from 169 →
    **180 µs/op** (+6.5%). Both still dominate Zay-ES and still lose
    to Dominion / Artemis on this benchmark — the ordering is stable,
    the magnitude is now honest.

**Why japes loses to Dominion / Artemis here.** `MoveSystem`,
`DamageSystem` and `StatsSystem` all iterate every entity, and japes's
immutable-record writes allocate per entity. Five-system full-scan
scenarios are exactly where the [write-path
tax](iteration-micros.md#the-write-path-tax) bites hardest; the
Dominion / Artemis mutable-POJO path has no equivalent cost.

**What the fix to `Archetype.findOrCreateChunkIndex` bought us.** The
O(n) linear scan replaced with an O(1) `openChunkIndex` helped this
benchmark specifically (it respawns ~100 entities per tick) — japes
dropped from ~157 to **149 µs/op** under the PR fixes, then gained
back ~8% when the `StatsSystem` fairness fix forced the per-tick
alive scan to run for real.

**Cross-library summary.**

| library | µs/op | cost model |
|---|---:|---|
| Bevy     |  22.4 | in-place float writes, Rust SIMD-friendly loops |
| **japes**| **161** | immutable-record writes, change-tracker maintained, five-system scheduler |
| Dominion |  68.3 | mutable POJO writes, no change tracking |
| Artemis  |  98.2 | mutable POJO writes, no change tracking |
| Zay-ES   |  1859 | immutable components, per-set `applyChanges()` cost dominates |

## Valhalla delta

From section 8 of `DEEP_DIVE.md`:

| benchmark               |  case | **japes** | **japes-v** | Δ          |
|-------------------------|------:|----------:|------------:|-----------:|
| `ParticleScenario tick` |   10k |       161 |         180 | 0.89× slower |

Valhalla regresses this benchmark by 12% (was 14% before the PR's
`ArchetypeGraph` fix trimmed it). GC profiling shows Valhalla
allocating ~2× more per op on the scenario benchmarks than stock
japes; the residual regression comes from value records crossing
the erased `Record` parameter of `World.setComponent`, which forces
the JVM to box the value into a heap wrapper even though the
storage layer is value-aware. See the [Valhalla page](valhalla.md)
for the full story.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "ParticleScenarioBenchmark" \
  -p entityCount=10000
```
