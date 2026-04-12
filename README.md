# japes

**J**ava **A**rchetype-based **P**arallel **E**ntity **S**ystem — a
high-throughput ECS library for the JVM with first-class change
detection and a tier-1 bytecode generator that turns system methods
into direct-dispatch hidden classes.

Bevy-style ergonomics in Java: archetype + chunk storage, systems as
annotated methods, parameters declared by type (`@Read C`, `@Write
Mut<C>`, `Res<R>`, `Commands`, `Entity`, …), and a DAG scheduler that
parallelises disjoint systems automatically.

> Pre-release. API is stable enough to benchmark but reserves the
> right to change before 1.0.

**📖 [Full documentation site](https://zzuegg.github.io/japes/)** — 22-chapter tutorial, per-benchmark deep dive, design notes, and `@Pair` / `@ForEachPair` relations walkthrough.

## Contents

| Section | Description |
|---|---|
| [Quick start](#quick-start) | Minimum world + system in a dozen lines |
| [Change detection](#change-detection) | `@Filter(Changed)` + `RemovedComponents<T>` |
| [Deferred structural edits](#deferred-structural-edits) | `Commands` buffers for spawn/despawn/insert/remove |
| [Relations](#relations) | First-class `@Pair(T)` / `@ForEachPair(T)` entity relationships with forward + reverse indices and tier-1 bytecode-gen dispatch |
| [Headline benchmark](#headline-benchmark) | One-line cross-library comparison |
| [Build](#build) | JDK 26, Gradle wrapper, running benchmarks |
| [Project layout](#project-layout) | What's in each module |

**Other docs**
| Document | For who |
|---|---|
| [TUTORIAL.md](TUTORIAL.md) | Step-by-step walkthrough: components, systems, queries, filters, resources, events, commands, scheduling, multi-threading |
| [DEEP_DIVE.md](DEEP_DIVE.md) | Full benchmark tables, per-benchmark analysis, Valhalla investigation, API design rationale |
| [TIER_FALLBACKS.md](TIER_FALLBACKS.md) | When the tier-1 bytecode generators bail to the reflective fallback — per-generator `skipReason` catalog with one-line fixes |

## Quick start

```java
record Position(float x, float y, float z) {}
record Velocity(float dx, float dy, float dz) {}
record DeltaTime(float dt) {}

class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p, Res<DeltaTime> dt) {
        var cur = p.get();
        var d = dt.get().dt();
        p.set(new Position(cur.x() + v.dx()*d, cur.y() + v.dy()*d, cur.z() + v.dz()*d));
    }
}

var world = World.builder()
    .addResource(new DeltaTime(0.016f))
    .addSystem(Physics.class)
    .build();

world.spawn(new Position(0, 0, 0), new Velocity(1, 2, 3));
world.tick();
```

## Change detection

```java
class DeathObserver {
    @System
    @Filter(value = Changed.class, target = Health.class)
    void onHealthChanged(@Read Health h, Entity self) {
        // Runs once per entity whose Health changed this tick.
    }

    @System
    void onDead(RemovedComponents<Health> dead, ResMut<Stats> stats) {
        long n = 0;
        for (var e : dead) n++;
        stats.set(new Stats(stats.get().deaths() + n));
    }
}
```

Observer cost is proportional to the dirty set, not the archetype
size. For 100 changes out of 10 000 entities, the observer walks 100
entities. Both the tick counter and the dirty-entity list are
maintained automatically by `Mut.set()` and `world.setComponent()`.

## Deferred structural edits

```java
class Reaper {
    @System
    void reap(@Read Health h, Entity self, Commands cmds) {
        if (h.hp() <= 0) cmds.despawn(self);
    }
}
```

`Commands` buffers spawn/despawn/insert/remove calls so a parallel
stage can issue them without racing on the archetype graph. Buffers
flush at the stage boundary.

## Relations

Flecs-style first-class entity relationships. Annotate a record with
`@Relation`, set pairs via `world.setRelation` or `cmds.setRelation`,
query them with either dispatch shape. The library maintains
forward + reverse indices automatically, so a prey can ask "who is
hunting me?" in one primitive-keyed map probe instead of scanning
every predator — and despawn cleanup drops pairs on both sides with a
configurable `CleanupPolicy`.

Two dispatch shapes, both first-class:

**`@Pair(T.class)`** — *set-oriented*. Called once per entity that
carries ≥ 1 pair. The body walks the entity's pairs with
`PairReader.fromSource(self)` / `withTarget(self)`. Right default
when the system wants the *whole set* of pairs on one entity.

```java
@Relation
record Hunting(int focus) {}

@System @Pair(Hunting.class)
void steer(@Read Position p, Entity self, PairReader<Hunting> r, @Write Mut<Velocity> v) {
    for (var pair : r.fromSource(self)) { /* steer toward pair.target() */ }
}
```

**`@ForEachPair(T.class)`** — *tuple-oriented*. Called once per live
pair. Parameters bind directly to source-side components, target-side
components (opt-in with `@FromTarget`), and the relation payload. The
scheduler generates a tier-1 hidden class that walks the
`RelationStore` forward index directly and calls the user method via
`invokevirtual` — no walker, no per-pair allocation, no
`world.getComponent` probe.

```java
@System @ForEachPair(Hunting.class)
void pursuit(
        @Read Position sourcePos,
        @Write Mut<Velocity> sourceVel,
        @FromTarget @Read Position targetPos,
        Hunting hunting
) { /* ...steer sourceVel toward targetPos... */ }
```

Benchmarked against Bevy 0.15 on a 500-predator / 2000-prey
steady-state tick. Bevy has no generic relations primitive, so the
comparison is against two hand-written Bevy implementations: the naive
`Component<Entity>` pattern most first-pass code uses, and a
hand-rolled `HuntedBy(Vec<Entity>)` reverse index — the exact
maintenance the relation API does for you.

| implementation                                  | 500 × 2000 tick µs/op |
|-------------------------------------------------|----------------------:|
| bevy 0.15 — hand-rolled reverse index           |             **11.5** |
| **japes** — `@ForEachPair` (tier-1 bytecode-gen)|              **31.4** |
| japes — `@Pair` + `PairReader` (tier-1)         |                  43.3 |
| bevy 0.15 — naive `Component<Entity>`           |                 243.7 |

japes now **beats the naive Bevy pattern at every grid cell** — up
to 13.6× faster at 1000 × 5000 — because the forward / reverse
indices are built in, so the `O(predators × prey)` awareness scan
that sinks the naive approach never happens. A determined Bevy user
willing to hand-maintain `HuntedBy(Vec<Entity>)` on every prey still
wins by ~3×, but the gap is no longer about wasted work: it's about
features the library does that the hand-rolled code skips (per-pair
change tracking, deferred `Commands`, archetype marker maintenance,
`RemovedRelations<T>` log drain).

The optimization journey: the 500 × 2000 cell has gone from **167 µs
at PR landing → 31.4 µs today**, a 5.32× speedup with the API surface
staying stable. See
[DEEP_DIVE § Predator / prey — the relations scenario](DEEP_DIVE.md#predator--prey--the-relations-scenario)
for the full 9-cell 4-way grid, the decision table for
`@Pair` vs `@ForEachPair`, and the tier-1 bytecode generator write-up.

## Headline benchmark

One realistic multi-observer tick: 10 000 entities with `{Position,
Velocity, Health, Mana}`, 1 % turnover per tick, three `@Filter(Changed)`
observers. This is the shape change detection is actually designed
for.

| library          | 10k µs/op | 100k µs/op |
|------------------|----------:|-----------:|
| **japes** (this) |  **5.86** |   **7.91** |
| zay-es           |      15.4 |       19.6 |
| bevy (Rust)      |      8.81 |       76.9 |
| artemis-odb      |      24.5 |        279 |
| dominion-ecs     |      44.6 |        389 |

Single-threaded numbers. **japes beats every library in the
comparison at both entity counts**, including Bevy's Rust reference
on the same workload — by 1.50× at 10 k and **9.72× at 100 k**. The
gap widens because japes (and Zay-ES) walk a dirty-slot list that
scales with the ~300 dirty entities per tick, while Bevy, Dominion
and Artemis scan the full archetype per observer and scale linearly
with total entity count. Neither approach dominates universally —
see [DEEP_DIVE.md](DEEP_DIVE.md#realistic-multi-observer-tick) for
the write-path trade-off in the opposite direction.

Full cross-library tables (iteration, N-body, sparse-delta, the
Valhalla investigation, benchmark-fairness audit) are in
[`DEEP_DIVE.md`](DEEP_DIVE.md#realistic-multi-observer-tick).
Short version:

- **`SparseDelta tick`** (10 k entities, 100 dirty per tick, 1 observer):
  japes **1.88 µs/op**, Bevy 4.11 µs/op, Zay-ES 4.67 µs/op —
  japes is **2.19× faster than Bevy** on the library change-detection
  path it's optimised for.
- **`iterateWithWrite`** (write-heavy micro): Dominion 22.5 µs,
  Artemis 18.2 µs, japes 38.5 µs. Mutable-POJO libraries win on
  naive per-entity writes because they skip change tracking entirely
  — if you don't need observers or filters, use them.

DEEP_DIVE.md has the full picture including the Valhalla investigation
(real ~4× speedup on reads via flat value-record layout), the
SoA-storage experiment, and the "write-path tax" trade-off discussion.

## Build

- JDK 26 with `--enable-preview` (uses `java.lang.classfile`)
- Gradle wrapper included: `./gradlew build`

Running benchmarks:

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar
```

All benchmark modules under `benchmark/` — see `DEEP_DIVE.md` for
reproduction commands for each library.

## Project layout

```
ecs-core/                       library source + unit tests
benchmark/
  ecs-benchmark/                japes JMH benches
  ecs-benchmark-valhalla/       same, on a Valhalla JDK
  ecs-benchmark-dominion/       Dominion-odb counterpart
  ecs-benchmark-artemis/        Artemis-odb counterpart
  ecs-benchmark-zayes/          Zay-ES counterpart
  bevy-benchmark/               Bevy (Rust) reference, `cargo bench`
README.md                       this file
TUTORIAL.md                     step-by-step API walkthrough
DEEP_DIVE.md                    full benchmark tables + analysis
```

## License

TBD
