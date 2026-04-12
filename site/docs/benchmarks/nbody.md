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
| `simulateOneTick`  |      1000 | 0.88 |    **4** |  43.6 |     2.44 |    1.84 |
| `simulateOneTick`  |     10000 | 8.79 |   **41** |   440 |     24.1 |    19.2 |
| `simulateTenTicks` |      1000 | 8.92 |   **40** |   436 |     24.5 |    18.5 |
| `simulateTenTicks` |     10000 | 88.2 |   **403** |  4437 |      239 |     193 |

## Analysis

Same shape as the write-path iteration benchmark — the integrator
allocates a new `Position` record per body per tick. Dominion's and
Artemis's in-place floats mean the JIT can keep the whole loop in
SIMD-ish registers.

!!! note "Record allocation dominates this benchmark"

    japes is paying the same [write-path
    tax](iteration-micros.md#the-write-path-tax) documented on the
    iteration micros page. Look at the `simulateTenTicks / 10000` row
    — japes at 403 µs is almost exactly 10× the `simulateOneTick /
    10000` number (41 µs), which confirms that per-tick cost is
    stable and dominated by the integrator body, not by scheduler
    one-shot overhead.

## Valhalla delta

From section 8 of `DEEP_DIVE.md`:

| benchmark                 | case | **japes** | **japes-v** | Δ              |
|---------------------------|-----:|----------:|------------:|---------------:|
| NBody `simulateOneTick`   |   1k |         4 |        5.84 | **0.68×** real |
| NBody `simulateOneTick`   |  10k |        41 |        57.3 | **0.72×** real |
| NBody `simulateTenTicks`  |  10k |       403 |         577 | **0.70×** real |

Stock japes is now faster than Valhalla on this benchmark. Writes still allocate
`new Position(...)` (either a flat value or a heap record depending
on the JVM), and the store into the backing array has the same cost
either way, so there's less for Valhalla to optimise than on the
read-heavy iteration micros (which gain 2–4×). The [Valhalla
page](valhalla.md) has the full read/write/scenario breakdown.

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
