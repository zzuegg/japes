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

## Contents

| Section | Description |
|---|---|
| [Quick start](#quick-start) | Minimum world + system in a dozen lines |
| [Change detection](#change-detection) | `@Filter(Changed)` + `RemovedComponents<T>` |
| [Deferred structural edits](#deferred-structural-edits) | `Commands` buffers for spawn/despawn/insert/remove |
| [Headline benchmark](#headline-benchmark) | One-line cross-library comparison |
| [Build](#build) | JDK 26, Gradle wrapper, running benchmarks |
| [Project layout](#project-layout) | What's in each module |

**Other docs**
| Document | For who |
|---|---|
| [TUTORIAL.md](TUTORIAL.md) | Step-by-step walkthrough: components, systems, queries, filters, resources, events, commands, scheduling, multi-threading |
| [DEEP_DIVE.md](DEEP_DIVE.md) | Full benchmark tables, per-benchmark analysis, Valhalla investigation, API design rationale |
| [docs/notes/](docs/notes) | Session logs / design notes |

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

## Headline benchmark

One realistic multi-observer tick: 10 000 entities with `{Position,
Velocity, Health, Mana}`, 1 % turnover per tick, three `@Filter(Changed)`
observers. This is the shape change detection is actually designed
for.

| library          |       µs/op | vs japes |
|------------------|------------:|---------:|
| **japes** (this) |    **5.79** |      1.0× |
| artemis-odb      |        24.7 |     4.3× |
| dominion-ecs     |        41.7 |     7.2× |
| bevy (Rust)      | (see below) |        — |

The benchmark, code, and Bevy/Zay-ES numbers for the same workload
are in [`DEEP_DIVE.md`](DEEP_DIVE.md#realistic-multi-observer-tick),
along with iteration, N-body, and sparse-delta micro-benchmarks.
Short version:

- **`SparseDelta tick`** (10 k entities, 100 dirty per tick, 1 observer):
  japes **1.86 µs/op**, Bevy 4.01 µs/op, Zay-ES 4.60 µs/op —
  japes is **2.16× faster than Bevy** on the library change-detection
  path it's optimised for.
- **`RealisticTick`** `st` single-threaded at 5.79 µs/op beats
  Artemis's fastest multi-threaded configuration (12.9 µs/op) on the
  same workload. Total CPU cost: 6.7× cheaper than Artemis `mt`.
- **`iterateWithWrite`** (write-heavy micro): Dominion 22.6 µs,
  Artemis 18.4 µs, japes 58.2 µs. Mutable-POJO libraries win on
  naive per-entity writes because they skip change tracking entirely
  — if you don't need observers or filters, use them.

DEEP_DIVE.md has the full picture including the Valhalla investigation
(real ~3× speedup on reads via flat value-record layout), the
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
docs/
  notes/                        session logs / design notes
DEEP_DIVE.md                    full benchmark tables + analysis
```

## License

TBD
