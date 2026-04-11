# Multi-threading

japes ships two executors. `singleThreaded()` runs the schedule on the calling thread in topological order. `multiThreaded()` runs the same schedule on a `ForkJoinPool`, fanning out every batch of conflict-free systems as parallel tasks. Which one you pick is a one-line change on the builder; everything else about writing systems stays identical.

## Picking an executor

```java
import zzuegg.ecs.executor.Executors;

// Default: single-threaded, runs on the caller of world.tick().
var world = World.builder()
    .addSystem(Physics.class)
    .build();                                        // implicit Executors.singleThreaded()

// Parallel, ForkJoinPool sized to Runtime.availableProcessors().
var parallelWorld = World.builder()
    .addSystem(Physics.class)
    .executor(Executors.multiThreaded())
    .build();

// Parallel, fixed thread count.
var sized = World.builder()
    .executor(Executors.fixed(4))
    .build();

// Parallel, share an existing ForkJoinPool (e.g., the common pool).
var shared = World.builder()
    .executor(Executors.multiThreaded(ForkJoinPool.commonPool()))
    .build();
```

The factory methods on `Executors` are the complete surface:

| Method                               | Threads                              | Pool ownership |
|--------------------------------------|--------------------------------------|----------------|
| `singleThreaded()`                   | calling thread                       | —              |
| `multiThreaded()`                    | `Runtime.availableProcessors()`      | owned, shut down on `world.shutdown()` |
| `multiThreaded(ForkJoinPool pool)`   | pool's parallelism                   | external       |
| `fixed(int threads)`                 | explicit                             | owned          |

When the world owns the pool, shutting the world down shuts the pool down too. When you pass an external pool (like `commonPool()`), the world leaves it alone — that is almost always what you want when embedding a japes world inside a larger application.

## How parallelism falls out of the schedule

Parallelism is derived, not declared. The `DagBuilder` already produced a dependency graph for every stage — the executor simply pops all nodes whose in-degree has reached zero and runs them as a batch.

```
PreUpdate wave 1:  [clearForces, applyInput]            → run in parallel
PreUpdate wave 2:  [dispatchInput]                      → waits on applyInput
                   [flush commands at stage boundary]
Update    wave 1:  [movePlayers, moveEnemies]           → parallel (disjoint archetypes)
Update    wave 2:  [integrateVelocity]                  → waits on both moves
                   [flush commands at stage boundary]
```

The DAG builder inserts edges when two systems have a write-conflict on the same component, a shared-resource write, or an explicit `after`/`before`. Everything else becomes a parallel edge. See [Stages and ordering](11-stages-and-ordering.md) for the full conflict rules.

!!! tip "Disjoint archetypes unlock parallelism on the same component"

    Two systems both writing `Position` normally force a sequential edge. Add `@With(Player.class)` to one and `@With(Enemy.class) @Without(Player.class)` to the other and the builder proves their archetype sets are disjoint — the edge disappears and they run in parallel.

## What the executor actually does

`MultiThreadedExecutor` walks the graph wave by wave:

1. Ask the graph for all nodes with `inDegree == 0`.
2. If only one node is ready, run it inline — no Phaser, no task submission.
3. Otherwise, submit every ready node to the `ForkJoinPool`, wait for all of them on a `Phaser`, and repeat.
4. On any exception, capture the first failure (suppressing later ones onto it) and rethrow after the phase joins.

This means the actual thread count used by a tick is never larger than the **widest wave** in the graph. A schedule with one wave of eight parallel systems and one wave of two will peak at eight threads, even if the pool has 32.

## Service parameters in parallel

The service-param resolution rules are simple enough to enumerate:

- **`Commands`** — each system gets its *own* `Commands` buffer at plan-build time. Two parallel systems each hold a different buffer; buffers are drained and applied at the end of the current stage.
- **`EventWriter<E>`** — shared across systems that write the same event type, but `send` is `synchronized` on the underlying `EventStore`, so concurrent writes are safe.
- **`EventReader<E>`** — reads the (immutable, swapped-in) read buffer. Multiple readers see the same list.
- **`Res<T>` / `ResMut<T>`** — the DAG builder forbids two systems from holding `ResMut` on the same resource in parallel, so every observed `ResMut` is effectively single-threaded. Read-only `Res` is free to share.
- **`Local<T>`** — keyed by `(systemName, paramIndex)`, so it is always single-threaded by construction.
- **`RemovedComponents<T>`** — read-only snapshot of the removal log. Parallel-safe.

!!! warning "Never share your own mutable state between systems"

    If you cache a `HashMap` in a singleton or static field and poke it from multiple `@System` methods, the DAG builder cannot see the dependency and the multi-threaded executor will happily corrupt it. Put the state in a `Res`/`ResMut`, in a `Local`, or in a plain component — anything the framework can analyse.

