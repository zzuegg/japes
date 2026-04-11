# Optimisation journey — 167 µs to 32 µs on 500×2000

**What you'll learn:** how the 500-predator × 2000-prey cell of
`PredatorPreyForEachPairBenchmark` went from 167 µs/op at PR-landing
to 32.0 µs/op today — a **5.22× speedup** with the public API
staying stable the whole time. This is the narrative companion to
[benchmarks/predator-prey](../benchmarks/predator-prey.md), which
owns the tables. Every round below is tied to the code file it
landed in and, where possible, the rough profile evidence that
motivated it.

## Why this cell

`PredatorPreyForEachPairBenchmark` at 500 × 2000 is the reference
workload for the relations subsystem. It exercises every relation
hot path in one tick:

- Forward pair walk — per-predator "steer toward my hunt target"
  system.
- Reverse pair walk — per-prey "how many predators are hunting me"
  system.
- `RELEASE_TARGET` despawn cleanup.
- `RemovedRelations<Hunting>` log drain.
- `Commands.setRelation` to acquire new hunt targets on idle
  predators.

Everything the relation store can do runs at least once per tick.
If something regresses in the store, this cell notices. If something
improves, this cell shows it first.

## The starting point

Tier-landing numbers:

- **500 × 2000**: 167 µs/op

Profile was dominated by three items, in rough order:

1. `HashMap<Entity, ...>` dispatch on the forward and reverse
   indices — ~35–40 %.
2. `ArrayList<Pair<T>>` allocation per `PairReader.fromSource`
   call, plus the `Pair<T>` record allocation per pair — ~20 %.
3. `World.getComponent` lookups on every target-side read — ~15 %.

Everything else (reflective dispatch, per-chunk storage refetch,
unpruned change trackers) was buried beneath those three.

## Round 1: primitive-long keys

**Change.** Replaced `HashMap<Entity, TargetSlice<T>>` with a
hand-rolled `Long2ObjectOpenMap<TargetSlice<T>>`
(`ecs-core/.../relation/Long2ObjectOpenMap.java`). Same for the
reverse map.

**What it fixed.** `Entity.hashCode()` virtual call on every lookup
went away. So did `HashMap.Node` allocation per new entry. So did
the linked-node walk for collision resolution — the open-addressing
probe is a plain `keys[idx] == key` long compare in the same cache
line as the value slot.

**Profile impact.** Drove the "outer map dispatch" line item in the
profile from ~35 % to <10 %. Also cut memory footprint of the
forward/reverse indices by roughly 6× on small slice sizes, which
matters less for iteration but materially helps L1/L2 residency.
See the [relations deep-dive](relations.md) for the memory
comparison table.

## Round 2: flat `TargetSlice` / `SourceSlice`

**Change.** The inner map — `target Entity → payload` for a single
source — used to be a `HashMap<Entity, T>`. Replaced with the
parallel-array `TargetSlice<T>` (two flat arrays + a size field).
Same swap applied to `SourceSlice` on the reverse side.

**What it fixed.** The "typical 1–10 pairs per source" shape makes
a linear scan over `long[]` ids cheaper than a hash + node walk. The
initial capacity is 2. For the benchmark's three-pairs-per-predator
workload, the inner loop is at most three long compares.

**Profile impact.** Dropped `fromSource`-path cost substantially.
Also let the next round hoist raw array refs into tier-1 locals.

## Round 3: per-archetype `ComponentReader` cache on `PairReader`

**Change.** `PairReader.fromSource(self)` used to construct a new
`PairReader` on every call with fresh per-archetype state. Added a
per-archetype `ComponentReader<T>` cache so that when a system walks
many sources and most live in the same archetype (as is always the
case in the predator/prey benchmark — all predators share one
archetype), the reader resolves each target-component storage
exactly once per archetype transition instead of once per pair.

**What it fixed.** `world.getComponent(target, Position.class)`
went from one-lookup-per-pair to one-lookup-per-archetype-transition.

**Profile impact.** Moved `World.getComponent` out of the top 5
hot methods for the first time.

## Round 4: `@Pair(role = TARGET)` archetype marker

**Change.** Introduced a target-side archetype marker component
(`RelationStore.targetMarkerId`, `ecs-core/.../relation/RelationStore.java`
line ~43). When a target acquires its first incoming pair,
`World` adds the marker to the target's archetype; when it loses
its last incoming pair, the marker is removed.

**What it fixed.** `@Pair(role = TARGET)` observer systems can now
require `targetMarkerId` in their query and the
`ArchetypeGraph.findMatching` call narrows to "archetypes whose
entities are currently being targeted." No per-entity liveness
check, no reverse-index scan, no dead-entity skip.

