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
| **10k entities** | 666 µs | **237 µs** |
| **100k entities** | 5,263 µs | **5,025 µs** |

**Zay-ES beats japes at 10k (2.81×) and is slightly faster at 100k (1.05×).** At 10k, Zay-ES's EntitySet dirty-set model only touches changed entities, while japes mutator systems iterate all entities to evaluate game-logic conditions (e.g., "is HP > 900?") and write only the ~10% that trigger. At 100k the gap narrows to noise as sequential SoA iteration scales well. The observer systems use multi-target `@Filter` which walks only dirty lists — matching Zay-ES's delta-only approach for the read side.

## What the japes code looks like

```java
// Damage-over-time: entities above 900 HP take 1 damage per tick
@System
void damageOverTime(@Write Mut<Health> h) {
    if (h.get().hp() > 900) h.set(new Health(h.get().hp() - 1));
}

// Mana regeneration: entities below 100 mana regen 1 per tick
@System
void manaRegen(@Write Mut<Mana> m) {
    if (m.get().points() < 100) m.set(new Mana(m.get().points() + 1));
}

// Multi-target @Filter observers react to changes across 3 component types
@System
@Filter(value = Changed.class, target = {State.class, Health.class, Mana.class})
void observeChanges(@Read State s, @Read Health h, @Read Mana m) {
    counters.changed++;
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