## Commands safety under parallel execution

Every `Commands` parameter is allocated in `resolveServiceParam`, which runs once per system at plan-build time. The buffer is stored both on the system's argument array and in a world-wide list `allCommandBuffers`. During execution, two parallel systems each write into their **own** `Commands` instance — there is no contention, no lock, no shared state.

At the end of the stage, the world drains every non-empty buffer in sequence on the main thread and hands the list of commands to the structural change pipeline. The pipeline is inherently single-threaded because it mutates archetype storages.

```java
// These run in parallel, each with its own Commands.
@System void spawnPlayers(Commands cmds) { cmds.spawn(new Player(), new Health(100)); }
@System void spawnEnemies(Commands cmds) { cmds.spawn(new Enemy(),  new Health(30));  }
// At the end of Update, both buffers flush: 
// spawn Player, then spawn Enemy, applied sequentially on the main thread.
```

The order in which buffers flush is the order systems were added to the world — not the order they ran in parallel. If two systems both spawn the same entity id, the later one wins. Generally, don't do that.

## Tuning parallelism

- **Wide waves win.** The executor can only exploit parallelism that the DAG exposes. If your hot stage is a single chain of 20 systems each depending on the previous, the multi-threaded executor is strictly slower than single-threaded because it pays task submission overhead for no fan-out.
- **Pool sizing.** `multiThreaded()` defaults to `availableProcessors()`; on laptops with eight threads that is usually right. On servers running many worlds in one JVM, share the common pool with `multiThreaded(ForkJoinPool.commonPool())` so contention is managed by one scheduler.
- **Measure.** The `benchmarks` module contains comparable single- vs. multi-threaded runs. Always benchmark your real schedule before deciding; a schedule that parallelises 2× on paper often only sees 1.3× wall-clock because stage-boundary flushes are sequential.

!!! tip "Start single-threaded, switch when you have data"

    Single-threaded schedules are deterministic, easier to debug, and the baseline you should beat. Move to `multiThreaded()` only once a profiler shows the executor is the bottleneck and the DAG has enough fan-out to benefit.

## Exceptions and failure propagation

When a system throws under the multi-threaded executor, the executor does not immediately abort — it lets the other systems in the same wave run to completion, captures the **first** failure on an `AtomicReference`, and suppresses every subsequent failure onto it via `addSuppressed`. Once the wave completes, the executor rethrows the first failure and you get the full suppression chain in the stack trace.

```
RuntimeException: executor wave failed
    Suppressed: IllegalStateException: ...
    Suppressed: NullPointerException: ...
```

The `ForkJoinPool` itself is never left in a corrupt state; the per-wave `Phaser` ensures the failed wave fully drains before the next one is attempted (it is not — the stage fails and the exception propagates up through `world.tick()`).

!!! warning "Assertion-style crashes in parallel systems can be noisy"

    A failing `assert` in system A looks identical to a failing `assert` in an unrelated system B if both are in the same wave. Read the whole suppression chain, not just the top-line exception.

## Deterministic debugging

One aspect people underestimate: the multi-threaded executor is not *non*-deterministic within the rules the DAG sets, but the **order of side effects that happen to arrive at a resource from parallel systems** is determined by thread scheduling. Two systems that both call `evtWriter.send(...)` on the same event in the same wave can produce either interleaving across runs. If you rely on event order, add an explicit `after =` edge to pin the systems into a line.

A useful debugging technique: if a bug only shows up under `multiThreaded()`, switch to `singleThreaded()` and see whether it still reproduces. If it does, the bug is real logic — the executor just exposes it because of the widened state space. If it goes away, you have a hidden shared-state dependency the DAG builder could not see, and the fix is to surface it as a resource or an explicit ordering constraint.

## Cost of the executor itself

For a schedule of small systems over a small number of entities, the executor submission cost dominates. On a benchmark of 64 trivial integrator systems over 10 000 entities, the single-threaded executor wins by 20% because:

- `ForkJoinPool` task submission costs a few hundred nanoseconds per system.
- `Phaser.awaitAdvance` is a synchronized barrier.
- The waves are short enough that the overhead is not amortised.

By contrast, with 4 systems over 1 million entities, the multi-threaded executor wins by ~3× on a 4-core machine because per-entity work dwarfs the fixed cost. The break-even point is roughly "does one system do enough work per tick to justify a ForkJoinTask?".

Finally, remember that the scheduler owns the parallelism but you own the data shape. A DAG that should fan out eight ways will still run serial if every system writes the same resource. Fix that by splitting the resource or by moving per-system state into `Local` or components — then rerun the benchmark.

## What's next

- [Query filters](13-query-filters.md) — `@With` / `@Without` markers that unlock disjoint-archetype parallelism.
- Related basics: [Systems](../basics/03-systems.md), [Commands](../basics/08-commands.md).
