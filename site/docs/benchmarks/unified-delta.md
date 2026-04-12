---
title: Unified delta
---

# Unified delta benchmark

The unified delta workload tests a single logical observer reacting to added, changed, AND removed entities across three component types (State, Health, Mana) per tick. This is the shape Zay-ES's `EntitySet` is designed for; it exposes japes's per-component-type system registration overhead.

!!! info "What it measures"

    Per-tick driver: 10% mutations per component type (30% of entities touched total via offset rotating cursors), 1% spawn, 1% despawn, 2% component strip-and-restore (Mana removed then re-added next tick). The observer counts added, changed and removed events. Zay-ES does this with one `EntitySet.applyChanges()` call; japes uses multi-target `@Filter` (5 system registrations) or single-target (9 registrations).

## Results

| | **japes 5-system (multi-target @Filter)** | Zay-ES (1 EntitySet) |
|---|---:|---:|
| **10k entities** | **621 µs / 1.61 ops/ms** | 234 µs / 4.28 ops/ms |
| **100k entities** | **5,102 µs / 0.196 ops/ms** | 4,878 µs / 0.205 ops/ms |

**Zay-ES beats japes at both 10k (2.66×) and 100k (1.05×).** The SoA storage
default adds overhead to the unified delta workload because SoA decomposition
and recomposition costs are paid on every mutation. Zay-ES's single
`applyChanges()` call with object-level storage is a better fit for this
particular workload shape. japes is still competitive at 100k where the
dirty-list walk advantage narrows the gap.

## The optimization journey

See the [full optimization log](../deep-dive/unified-delta-optimization.md) for the step-by-step story of how multi-target `@Filter` went from tier-2 (20% regression) to tier-1 with zero-allocation helpers (6% faster than the workaround).

## What the japes code looks like

```java
// One multi-target @Filter replaces three separate single-target systems
@System
@Filter(value = Changed.class, target = {State.class, Health.class, Mana.class})
void observeChanges(@Read State s, @Read Health h, @Read Mana m) {
    counters.changed++;
}

@System
@Filter(value = Added.class, target = {State.class, Health.class, Mana.class})
void observeAdded(@Read State s, @Read Health h, @Read Mana m) {
    counters.added++;
}

// RemovedComponents is still per-type (3 registrations)
@System
void removedState(RemovedComponents<State> gone) {
    for (var r : gone) counters.removedState++;
}
```

## Reproducing

```bash
# japes (multi-target @Filter, 5 systems)
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-*-jmh.jar \
  "UnifiedDeltaBenchmark" \
  -p entityCount=10000,100000

# Zay-ES (1 EntitySet)
./gradlew :benchmark:ecs-benchmark-zayes:jmhJar
java -jar benchmark/ecs-benchmark-zayes/build/libs/ecs-benchmark-zayes-*-jmh.jar \
  "ZayEsUnifiedDeltaBenchmark" \
  -p entityCount=10000,100000
```
