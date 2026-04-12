# N-body integration

Full world tick with a single integrate system, `dt` supplied via
`Res<T>` (japes) or a world-level field (others).

!!! tip "What this workload measures"

    Euler integration over `{Position, Velocity}`:

    - **Start state.** Bodies are angle-distributed around a circle
      with unit velocities pointing outward — the same deterministic
      seed across every library so every run measures the same work.
    - **Tick body.** `pos += vel * dt` per body. That's it.
    - **Shapes.** `simulateOneTick` runs a single `world.tick()`;
      `simulateTenTicks` runs ten. The latter amortises one-shot
      scheduler overhead across ten iterations and is the fairer
      measure of steady-state throughput.

    This is **not** a pairwise gravitational N-body simulation. The
    `NBodyBenchmark` Javadoc calls this out explicitly; don't compare
    against external astrophysics N-body benchmarks.

## Results

Numbers are µs/op, lower is better. Copied verbatim from `DEEP_DIVE.md`.

| benchmark          | bodyCount | bevy | **japes** | zayes | dominion | artemis |
|--------------------|----------:|-----:|----------:|------:|---------:|--------:|
| `simulateOneTick`  |      1000 | 0.9 |  **0.1** |  44 |     2 |    2 |
| `simulateOneTick`  |     10000 | 8.8 |    **2** |   441 |     24 |    19 |
| `simulateTenTicks` |      1000 | 8.9 |    **1** |   443 |     25 |    19 |
| `simulateTenTicks` |     10000 | 89 |    **17** |  4454 |      239 |     191 |

## Analysis

With SoA storage as the default, the integrator's `new Position(...)` write
is scalar-replaced by the JIT — the record fields decompose into primitive
`fastore` instructions on the backing `float[]` arrays. japes now **beats
Bevy at every cell** on this benchmark: 2 µs vs 8.8 µs at 10k (4.4× faster),
and 17 µs vs 89 µs at 10k ten-tick (5.2× faster).

!!! note "SoA eliminated the write-path tax on this benchmark"

    The previous numbers (41 µs at 10k) were dominated by record allocation
    into `Object[]` storage. With SoA, the `new Position(...)` is
    scalar-replaced — the JIT decomposes it into three float stores.
    `simulateTenTicks / 10000` at 17 µs is almost exactly 10× the
    `simulateOneTick / 10000` number (2 µs), confirming per-tick cost is
    stable and dominated by the integrator body, not by scheduler
    one-shot overhead.

## Valhalla delta

From section 8 of `DEEP_DIVE.md`:

| benchmark                 | case | **japes** | **japes-v** | Δ              |
|---------------------------|-----:|----------:|------------:|---------------:|
| NBody `simulateOneTick`   |   1k |       0.1 |        5.84 | — |
| NBody `simulateOneTick`   |  10k |         2 |        57.3 | — |
| NBody `simulateTenTicks`  |  10k |        17 |         577 | — |

With SoA storage, stock japes is dramatically faster than both the previous
stock numbers and Valhalla on this benchmark. The SoA decomposition lets the
JIT scalar-replace the `new Position(...)` record entirely — something
Valhalla's value classes also target but with different trade-offs. The
Valhalla numbers above are from the pre-SoA sweep and are **not directly
comparable** to the new stock numbers; a fresh Valhalla sweep with SoA is
pending. The [Valhalla page](valhalla.md) has the previous read/write/scenario
breakdown.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "NBodyBenchmark"
```

Pinning a single cell:

```bash
java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "NBodyBenchmark.simulateOneTick" \
  -p bodyCount=10000
```
