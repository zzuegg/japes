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

| Benchmark | japes | Bevy 0.15 (Rust) | japes vs Bevy |
|---|---:|---:|---|
| RealisticTick 10k (3 observers) | **5.86 µs** | 8.81 µs | **1.50× faster** |
| RealisticTick 100k | **7.91 µs** | 76.9 µs | **9.72× faster** |
| SparseDelta 10k (change detection) | **1.88 µs** | 4.11 µs | **2.19× faster** |
| PredatorPrey @ForEachPair 500×2000 | **31.4 µs** | 11.5 µs (hand-rolled) | 2.73× slower |
| iterateWithWrite 10k | 38.6 µs | **6.29 µs** | 6.1× slower |

japes wins on change-detection workloads (dirty-list scaling). Bevy wins on raw writes (mutable components, no tracking overhead). Full tables, methodology, and the write-path trade-off discussion: **[benchmarks](https://zzuegg.github.io/japes/benchmarks/)**.

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