**Profile impact.** The "how many predators are hunting me"
observer dropped from a scan over every prey to a scan over the
prey actually under pursuit. At the 500 × 2000 cell, roughly 3×
fewer observer invocations per tick.

## Round 5: tier-1 `@Pair` bytecode generation

**Change.** Moved the `@Pair` dispatch path from the reflective
tier-2 `PairIterationProcessor` to a tier-1 bytecode-generated
`GeneratedPairIterationProcessor` — the hidden-class model
documented on the [tier-1 generation page](tier-1-generation.md).

**What it fixed.** Every `@Pair` system call stopped going through
`MethodHandle.asSpreader(Object[])` and started going through a
direct `invokevirtual` in generated bytecode. The user method
inlined into the iteration loop.

**Profile impact.** ~1.3 ns per pair disappeared from the per-
invocation cost. On the order of ~6 µs per tick on this cell.

## Round 6: tier-1 `@ForEachPair`

**Change.** Added a second tier-1 generator specifically for
`@ForEachPair(T.class)` systems —
`GeneratedPairIterationProcessor`. The emitted class walks
`RelationStore.forwardKeysArray()` / `forwardValuesArray()`
directly, caches source-side storages per archetype transition,
hoists `Mut<T>` references to locals once per run, loads every
`@Read` component value into a local, and invokes the user method
via direct `invokevirtual`. No `PairReader.fromSource` allocation,
no `Pair<T>` record per pair, no `world.getComponent` call for the
target read. See the dedicated design notes at the top of
`GeneratedPairIterationProcessor.java`.

**What it fixed.** The whole `@ForEachPair` dispatch model became
an inner loop the JIT could inline end-to-end. The code shape is
the same as a per-entity chunk loop, with outer-loop source
iteration scaffolding on top.

**Profile impact.** The `@ForEachPair` dispatch column on the
benchmark went from ~40 µs to ~32 µs. The "8 µs left on the table"
was where the next round of storage caching lived.

!!! tip "Why two tier-1 generators for relations?"
    `@Pair` is set-oriented (once per source), `@ForEachPair` is
    tuple-oriented (once per live pair). They have different
    parameter-binding rules and different inner-loop shapes. One
    generator could handle both, but the emission logic would
    branch on the shape for most of its body. Splitting them
    produced a simpler emission path for each and let the
    `@ForEachPair` generator focus on the flat-array walks
    specifically.

## Round 7: per-archetype storage cache — reads too, not just writes

**Change.** The first cut of tier-1 `@ForEachPair` cached only the
source-side **write** storage refs on archetype transition — the
assumption was that reads are cheap enough to refetch per pair.
This round extended the cache to cover *every* source-side
storage (reads included). On a cache hit (consecutive sources in
the same archetype, which the benchmark workload produces almost
entirely), the per-source preamble collapses to a single
`(archetype, chunkIdx)` identity compare and the subsequent per-
pair loop skips the chunk refetch and the per-source
`componentStorage` lookup entirely.

**Profile impact.** Cut ~10 % off the 500 × 2000 cell. Per-pair
inner body became: `aaload posArr; aaload tgtPosArr; invokevirtual;
aastore velArr`. That's as tight as the JVM can get without flat
arrays.

## Round 8: `forEachPairLong` + `ComponentReader.getById(long)`

**Change.** Added a raw-long bulk walk on the store
(`RelationStore.forEachPairLong`, line ~334) and a
`ComponentReader.getById(long)` overload. Cleanup systems that
scan every live pair (e.g. `resolveCatches`) could now go directly
from packed `long` ids to component values without allocating a
per-pair `Entity` record.

**What it fixed.** The `Entity` allocation in the inner loop of
scan-style cleanup code. Previously every pair visited in a
bulk scan produced two `new Entity(id)` allocations crossing the
`PairConsumer<T>` interface boundary.

**Profile impact.** Measurable on GC pressure — the benchmark's
allocation rate dropped noticeably — and ~1 µs on the cell.

## Round 9: tier-1 `@Exclusive` cleanup systems

**Change.** `resolveCatches` and `respawnPrey` are `@Exclusive`
service-only systems. They used to dispatch through
`SystemInvoker.invoke`. Added a third tier-1 generator,
`GeneratedExclusiveProcessor`
(`ecs-core/.../system/GeneratedExclusiveProcessor.java`, ~177
lines), that emits a hidden class with a `run()` method doing
straight-line `aload args; ldc i; aaload; checkcast P` per
parameter, followed by a single `invokevirtual` (or `invokestatic`
for static methods).

**What it fixed.** ~3 sample invocations × ~300 ns per tick of
reflective `MethodHandle` dispatch. Not load-bearing but free.

