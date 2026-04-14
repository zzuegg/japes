# Benchmarks

<div id="bench-loading">Loading benchmark data...</div>

## Results at a glance

### Iteration micros (µs/op)

<div id="table-iteration"></div>

### Scenarios (µs/op)

<div id="table-scenarios"></div>

### Relations (µs/op)

<div id="table-relations"></div>

### Unified delta (µs/op)

<div id="table-unified"></div>

### Allocation per tick (B/op, japes only)

<div id="table-allocation"></div>

## Detailed benchmark pages

| Page | What it measures |
|---|---|
| [Methodology](methodology.md) | Hardware, JMH config, the `@Setup` / `@Benchmark` contract |
| [Iteration micros](iteration-micros.md) | Pure per-entity read/write cost at 1k / 10k / 100k |
| [N-Body](nbody.md) | One integrator system, Euler step, 1k + 10k bodies |
| [Particle scenario](particle-scenario.md) | Five-system game loop with 1% per-tick turnover |
| [Sparse delta](sparse-delta.md) | Change-detection: 100 dirty per tick, 1 observer |
| [Realistic tick](realistic-tick.md) | Three observers, 10k/100k, st/mt |
| [Predator / prey](predator-prey.md) | Relations: `@Pair` vs `@ForEachPair` vs Bevy |
| [Unified delta](unified-delta.md) | Multi-target `@Filter` vs Zay-ES EntitySet |
| [Valhalla EA](valhalla.md) | JDK 27 JEP 401 value records |

<script src="../js/benchmark-tables.js"></script>
