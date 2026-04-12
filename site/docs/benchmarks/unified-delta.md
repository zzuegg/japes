---
title: Unified delta
---

# Unified delta benchmark

The unified delta workload tests a single logical observer reacting to added, changed, AND removed entities across three component types (State, Health, Mana) per tick. This is the shape Zay-ES's `EntitySet` is designed for; it exposes japes's per-component-type system registration overhead.

!!! info "What it measures"

    Per-tick driver: 3 mutator systems iterate all entities and write 10% each (30% of entities touched total), 1% spawn, 1% despawn, 2% component strip-and-restore (Mana removed then re-added next tick). 3 observer systems count added, changed and removed events via multi-target `@Filter`. Zay-ES does mutations via `EntitySet.applyChanges()` on the same workload shape.

## Results

| | **japes 6-system** | Zay-ES (1 EntitySet) |
|---|---:|---:|
| **10k entities** | 596 µs | **237 µs** |
| **100k entities** | **4,762 µs** | 5,025 µs |

**Zay-ES beats japes at 10k (2.52×) but japes wins at 100k (1.06×).** At 10k, Zay-ES's EntitySet dirty-set model only touches the ~30% of changed entities per tick, while japes mutator systems iterate all 10k entities and write 10% each. At 100k the sequential SoA iteration advantage overcomes the full-scan overhead. The observer systems use multi-target `@Filter` which walks the union of dirty lists — matching Zay-ES's delta-only approach for the read side.

## What the japes code looks like

```java
// Mutator systems iterate all entities, write 10% via Mut<T>
@System
void mutateState(@Write Mut<State> s) {
    if (counter++ % 10 == 0)
        s.set(new State(s.get().value() + 1));
}

// Multi-target @Filter observers react to changes across 3 component types
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
