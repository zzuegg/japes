# ECS Library Design Specification

## Overview

A high-performance, standalone Entity Component System library for Java 26 with Valhalla-ready design. Bevy-inspired API with method-signature injection, automatic parallelism, and batch command processing. Zero external runtime dependencies.

**Key constraints:**
- Compile time does not matter; execution time does
- Multithreaded, thread-safe, fast, easy to use
- Fully tested via TDD, including corner cases and misuse
- Gradle + Kotlin buildscripts
- Targeting Java 26 + Valhalla EA for performance comparison benchmarks
- Package: `zzuegg.ecs`

---

## 1. Core Data Model

### Entity
- 64-bit value: 32-bit index + 32-bit generation counter
- Generation prevents stale references from addressing recycled slots
- Valhalla-ready as a value type
- Recycled via a free list; generation increments on recycle

### Components
- Components are **records** — pure data, immutable, Valhalla value type candidates
- Registration by `Class<T>`, no marker interface required
- Storage strategy declared via annotation:
  - `@TableStorage` (default) — stored in archetype chunk tables, optimized for iteration
  - `@SparseStorage` — stored in sparse sets indexed by entity, optimized for add/remove
- Change detection:
  - Write-access tracking by default: tick counter bumped on `Mut.set()` call
  - `@ValueTracked` components use record `equals()` to suppress no-ops
  - If `set()` is never called on a `Mut<T>`, no change is recorded

### Archetypes & Chunks
- An **Archetype** is defined by its unique set of table-stored component types
- Each archetype owns a list of **Chunks**
- A **Chunk** is a fixed-size block (default 1024 entities) containing:
  - Dense arrays for each component type in the archetype
  - An entity ID array
- When a chunk fills, a new chunk is allocated
- On entity removal: swap-remove (last entity fills the gap) to keep arrays dense
- Valhalla payoff: value-type records stored flat in chunk arrays — no object headers, no pointers, contiguous memory. On standard Java 26, reference arrays with same API but worse cache behavior

### Sparse Sets
- Components marked `@SparseStorage` live outside archetypes in a `SparseSet<T>`
- Structure: dense array of `(entity, T)` pairs + sparse array mapping entity index to dense index
- Sparse components do not affect archetype identity — adding/removing never triggers migration

---

## 2. Queries & System Parameters

### Per-Entity Systems
Systems are methods with injectable parameters. The method signature IS the query. The framework inspects parameters via reflection at registration, builds the query internally, and invokes the method once per matching entity:

```java
@System
void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
    var p = pos.get();
    var d = dt.get().dt();
    pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d, p.z() + vel.dz() * d));
}
```

Method handles via `LambdaMetafactory` are generated at registration for near-direct-call performance on the hot path. Reflection only happens once.

### Read vs Write Access
- `@Read T` — direct record value, read-only
- `@Write Mut<T>` — mutable wrapper with `get()` (read current) and `set()` (replace value)
- Granular per-component: a system can read some components and write others
- The scheduler uses this for maximum parallelism — two systems both reading the same component run concurrently

### Chunk Systems (Escape Hatch)
For performance-critical bulk operations, fixed-arity overloads up to 12 components:

```java
@System
void moveBulk(ChunkQuery2<@Read Position, @Write Velocity> chunks) {
    chunks.forEachChunk((posArray, velArray, count) -> {
        // raw array access for SIMD-friendly loops
    });
}
```

### Query Filters
Annotations on the system method:
- `@With(Component.class)` — entity must have component
- `@Without(Component.class)` — entity must not have component
- `@Filter(Added.class, target = Component.class)` — entity got component this tick
- `@Filter(Changed.class, target = Component.class)` — component was replaced this tick
- `@Filter(Removed.class, target = Component.class)` — component was removed this tick. These entities are iterated separately (they no longer match the archetype), and the old component value from before removal is provided as a read-only parameter

### Injectable Parameter Types
| Type | Purpose |
|---|---|
| `@Read T` | Read-only component access |
| `@Write Mut<T>` | Mutable component access |
| `Res<T>` | Read-only resource |
| `ResMut<T>` | Mutable resource (`get()`/`set()`) |
| `Commands` | Deferred structural mutations |
| `EventWriter<T>` | Send typed events |
| `EventReader<T>` | Receive typed events |
| `Local<T>` | Per-system persistent state |

