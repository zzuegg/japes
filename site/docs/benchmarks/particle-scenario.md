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
| `tick`    |       10000 | 22.7 |  **41.8** |  1855 |     67.9 |    98.5 |

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
    161 → 107 → **41.8 µs/op** after SoA storage became the default.
    japes-v (Valhalla) went from 169 → **180 µs/op** (+6.5%) on the
    pre-SoA sweep. japes now beats Dominion (67.9) and Artemis (98.5)
    on this benchmark and lands within 1.84× of Bevy.

**With SoA storage, japes now beats every Java library on this benchmark.**
The `MoveSystem` and `DamageSystem` writes that previously allocated a new
record per entity are now scalar-replaced by the JIT — the SoA backing
arrays receive primitive `fastore` / `iastore` instructions directly. japes
at 41.8 µs beats Dominion (67.9) and Artemis (98.5) while still maintaining
automatic change tracking that neither of those libraries provides.

**Cross-library summary.**

| library | µs/op | cost model |
|---|---:|---|
| Bevy     |  22.7 | in-place float writes, Rust SIMD-friendly loops |
| **japes**| **41.8** | SoA writes, change-tracker maintained, five-system scheduler |
| Dominion |  67.9 | mutable POJO writes, no change tracking |
| Artemis  |  98.5 | mutable POJO writes, no change tracking |
| Zay-ES   |  1855 | immutable components, per-set `applyChanges()` cost dominates |

## Valhalla delta

From section 8 of `DEEP_DIVE.md`:

| benchmark               |  case | **japes** | **japes-v** | Δ          |
|-------------------------|------:|----------:|------------:|-----------:|
| `ParticleScenario tick` |   10k |      41.8 |         180 | — |

The Valhalla number (180 µs) is from the pre-SoA sweep and is **not
directly comparable** to the new stock number. A fresh Valhalla sweep
with SoA storage is pending. See the [Valhalla page](valhalla.md)
for the previous breakdown.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "ParticleScenarioBenchmark" \
  -p entityCount=10000
```
