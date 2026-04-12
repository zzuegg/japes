# Predator / prey — the relations scenario

`PredatorPreyBenchmark` is the reference workload for the first-class
relations feature (`@Relation` record + `RelationStore` +
`@Pair(T.class)` / `@ForEachPair(T.class)` dispatch). It's built to
stress every relation hot path the design doc calls out.

!!! tip "What this workload measures"

    Per-tick steady state:

    - **`Commands.setRelation`** — idle predators acquire a new hunt
      target.
    - **Forward walk** per predator that reads each hunt pair's
      target position — the "pursuit" system.
    - **Reverse walk** per prey counting "how many predators are
      hunting me" (this is the cell the whole feature exists for).
    - **`world.despawn(prey)` → `RELEASE_TARGET`** cleanup — the
      reverse index drops the matching forward entries automatically;
      no user bookkeeping.
    - **`RemovedRelations<Hunting>`** — drains the removal log with a
      per-subscriber watermark.

    One random prey respawns per catch, so entity counts and per-tick
    work stay stable across iterations. Counters are blackholed at
    the end of each tick to defeat DCE on the observer accumulators.

## Two dispatch models: `@Pair` and `@ForEachPair`

japes ships two alternative ways to run a relation-driven system;
the benchmark exercises both and the results grid below shows both
columns side-by-side. See also the tutorial chapter
[`@ForEachPair` and `@FromTarget`](../tutorials/relations/19-for-each-pair.md)
for the language-level walkthrough.

**`@Pair(Hunting.class)`** — *set-oriented*. The system is called
**once per entity** that carries at least one pair. The user body
walks the entity's pairs with `PairReader.fromSource(self)` or
`PairReader.withTarget(self)`:

```java
@System
@Pair(Hunting.class)
void pursuit(@Read Position p, Entity self, PairReader<Hunting> r, @Write Mut<Velocity> v) {
    for (var pair : r.fromSource(self)) { /* steer toward pair.target() */ }
}
```