---

## 3. System Registration & Ordering

### System Annotation
Full system configuration via annotations:

```java
@System(
    stage = "Update",
    after = "inputSystem",
    before = "collisionSystem"
)
@With(Enemy.class)
@Without(Dead.class)
@Filter(Changed.class, target = Position.class)
void trackEnemyMovement(@Read Position pos, Res<GameState> state) { ... }
```

### System Sets
Grouping systems in a class defines a system set:

```java
@SystemSet(name = "physics", stage = "Update", after = "input")
public class PhysicsSystems {

    @System
    void applyGravity(@Write Mut<Velocity> vel, Res<Gravity> g) { ... }

    @System(after = "applyGravity")
    void integrate(@Read Velocity vel, @Write Mut<Position> pos) { ... }
}
```

Class-level annotation provides defaults; method-level annotations can override or add ordering within the set.

### Run Conditions

```java
@System(stage = "Update")
@RunIf("isPlaying")  // references a named condition method
void move(@Read Position pos, @Write Mut<Velocity> vel) { ... }
```

### Exclusive Systems
Systems annotated `@Exclusive` get direct mutable `World` access. They run alone — no other system runs concurrently:

```java
@System(stage = "PreUpdate")
@Exclusive
void loadScene(World world) {
    // direct world mutation, no commands needed
}
```

---

## 4. Scheduler & Executor

### Stages
Predefined ordered stages that run sequentially:

1. `First` — housekeeping (event cleanup, entity recycling)
2. `PreUpdate` — input processing, time advancement
3. `Update` — main game logic
4. `PostUpdate` — reactions, constraint solving
5. `Last` — cleanup, state snapshots

Users can insert custom stages between any of these. A stage completes fully (including command flushes) before the next begins.

