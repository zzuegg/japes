---
title: Unified delta
---

# Unified delta benchmark

The unified delta workload tests a single logical observer reacting to added, changed, AND removed entities across three component types (State, Health, Mana) per tick. This is the shape Zay-ES's `EntitySet` is designed for; it exposes japes's per-component-type system registration overhead.

!!! info "What it measures"

    Per-tick driver: 3 mutator systems iterate all entities and write 10% each (30% of entities touched total), 1% spawn, 1% despawn, 2% component strip-and-restore (Mana removed then re-added next tick). 3 observer systems count added, changed and removed events via multi-target `@Filter`. Zay-ES does mutations via `EntitySet.applyChanges()` on the same workload shape.

## Results

| | **japes** (6 systems) | Zay-ES (1 EntitySet) |
|---|---:|---:|
| **10k entities** | **660 µs** | 1,067 µs |
| **100k entities** | **4,854 µs** | 13,889 µs |

**japes beats Zay-ES at both 10k (1.62×) and 100k (2.72×).** Both frameworks iterate all entities per component type, evaluate a game-logic condition (HP > 900, mana < 100, state % 10 == 0), and write only the ~10% that trigger. japes mutator systems read component values through tier-1 SoA primitive array loads; Zay-ES goes through `data.getComponent()` per entity (HashMap lookup). The SoA advantage grows with entity count because sequential array access scales better than per-entity HashMap probes.

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
