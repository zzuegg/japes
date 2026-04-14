# japes

**J**ava **A**rchetype-based **P**arallel **E**ntity **S**ystem — a high-throughput ECS for the JVM with first-class change detection, Flecs-style entity relations, and tier-1 bytecode generation.

> Pre-release. API stable enough to benchmark; reserves the right to change before 1.0.

**[Full documentation, tutorials, and benchmarks](https://zzuegg.github.io/japes/)**

## Quick start

```java
record Position(float x, float y) {}
record Velocity(float dx, float dy) {}

class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p) {
        var cur = p.get();
        p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
    }
}

var world = World.builder().addSystem(Physics.class).build();
world.spawn(new Position(0, 0), new Velocity(1, 1));
world.tick();
```

## Highlights

| What | How |
|---|---|
| **Archetype + chunk storage** | Flat arrays, cache-linear iteration, zero type dispatch in the hot loop |
| **Tier-1 bytecode dispatch** | Per-system hidden classes via `java.lang.classfile` — JIT inlines the user method |
| **Change detection** | `@Filter(Added/Changed/Removed)` with multi-target OR, backed by per-component dirty lists |
| **First-class relations** | `@Pair(T)` / `@ForEachPair(T)` with forward + reverse indices, cleanup policies, `RemovedRelations<T>` |
| **Deferred structural edits** | `Commands` buffers flush at stage boundaries — no locking |
| **Disjoint-access parallelism** | DAG scheduler runs non-conflicting systems in parallel automatically |

## Benchmark snapshot

µs/op, lower is better. JDK 26, Bevy 0.15 via Criterion, single fork.

| Benchmark | **japes** | Bevy (Rust) | Artemis | vs Bevy |
|---|---:|---:|---:|---|
| iterateWithWrite 10k | **1.6** | 6.3 | 19.0 | **3.92× faster** |
| NBody 10k | **1.7** | 8.8 | 20.0 | **5.24× faster** |
| SparseDelta 10k | **1.8** | 4.0 | 0.275 | **2.28× faster** |
| RealisticTick 10k (3 observers) | **6.5** | 8.5 | 26.2 | **1.32× faster** |
| RealisticTick 100k | **8.5** | 75.4 | 283 | **8.93× faster** |
| ParticleScenario 10k | **28.3** | 21.5 | 101 | 1.3× slower |
| PredatorPrey @ForEachPair 500×2000 | **19.4** | 11.1 | — | 1.8× slower |

### Allocation per tick (japes, B/op)

| Benchmark | B/op |
|---|---:|
| iterateWithWrite 10k | **0** |
| NBody 10k | **0** |
| SparseDelta 10k | 1,638 |
| RealisticTick 10k | 11,279 |
| ParticleScenario 10k | 72,583 |

Full cross-library tables (Zay-ES, Dominion): **[benchmarks](https://zzuegg.github.io/japes/benchmarks/)**.

## Dependency

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/zzuegg/japes")
        credentials { /* GitHub token with read:packages */ }
    }
}
dependencies {
    implementation("io.github.zzuegg.japes:ecs-core:0.1.0-SNAPSHOT")
}
```

Requires **JDK 26** with `--enable-preview`. See **[installation guide](https://zzuegg.github.io/japes/getting-started/installation/)** for full setup.

## Build from source

```bash
git clone https://github.com/zzuegg/japes.git && cd japes
./gradlew :ecs-core:test              # run tests
./gradlew :benchmark:ecs-benchmark:jmhJar  # build benchmark jar
```

## License

MIT