### DAG Scheduling (Within a Stage)
At startup:
1. Collect all systems in the stage
2. Read annotations for explicit ordering and access declarations
3. Build dependency DAG — edges from explicit ordering + implicit conflicts (two systems writing the same component can't run in parallel)
4. Validate: detect cycles, warn on ambiguous ordering

At runtime each tick:
1. Systems with zero unmet dependencies dispatch to thread pool
2. As each system completes, dependents decrement and dispatch when ready
3. Sync points: after all systems that produced commands complete, commands flush before dependents run

### Chunk-Level Parallelism
Within a single system execution:
- Matched chunks split across worker threads
- Each thread processes its slice independently
- Per-entity systems: framework iterates entities within chunk, invokes method per entity
- Chunk systems: framework passes raw arrays

### Configurable Executor

```java
var world = World.builder()
    .executor(Executors.multiThreaded())      // default: ForkJoinPool, DAG + chunk parallelism
    .executor(Executors.multiThreaded(pool))   // custom ForkJoinPool
    .executor(Executors.singleThreaded())      // sequential — useful for debugging/testing
    .executor(Executors.fixed(4))              // fixed thread count
    .build();
```

Executor interface:

```java
public interface Executor {
    void execute(ScheduleGraph graph, World world);
}
```

- `ScheduleGraph` contains the resolved DAG for one stage
- Users can implement custom `Executor` for custom scheduling strategies
- `SingleThreadedExecutor` runs systems in topological order — same results, easier to debug

---

## 5. Commands & Batch Processing

### Command Buffer
Each system injecting `Commands` gets a thread-local command buffer:

```java
@System
void spawnBullets(@Read Gun gun, @Read Position pos, Commands cmd) {
    if (gun.firing()) {
        cmd.spawn(new Position(pos.x(), pos.y(), pos.z()), new Velocity(0, 0, 100));
    }
}
```

Available commands:
- `cmd.spawn(Component...)` — create entity with components
- `cmd.despawn(Entity)` — destroy entity
- `cmd.add(Entity, Component)` — add component to existing entity
- `cmd.remove(Entity, Class<T>)` — remove component
- `cmd.set(Entity, Component)` — replace component value
- `cmd.insertResource(T)` — insert/replace global resource

### Batch Coalescing
At sync points, command buffers are collected and coalesced:

1. **Group by operation type + target archetype** — 500 spawns with same component set become one batch
2. **Bulk allocation** — one chunk allocation for full batch instead of 500 individual inserts
3. **Bulk migration** — 200 `add` calls to same source-to-destination archetype become one bulk move: allocate destination space, memcpy component arrays, fixup entity indices
4. **Despawn compaction** — bulk swap-remove, defragmenting chunks in one pass

### Ordering Guarantees
- Commands from a single system: applied in recording order
- Commands across systems within same sync point: applied in DAG topological order
- Deterministic, reproducible behavior regardless of thread scheduling

---

## 6. Events

### Event Declaration
Events are records:

```java
record CollisionEvent(Entity a, Entity b, Vec3 point) {}
```

### Sending & Receiving

```java
@System
void detect(@Read Position pos, @Read Collider col, EventWriter<CollisionEvent> collisions) {
    collisions.send(new CollisionEvent(entityA, entityB, hitPoint));
}

@System(after = "detect")
void react(EventReader<CollisionEvent> collisions, Commands cmd) {
    for (var event : collisions.read()) { ... }
}
```

### Lifecycle
- Double-buffered: sent events land in write buffer, readers see read buffer
- Start of `First` stage: read buffer cleared, write buffer becomes read buffer
- Events readable for exactly one full tick after being sent
- Writer and reader in same tick: reader must be ordered `after` writer (scheduler warns if not)

### Ordering
- Events from single system: ordered by send order
- Events from different systems: ordered by DAG completion order — deterministic

---

## 7. Resources & Local State

### Resources
Global singletons accessed by type:

```java
world.insertResource(new DeltaTime(0f));
```

- `Res<T>` — read-only, multiple systems read in parallel
- `ResMut<T>` — mutable via `get()`/`set()`, exclusive access
- Stored in type-keyed map on `World`

### Local State
Per-system persistent state:

```java
@System
void track(Local<Integer> frameCount) {
    frameCount.set(frameCount.get() + 1);
}
```

- Unique per system instance, not shared, invisible to scheduler
- Initialized to `null` unless `@Default` provided

---

## 8. World API & External Integration

### World Setup

```java
var world = World.builder()
    .addResource(new DeltaTime(0f))
    .addResource(new Gravity(-9.81f))
    .addStage("Physics", Stage.after("PreUpdate"))
    .addSystem(PhysicsSystems.class)
    .addEvent(CollisionEvent.class)
    .executor(Executors.multiThreaded())
    .chunkSize(1024)
    .build();
```

### Tick Loop
The world does not own the loop:

```java
while (running) {
    world.setResource(new DeltaTime(delta()));
    world.tick(); // runs all stages, blocks until complete
    renderer.present();
}
```

Between ticks, the caller has full mutable world access.

### Snapshots (Optional Render Decoupling)

```java
var snapshot = world.snapshot(Position.class, Sprite.class);
renderThread.submit(() -> snapshot.forEach((pos, sprite) -> { ... }));
```

Snapshots copy queried component data — ECS proceeds to next tick without contention.

---

## 9. Testing Strategy

### Test-Driven Development
All code is written test-first. Red-green-refactor cycle for every feature.

### Unit Tests
Every module gets thorough coverage:

- **Entity** — allocation, recycling, generation overflow, free list correctness
- **Archetype/Chunk** — creation, entity add/remove, swap-remove compaction, chunk splitting, empty chunk reclamation
- **Sparse Set** — insert, remove, lookup, dense iteration, double-remove
- **Queries** — matching archetypes, all filter types, read/write access tracking
- **Change Detection** — write-access tracking, value-equality tracking, tick propagation, no-op suppression
- **Commands** — recording, batch coalescing, bulk spawn, bulk migration, despawn compaction, ordering guarantees
- **Events** — send/receive, double-buffer lifecycle, ordering, clear, read-before-write warning
- **Resources** — insert, get, set, missing resource errors
- **Scheduler** — DAG construction, cycle detection, ambiguity warnings, sync point insertion, parallel correctness
- **Mut<T>** — get/set, change tracking, no-set-no-change guarantee
- **Executor** — all strategies produce identical results

### Corner Cases & Misuse Tests
- Stale `Entity` reference after despawn (generation mismatch)
- Accessing despawned entity's components
- Double-registering the same system
- System with conflicting annotations (e.g., `@Read` and `@Write` on same component)
- Empty world tick (no systems, no entities)
- System that spawns entities matching its own query (no infinite loop)
- Command on entity that gets despawned in same sync point
- Resource access before insertion
- Event reader with no corresponding writer
- Archetype with zero entities after bulk despawn
- Chunk boundary: exactly 1024 entities, then add one more
- Zero-component entity
- System with no component parameters (only resources/commands)
- Circular system dependencies (must error at build time)
- Mutable access to same component from two systems without explicit ordering (must warn)

### Integration Tests
- Multi-system pipelines across stages with correct ordering
- Concurrent read/write safety under parallel execution
- Command ordering determinism across multiple runs
- Stress: 100k+ entities, verify correctness

### Thread Safety Tests
- Parallel systems on shared data — no data corruption
- Determinism: same state + same systems = same result, every time

---

## 10. Benchmarks

### JMH Micro-Benchmarks
- Entity spawn: single, bulk 1k, bulk 100k
- Entity despawn: single, bulk
- Component add/remove (archetype migration)
- Iteration: 1-component, 4-component, 8-component queries
- Iteration with filters (Changed, With/Without)
- Sparse set vs table storage iteration
- Command buffer record + flush
- Event send + read throughput

### Macro-Benchmarks
- **N-body simulation** — 10k entities, gravity, position integration
- **Boids** — 50k entities, neighbor queries, velocity updates
- **Particle system** — high spawn/despawn rate, short-lived entities

### Valhalla Comparison
Same benchmark suite on:
- Java 26 (standard) — components as regular records
- Valhalla EA — components as value records

Output: side-by-side table with throughput, latency (p50/p99), allocation rate, GC pressure.

---

## 11. Project Structure

```
ecs/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── ecs-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/
│       │   └── zzuegg/ecs/
│       │       ├── entity/
│       │       ├── component/
│       │       ├── archetype/
│       │       ├── storage/
│       │       ├── query/
│       │       ├── system/
│       │       ├── scheduler/
│       │       ├── command/
│       │       ├── event/
│       │       ├── resource/
│       │       └── world/
│       └── test/java/
│           └── zzuegg/ecs/
│               ├── entity/
│               ├── component/
│               ├── ... (mirrors main)
│               └── integration/
├── ecs-benchmark/
│   ├── build.gradle.kts
│   └── src/jmh/java/
│       └── zzuegg/ecs/bench/
│           ├── micro/
│           └── macro/
```

### Build
- Java 26, `--enable-preview`
- Gradle 8.x, Kotlin DSL
- Version catalog for dependencies
- JMH Gradle plugin
- `./gradlew test` — run all tests
- `./gradlew jmh` — run benchmarks on Java 26
- `./gradlew jmhValhalla` — run benchmarks on Valhalla EA JDK (path via property/env var)
- Zero runtime dependencies for `ecs-core`

---

## 12. Public API Summary

### Core Types
| Type | Purpose |
|---|---|
| `World` | Top-level container, owns all state |
| `World.Builder` | Fluent world configuration |
| `Entity` | 64-bit ID (32 index + 32 generation) |
| `Mut<T>` | Mutable component wrapper |
| `Res<T>` | Read-only resource injection |
| `ResMut<T>` | Mutable resource injection |
| `Commands` | Deferred structural mutation |
| `EventWriter<T>` | Send events |
| `EventReader<T>` | Receive events |
| `Local<T>` | Per-system persistent state |
| `ChunkQuery1..12<>` | Bulk chunk access |
| `Executor` | Pluggable scheduling strategy |
| `ScheduleGraph` | Resolved DAG for one stage |

### Annotations
| Annotation | Target | Purpose |
|---|---|---|
| `@System` | Method | Marks system, configures stage/ordering |
| `@SystemSet` | Class | Groups systems |
| `@Exclusive` | Method | System gets `World`, runs alone |
| `@Read` | Parameter | Read-only component access |
| `@Write` | Parameter | Mutable component access |
| `@With` | Method | Must-have filter |
| `@Without` | Method | Must-not-have filter |
| `@Filter` | Method | Change detection filter |
| `@RunIf` | Method | Conditional execution |
| `@TableStorage` | Record | Dense archetype storage (default) |
| `@SparseStorage` | Record | Sparse set storage |
| `@ValueTracked` | Record | Equality-based change detection |
| `@Default` | Parameter | Default for `Local<T>` |
