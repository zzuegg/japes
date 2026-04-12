# Benchmarks

All numbers from a single co-temporal sweep on the same machine (JDK 26, Bevy 0.15 via Criterion, single fork, 5 × 2s iterations). Lower is better.

## Results at a glance

### Iteration micros (µs/op)

| Benchmark | Entities | **japes** | Bevy (Rust) | Zay-ES | Dominion | Artemis |
|---|---:|---:|---:|---:|---:|---:|
| [iterateSingleComponent](iteration-micros.md) | 10k | **2.94** | **2.18** | 28.6 | 7.06 | 4.52 |
| [iterateTwoComponents](iteration-micros.md) | 10k | **5.92** | **3.70** | 38.6 | 12.4 | 11.6 |
| [iterateWithWrite](iteration-micros.md) | 10k | **1.70** | 6.29 | 1829 | 22.5 | 18.3 |
| [iterateSingleComponent](iteration-micros.md) | 100k | **31.6** | **21.3** | 383 | 79.4 | 166 |
| [iterateWithWrite](iteration-micros.md) | 100k | **26.0** | 63.8 | 17857 | **234** | 335 |

*japes iteration numbers use field-level blackhole; Bevy uses object-level `black_box`. Read rows are not directly comparable across languages. See [iteration micros methodology](iteration-micros.md).*

### Scenarios (µs/op)

| Benchmark | Entities | **japes** | Bevy | Zay-ES | Dominion | Artemis |
|---|---:|---:|---:|---:|---:|---:|
| [N-Body oneTick](nbody.md) | 10k | **2** | 8.8 | 441 | 24 | 19 |
| [ParticleScenario](particle-scenario.md) | 10k | **41.8** | **22.7** | 1855 | 67.9 | 98.5 |
| [SparseDelta](sparse-delta.md) | 10k | **2.16** | 4.11 | 4.67 | **0.37** | **0.27** |
| [RealisticTick](realistic-tick.md) | 10k st | **6.94** | 8.81 | 15.4 | 44.6 | 24.5 |
| [RealisticTick](realistic-tick.md) | 100k st | **12.7** | 76.9 | **19.6** | 389 | 279 |

### Relations (µs/op, japes + Bevy only)

| Benchmark | Cell | **japes** | Bevy naive | Bevy hand-rolled |
|---|---|---:|---:|---:|
| [PredatorPrey `@Pair`](predator-prey.md) | 500×2000 | 41.0 | 243.7 | **11.5** |
| [PredatorPrey `@ForEachPair`](predator-prey.md) | 500×2000 | **27.6** | 243.7 | **11.5** |

### Unified delta (ops/ms, higher is better)

| Benchmark | Entities | **japes** (5 systems) | Zay-ES (1 EntitySet) |
|---|---:|---:|---:|
| [UnifiedDelta](unified-delta.md) | 10k | 1.61 | **4.28** |
| [UnifiedDelta](unified-delta.md) | 100k | 0.196 | **0.205** |

## Where japes wins

- **Raw writes** — `iterateWithWrite 10k`: **3.7× faster than Bevy**. SoA storage lets the JIT scalar-replace the `new Position(...)` record — field values go directly into primitive backing arrays with zero heap allocation. See [One JIT to rule them all](../deep-dive/one-jit-to-rule-them-all.md).
- **Dense integration** — `NBody 10k`: **4.4× faster than Bevy**. Same SoA write-path benefit on a full-entity-scan workload.
- **Change detection** — `SparseDelta`: **1.90× faster than Bevy**, because dirty-list walks scale with dirty count, not total entities.
- **Multi-observer scaling** — `RealisticTick 100k`: **6.06× faster than Bevy**, same reason at 10× scale. Also beats every Java library at both 10k and 100k.
- **Relations vs naive Bevy** — `PredatorPrey @ForEachPair`: **8.8× faster** than Bevy's naive `Component<Entity>` pattern at 500×2000 because the reverse index is built-in.
- **Particle scenario** — `ParticleScenario 10k`: **1.84× slower than Bevy** but now beats every Java library (Dominion 67.9, Artemis 98.5).

## Where japes loses

- **Read micros** — `iterateSingleComponent 10k`: **1.35× slower than Bevy**. japes uses field-level blackhole while Bevy uses object-level `black_box`, so read rows are not directly comparable. Even so, the gap is small.
- **Hand-rolled Bevy reverse-index** — `PredatorPrey optimized`: Bevy wins **2.4×** because it skips per-pair change tracking, Commands, archetype markers, and RemovedRelations.
- **Unified delta** — `UnifiedDelta 10k`: **2.66× slower than Zay-ES**. SoA decomposition costs on every mutation hurt this multi-component mutation workload.

## Speed-up matrix vs Bevy

Lower is better. `1.0×` matches Bevy; below `1.0×` means japes is faster.

| Benchmark | Case | **japes** | Zay-ES | Dominion | Artemis |
|---|---:|---:|---:|---:|---:|
| iterateSingleComponent | 10k | 1.35× | 13.1× | 3.2× | 2.1× |
| iterateTwoComponents | 10k | 1.60× | 10.4× | 3.4× | 3.1× |
| iterateWithWrite | 10k | **0.27×** | 291× | 3.6× | 2.9× |
| NBody simulateOneTick | 10k | **0.23×** | 50× | 2.7× | 2.2× |
| ParticleScenario | 10k | 1.84× | 82× | 3.0× | 4.3× |
| SparseDelta | 10k | **0.53×** | 1.1× | **0.09×** | **0.07×** |
| RealisticTick | 10k | **0.79×** | 1.8× | 5.1× | 2.8× |
| RealisticTick | 100k | **0.17×** | 0.25× | 5.1× | 3.6× |

*japes iteration micro ratios use field-level blackhole; Bevy uses object-level `black_box`. Read-row ratios are not apples-to-apples across languages.*

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
