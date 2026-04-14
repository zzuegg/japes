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
| RealisticTick 10k (3 observers) | **— µs** | 8.4 µs | — |
| RealisticTick 100k | **— µs** | 73.4 µs | — |
| SparseDelta 10k (change detection) | **— µs** | 3.9 µs | — |
| PredatorPrey @ForEachPair 500×2000 | **— µs** | 11.0 µs (hand-rolled) | — |
| iterateWithWrite 10k | **1.6 µs** | 6.2 µs | **3.86× faster** |
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