**Profile impact.** Less than 1 µs on the cell. Kept it because
it's a simple change that also makes the dispatch model
consistent across all three system shapes.

## Round 10: primitive `LongArrayList` in the catch buffer

**Change.** The catch-resolution system maintained an
`ArrayList<Long>` of entities caught this tick. Replaced with a
hand-rolled primitive `LongArrayList` utility.

**What it fixed.** Autoboxing of every caught entity id on append,
and unboxing on drain. 500 ints per tick, turned into 500
primitive long writes.

**Profile impact.** Small but visible on the GC profile.

## What *didn't* help

A few rounds were tried and reverted or filed for v2 because the
profile evidence didn't justify them.

**Source grouping by archetype at `setRelation` time.** The theory
was that sorting the forward-map keys into archetype-grouped runs
would let the tier-1 generator's per-source archetype cache hit
more often. In practice, **the benchmark has exactly one predator
archetype**, so the cache hit rate was already ~100 % — there was
no re-sort gain to be had. This will matter on workloads with
multiple source archetypes; filed for v2 when such a workload
lands in the suite.

**Per-pair `ChangeTracker` on the hot path.** An attempt to avoid
even the one-branch `isFullyUntracked()` check on
`PairChangeTracker.markChanged` by short-circuiting at the
generator level was tested. It didn't move the number on this
benchmark because the tracker is already fully untracked (no
`@Filter(Hunting)` observer), so the branch is perfectly predicted
and folds into the load. Not applied.

**Stuffing `Mut.flush` into a static helper for the pair runner.**
Tried; the extra call frame confused the JIT inliner and the
numbers regressed. Reverted.

**Caller-site change-tracker hoisting.** The generator currently
re-reads `ChangeTracker.dirtySet()` once per source cache miss.
Hoisting to once per run is on the v2 list — small win, easy
change, not yet shipped.

## Running total

| Round | Change | Cell µs (approx) | Delta |
|---|---|---:|---:|
| 0 | PR-landing (reflective tier-3, HashMaps everywhere) | ~167 | — |
| 1 | `Long2ObjectOpenMap` primitive-long keys | ~120 | -47 |
| 2 | Flat `TargetSlice` / `SourceSlice` | ~95 | -25 |
| 3 | Per-archetype `ComponentReader` cache on `PairReader` | ~78 | -17 |
| 4 | `@Pair(role = TARGET)` archetype marker | ~65 | -13 |
| 5 | Tier-1 `@Pair` bytecode generation | ~55 | -10 |
| 6 | Tier-1 `@ForEachPair` | ~42 | -13 |
| 7 | Per-archetype read-storage cache in the pair runner | ~38 | -4 |
| 8 | `forEachPairLong` / `ComponentReader.getById` | ~35 | -3 |
| 9 | Tier-1 `@Exclusive` cleanup | ~34 | -1 |
| 10 | Primitive `LongArrayList` catch buffer | 32.0 | -2 |

Per-round deltas are rough — each round's measurement is from that
branch's JMH run, and workload variance between rounds is part of
the motion. The cumulative **5.22× end-to-end speedup** is the
solid number.

## API stability through the journey

Every round above is a strict implementation change. The user-
facing API didn't move once:

- `@Relation record Hunting(...)` — same declaration shape.
- `@System @ForEachPair(Hunting.class)` — same annotation.
- `@Read Position sourcePos, @Write Mut<Velocity> sourceVel,
  @FromTarget @Read Position targetPos, Hunting hunting` — same
  parameter binding.
- `Commands.setRelation` — same call shape.
- `RemovedRelations<Hunting>` — same service parameter.
- `@Relation(onTargetDespawn = RELEASE_TARGET)` — same cleanup
  policy enum.

The user's code compiled and ran unchanged from PR-landing to
32.0 µs. Every performance round was picked up for free on library
upgrade.

This is what the tier-1 generator is for. The tight iteration
loop that the user *would* write if they hand-rolled a relations
subsystem is the tight iteration loop the framework emits on their
behalf, and the emission path can improve without touching the
call sites.

## Related

- [Benchmarks — predator / prey](../benchmarks/predator-prey.md) —
  the full per-cell table across every grid point
- [Relations](relations.md) — the data structures every round
  above was either improving or leveraging
- [Tier-1 bytecode generation](tier-1-generation.md) — the
  machinery that rounds 5, 6, and 9 built on
- [Write-path tax](write-path-tax.md) — the counterexample: on
  write-heavy benchmarks, no amount of iteration optimisation
  closes the gap to mutable-POJO libraries; the cost is in the
  API contract itself
- [Tutorial — Predator / prey example](../tutorials/relations/22-predator-prey.md)
  — the benchmark workload, explained for users