This shape is the right default when a system needs to see *the
whole set* of pairs attached to one entity (e.g. "sum the focus
values over all my hunt targets", "pick the closest target of any
type"). It's also what `@Pair(role = TARGET)` + `withTarget` is
for — reverse-side systems that want "every predator hunting me"
in one call.

**`@ForEachPair(Hunting.class)`** — *tuple-oriented*. The system is
called **once per live pair**. No walker, no `fromSource` call: the
parameters bind directly to source-side components (default),
target-side components (opt-in with `@FromTarget`), the relation
payload, and source/target entity ids:

```java
@System
@ForEachPair(Hunting.class)
void pursuit(
        @Read Position sourcePos,             // source (predator)
        @Write Mut<Velocity> sourceVel,       // source, writable
        @FromTarget @Read Position targetPos, // target (prey)
        Hunting hunting,                      // payload, type-matched
        ResMut<Counters> counters             // normal service param
) {
    float dx = targetPos.x() - sourcePos.x();
    float dy = targetPos.y() - sourcePos.y();
    float mag = (float) Math.sqrt(dx*dx + dy*dy);
    if (mag > 1e-4f) sourceVel.set(new Velocity(dx / mag * 0.1f, dy / mag * 0.1f));
    counters.get().pursuitCalls++;
}
```

This shape is right when the body only cares about one pair at a
time — steering, distance checks, damage propagation, constraint
solves. It's also the shape the scheduler can make *fast*, because
the tier-1 bytecode generator can walk the store's forward index
directly and bind all source/target/payload arguments in
straight-line bytecode, with no `PairReader.fromSource(…)` call, no
`Pair<T>` record allocation, and no `world.getComponent` lookup for
target-side reads.

### Decision table

Both APIs are first-class; they just solve different problems.

| You want to…                                              | Use                               | Why |
|-----------------------------------------------------------|-----------------------------------|-----|
| See the *set* of pairs per source entity (sum, max, pick-best) | `@Pair(T.class)`                | The reader gives you `Iterable<Pair<T>>` on the entity; `@ForEachPair` would force you to reassemble the set. |
| See the set of pairs per target entity (`withTarget`)     | `@Pair(T.class)` w/ `role = TARGET` | Same reason, reverse direction. |
| Do one thing per pair, in isolation                       | `@ForEachPair(T.class)`           | Tier-1 bytecode-gen path; ~25% faster than `@Pair` on this benchmark, no allocations per pair. |
| Mix set-oriented reads with per-pair updates              | start with `@Pair`, move the per-pair body into a separate `@ForEachPair` system | Two systems beat one in the scheduler: the set-oriented read can run in parallel with a disjoint-access pair walker. |

## Three comparison points

Bevy 0.15 has no generic relations primitive, so the comparison is a
four-way (two japes shapes × two Bevy shapes):

**japes @Pair** / **japes @ForEachPair** — the two first-class APIs
above. Both use the same `@Relation record Hunting`, the same
`Commands.setRelation` acquisition path, the same `RELEASE_TARGET`
despawn cleanup, the same `RemovedRelations<Hunting>` log drain.
They differ only in the pursuit system's dispatch model.

**Bevy "naive"** does what a first-pass Bevy user actually writes:
store the target `Entity` in a component field, then manually scan
every predator when a prey wants to know who's hunting it.
O(predators × prey) per tick in the awareness system. See
`pp_awareness` in `benchmark/bevy-benchmark/benches/ecs_benchmark.rs`.

**Bevy "optimized"** hand-rolls exactly what the relation store
maintains for japes automatically — a `HuntedBy(Vec<Entity>)`
component on every prey, with two extra writes per `Hunting`
acquisition to keep the reverse index consistent. The awareness
system then reads `hunted_by.0.len()` in O(1) per prey. See
`pp_opt_acquire_hunt` / `pp_opt_awareness` in the same file. This
is the "what if the user ignored the library and wrote it by hand"
upper bound.

## Results

Same 9-cell parameter grid. Lower is better; bold marks each row's
winner; the **japes `@ForEachPair`** column is the library's
recommended shape for per-pair work. Copied verbatim from
`DEEP_DIVE.md`.

| predators | prey | japes `@Pair` | **japes `@ForEachPair`** | Bevy naive | **Bevy optimized** |
|---:|---:|---:|---:|---:|---:|
| 100  |  500 |   8.5 µs |   **6.3 µs** |   14.1 µs |  **1.97 µs** |
| 100  | 2000 |  16.2 µs |  **14.0 µs** |   51.8 µs |  **3.99 µs** |
| 100  | 5000 |  28.0 µs |  **26.4 µs** |  126.3 µs |  **7.30 µs** |
| 500  |  500 |  31.4 µs |  **22.1 µs** |   67.6 µs |  **7.01 µs** |
| 500  | 2000 |  41.1 µs |  **25.9 µs** |  243.7 µs | **11.5 µs** |
| 500  | 5000 |  69.2 µs |  **55.9 µs** |  632.1 µs | **19.13 µs** |
| 1000 |  500 |  62.6 µs |  **43.1 µs** |  128.8 µs | **13.15 µs** |
| 1000 | 2000 |  83.1 µs |  **55.3 µs** |  476.4 µs | **19.68 µs** |
| 1000 | 5000 | 118.7 µs |  **88.4 µs** |   1198 µs | **32.73 µs** |

The honest takeaways are layered.

**japes `@ForEachPair` beats `@Pair` at every cell** by 9–29%.
That's the tier-1 bytecode generator paying off: the generated
`run(long tick)` method walks the `RelationStore.forwardKeysArray()`
directly, caches the source-side storages per archetype transition
(so per-source reads are just one `storage.get` + slot-index load —
no `componentStorage()` lookup, no chunk refetch), hoists the
`Mut<T>` references to locals once per run, and invokes the user
method via plain `invokevirtual` with every component argument in a
local. No `PairReader.fromSource(…)` allocation, no `Pair<T>` record
per pair, no `world.getComponent` call for the target read, no
`SystemInvoker.invoke` reflection. See
`GeneratedPairIterationProcessor` for the full emission logic.

**japes beats naive Bevy at every cell, up to 13.6× at 1000 × 5000.**
The earlier `@Pair`-vs-naive crossover story is gone: even the
set-oriented `@Pair` column wins every cell now, and `@ForEachPair`
extends the lead further. The reverse-index advantage that was
originally masked by constant-factor overhead is now visible from
the smallest workload up.

**japes vs optimized Bevy** — the ratio on the `@ForEachPair` column
sits at **2.4–3.7×** across every cell (it was 11–28× on the first
PR-landing numbers). That remaining gap is structurally out of
reach without giving up features: the japes scheduler pays for
per-pair change tracking (`PairChangeTracker`), deferred `Commands`,
archetype marker maintenance on first/last pair, and
`RemovedRelations<T>` log drain. The hand-rolled Rust version skips
every one of those. Closing the gap further means cutting features
the library exists to provide.

## The optimization journey

The 500 × 2000 cell started at **167 µs/op** at PR-landing
(reflective tier-3 dispatch, `HashMap` forward / reverse indices,
`ArrayList<Pair<T>>` per `fromSource` call, `world.getComponent`
probes per target read). Successive rounds replaced those with:

- a primitive-keyed `Long2ObjectOpenMap`,
- flat `TargetSlice` / `SourceSlice` inner maps,
- per-archetype `ComponentReader` caches on the pair reader,
- `@Pair(role = TARGET)` narrowing,
- tier-1 `@Pair` bytecode generation,
- the tier-1 `@ForEachPair` path documented below,
- per-archetype caching of *every* source-side storage ref (not
  just write storages) so cache-hit transitions skip the chunk
  refetch and the per-source `componentStorage()` lookup entirely,
- a raw-long `forEachPairLong` / `ComponentReader.getById(long)`
  bulk-scan path that avoids per-pair `Entity` allocation in
  cleanup systems,
- a tier-1 bytecode-generated path for service-only `@Exclusive`
  systems,
- a primitive `LongArrayList` utility replacing `ArrayList<Long>`
  in the catch buffer.

End-to-end the cell now runs at **25.9 µs/op** — a **6.45×
speedup** with the API surface staying stable the whole time.

## What the four columns actually tell you

1. **If you need a reverse index, you need a reverse index.** The
   naive Bevy story falls apart hard by 1000 × 5000 (1.2 ms/tick —
   not shippable), and the relations feature exists precisely
   because this shape is common in game code.
2. **`@ForEachPair` is the path to pick when the system only needs
   one pair at a time.** It's tier-1 generated, it walks flat
   arrays, it doesn't allocate. `@Pair` stays for set-oriented
   work where the system genuinely needs to see all the pairs
   attached to one entity.
3. **Use relations for correctness-by-default and now for speed
   too.** The point of automatic reverse-index maintenance is that
   you cannot forget to update it. You cannot forget to drop pairs
   on despawn. You cannot accidentally iterate while mutating. All
   of that is the library's job — and with tier-1 `@ForEachPair`
   the library's job is now within ~3× of a hand-rolled Rust
   reverse index on the same workload.

## How tier-1 `@ForEachPair` generation works

The generator (`GeneratedPairIterationProcessor`, ~790 lines) emits
a hidden class per system using Java's `java.lang.classfile` API,
with a `run(long tick)` method that:

1. Loads `store.forwardKeysArray()` / `forwardValuesArray()` into
   locals once at the start — these are the raw backing arrays of
   the primitive-keyed forward map, exposed by `RelationStore`
   exactly so tier-1 can skip `Long2ObjectOpenMap.forEach` and
   walk the table slot-by-slot.
2. **Outer loop.** Skip null slots, unpack the source `Entity`,
   look up the source's `EntityLocation`.
3. **Per-source archetype cache.** Compare `(archetype, chunkIdx)`
   to the previous source's cache; on a miss, re-resolve every
   source-side component storage and `ChangeTracker` once, store
   them in locals.
4. Load every `@Read` source component value into a local.
5. For every `@Write Mut<T>` slot, `setContext` + `resetValue` the
   reusable `Mut<T>` once per source (*not* once per pair).
6. **Inner loop.** Walk `TargetSlice.targetIdsArray()` /
   `valuesArray()` directly — these are also flat `long[]` and
   `Object[]` backing arrays, again exposed as public by the slice
   so tier-1 can skip the `Iterator`.
7. Per-pair: look up the target's `EntityLocation`, hit a
   target-side archetype cache, load every `@FromTarget @Read`
   value.
8. **Direct `invokevirtual`** to the user method — no
   `MethodHandle`, no `SystemInvoker.invoke`, no reflection. Every
   argument is already in a local.
9. After the inner loop: flush every `@Write Mut` back to the
   source's storage.

Unsupported shapes (≤4 source-reads, ≤2 source-writes, ≤2
target-reads, instance method) fall back to
`PairIterationProcessor` (reflective tier-2), which does the same
thing with `MethodHandle.invokeExact` and per-pair
`world.getComponent` probes. The benchmark shape (1 source-read +
1 source-write + 1 target-read + 1 payload + 1 service) is
comfortably inside the fast path.

Most of the per-cell speedup in the journey above comes from moving
work *out* of the inner loop: archetype caching, per-source `Mut`
setup, hoisted storage arrays. The innermost body is now a
component-load, a component-load, an `invokevirtual`, a store-back —
basically the same shape as a tier-1 per-entity chunk loop, with
the addition of the outer source-iteration scaffolding.

## Remaining v2 levers

- **Tier-1 for `@Exclusive` cleanup systems** — `resolveCatches`
  and `respawnPrey` still go through the reflective exclusive
  path. Worth ~3 samples × ~300 ns per tick; not load-bearing but
  free.
- **Chunk-level source grouping** — when multiple consecutive outer
  slots land in the same source archetype, the current generator
  still redoes the archetype cache probe. Sorting the forward keys
  by archetype at set time would turn the cache into a run-length
  walk.
- **Caller-site change-tracker hoisting** — generator currently
  re-reads `ChangeTracker.dirtySet()` once per source cache miss;
  could be hoisted to once per run.
- **`@ForEachPair(role = TARGET)`** — target-side iteration shape
  (for when you want "one call per pair, but you're writing the
  target, not the source"). v2.

None of these change the API surface. Filed for v2.

## Reproducing

```bash
# japes (Java) — both dispatch shapes on the same workload
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "PredatorPreyBenchmark" \
  -p predatorCount=100,500,1000 -p preyCount=500,2000,5000

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "PredatorPreyForEachPairBenchmark" \
  -p predatorCount=100,500,1000 -p preyCount=500,2000,5000

# Bevy (Rust) — both naive and optimized variants
cd benchmark/bevy-benchmark
cargo bench -- predator_prey/naive_tick
cargo bench -- predator_prey/optimized_tick
```
