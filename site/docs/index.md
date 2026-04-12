---
title: japes — Java Archetype-based Parallel Entity System
hide:
  - navigation
  - toc
---

<div class="japes-hero" markdown>

# japes

<p class="tagline">
<strong>J</strong>ava <strong>A</strong>rchetype-based <strong>P</strong>arallel <strong>E</strong>ntity <strong>S</strong>ystem — a high-throughput ECS for the JVM with first-class change detection, Flecs-style entity relations and a tier-1 bytecode generator that turns your system methods into direct-dispatch hidden classes.
</p>

<div class="cta">
  <a href="getting-started/quick-start/" class="md-button md-button--primary">Quick start</a>
  <a href="tutorials/" class="md-button">Tutorials</a>
  <a href="benchmarks/" class="md-button">Benchmarks</a>
</div>

</div>

!!! tip "Pre-release"

    The API is stable enough to benchmark but reserves the right to change before 1.0. Snapshot Maven artifacts are published from `main` to GitHub Packages — see [Installation](getting-started/installation.md) for the client-side wiring.

## What japes is

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

Everything is declarative. Components are plain `record`s. Systems are annotated methods whose parameters tell the scheduler exactly what they read and write, so the DAG builder can run disjoint systems in parallel automatically — no manual locking, no priority tables. The scheduler then emits a **hidden class** per system: your user method is called via direct `invokevirtual` with every argument hoisted into a JIT register, so hot loops inline as if you'd hand-written one big monolithic method.

## Feature grid

<div class="japes-card-grid" markdown>

<div class="japes-card" markdown>
### :material-database: Archetype storage
Flat chunked arrays keyed by component signature. Every matching archetype iterates cache-linearly with zero type-based dispatch in the hot loop.
</div>

<div class="japes-card" markdown>
### :material-flash: Tier-1 bytecode dispatch
Per-system hidden classes generated via `java.lang.classfile`. The JIT sees an ordinary virtual call and inlines your whole system body into the chunk loop.
</div>

<div class="japes-card" markdown>
### :material-eye-check: Change detection
`@Filter(Added.class)` / `@Filter(Changed.class)` filters and `RemovedComponents<T>` service params, backed by per-component dirty lists — observers walk only entities that actually changed.
</div>

<div class="japes-card" markdown>
### :material-vector-link: First-class relations
`@Relation`, `@Pair(T.class)`, `@ForEachPair(T.class)` with non-fragmenting forward + reverse indices and archetype markers — Flecs-style without leaving Java.
</div>

<div class="japes-card" markdown>
### :material-gesture-double-tap: Deferred structural edits
`Commands` buffers spawn/despawn/insert/remove operations from inside parallel systems and flushes them at stage boundaries — no locking, no race conditions.
</div>

<div class="japes-card" markdown>
### :material-cpu-64-bit: Disjoint-access parallelism
The scheduler analyses each system's access set statically and runs everything that commutes on the multi-threaded executor — no annotations, no priorities.
</div>

</div>

<div class="japes-perf-callout" markdown>
**Headline benchmark.** With SoA storage, `iterateWithWrite` at 10k lands at **japes 1.70 µs/op** vs Bevy 0.15's 6.29 µs/op — **3.7× faster** than the Rust reference on writes. Change detection stays strong: `SparseDelta` at **2.16 µs/op** vs Bevy's 4.11 — **1.90× faster**. The [benchmarks section](benchmarks/index.md) shows the full cross-library tables plus the predator/prey relations workload where japes runs at **27.6 µs/op** on 500 × 2000 tuples.
</div>

## Where to start

<div class="japes-card-grid" markdown>

<div class="japes-card" markdown>
### :material-rocket-launch: I just want to ship code
[**Quick start**](getting-started/quick-start.md) &rarr; gradle dependency, first world, first system, first tick.
</div>

<div class="japes-card" markdown>
### :material-book-multiple: I want to learn the whole API
[**Tutorials**](tutorials/index.md) &rarr; 22 chapters covering every annotation and service parameter, from components to relations.
</div>

<div class="japes-card" markdown>
### :material-speedometer: I want the numbers
[**Benchmarks**](benchmarks/index.md) &rarr; methodology, per-benchmark analysis and the cross-library cost-model breakdown.
</div>

<div class="japes-card" markdown>
### :material-puzzle: I want the design rationale
[**Reference**](reference/index.md) &rarr; cheat sheet, tier-1/tier-2 fallback catalog, FAQ.
</div>

</div>

## License

MIT. japes is single-author hobby research released in the hope it's useful. Contributions, issues and pull requests all welcome at [github.com/zzuegg/japes](https://github.com/zzuegg/japes).
