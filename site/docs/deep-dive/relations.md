# Relations

**What you'll learn:** why the japes relations subsystem stores pairs
in a side table instead of fragmenting archetypes, what the forward
and reverse indices look like, why the inner maps are flat arrays
rather than hash maps, how the archetype marker components narrow
filters without interfering with storage, and how cleanup policies
handle target despawn safely. Source of truth is
`ecs-core/.../relation/` — `RelationStore.java`, `TargetSlice.java`,
`Long2ObjectOpenMap.java`, `CleanupPolicy.java`,
`PairChangeTracker.java`, `PairRemovalLog.java`.

## The design space

A relation is "entity A has a hunting link to entity B with payload
data P." Users of a typed relation want to ask four questions,
efficiently:

1. **Per-source forward walk.** "For each of my hunt targets, read
   its position." O(pairs-on-this-source).
2. **Per-target reverse walk.** "For each predator hunting me, read
   its velocity." O(pairs-pointing-at-this-target).
3. **Delete a pair** on relationship break. O(1) amortised.
4. **Cascade on target despawn.** When B is despawned, every
   outstanding `Hunting` pair aimed at B must be released or its
   source cascaded — depending on policy — without the user
   remembering to clean up.

There are two obvious implementation approaches:

| Model | How it stores pairs | Forward walk | Reverse walk | Structural cost |
|---|---|---|---|---|
| **Archetype-fragmenting** (Flecs-style) | Each distinct pair `(Relation, Target)` is a separate component type, entities are grouped by archetype over the whole pair set | Fast (tight archetype iteration) | Very expensive (every pair is its own archetype) | Every new target spawns a new archetype; adding/removing a pair migrates the entity |
| **Non-fragmenting side table** (japes) | One side-table per relation type; entities are still grouped by ordinary component set; pairs live outside the archetype graph | Fast (flat slice per source) | Fast (flat slice per target) | Pair add/remove does not touch archetypes |

japes picks the non-fragmenting side table. The reason is cost
asymmetry: archetype fragmentation is cheap for the forward walk but
pathologically expensive for the reverse walk (every target becomes
its own archetype of 1), and the reverse walk is exactly the
question the predator/prey benchmark is built to stress. The side
table keeps both walks O(pairs-touching-this-entity) without
inflating the archetype count.

## `RelationStore` — the per-type container

`RelationStore<T>` (`ecs-core/.../relation/RelationStore.java`) is
the lowest layer of the stack. Each relation `@Relation record T`
gets exactly one `RelationStore<T>` inside `ComponentRegistry`. No
knowledge of the scheduler, no world-level plumbing. Its fields:

```java
private final Class<T> type;
private final ComponentId markerId;         // source-side archetype marker
private final ComponentId targetMarkerId;   // target-side archetype marker
private final CleanupPolicy onTargetDespawn;
private final Long2ObjectOpenMap<TargetSlice<T>> forward;   // source → (target, payload) slice
private final Long2ObjectOpenMap<SourceSlice>  reverse;     // target → source-id slice
private final PairChangeTracker tracker;                    // per-pair added/changed ticks
private final PairRemovalLog removalLog;                    // subscriber log for RemovedRelations
private int size = 0;
```

The store is single-writer. World calls into it from inside a single
executor thread (for commands-flushed pair updates) or from a
`setRelation` call guarded by its own scheduling. No internal
locking.

## Primitive-long keys: `Long2ObjectOpenMap`

The forward and reverse indices are not `HashMap<Entity, ...>`. They
are `Long2ObjectOpenMap` — a minimal open-addressing primitive-long-
keyed map written for this purpose
(`ecs-core/.../relation/Long2ObjectOpenMap.java`, ~225 lines).

The bar for why this is worth ~200 lines of hand-rolled hashmap:

| Cost | `HashMap<Entity, ...>` | `Long2ObjectOpenMap<...>` |
|---|---|---|
| Hash of a key | `Entity.hashCode()` — virtual call, then `Long.hashCode(id)` | `splitmix64` finalizer on the packed id — one XOR/MUL pipeline |
| Equality | `Entity.equals(Entity)` — object compare, field load, long compare | `keys[idx] == key` — one long compare |
| Per-entry object | `HashMap.Node` — 48 bytes with `hash`, `key`, `value`, `next` | zero — just two slots in two parallel arrays |
| Iteration | Walk a linked `Node.next` chain through a sparse 16-slot table | Indexed scan over a `long[]` + `Object[]` pair |
| Cache footprint for one entry | ~192 bytes (Node + 16-slot table) | ~32 bytes (two 2-element arrays + size) |

The **6× memory delta** shows up in the lowest-allocation benchmarks:
500 predators each holding one pair would create 500 `Node`s with
pointer chains, against 500 entries in a flat `long[] + Object[]`
pair for the outer map. The **forward walk cost** is more load-
bearing still: the tier-1 `@ForEachPair` generator walks
`forwardKeysArray()` / `forwardValuesArray()` directly (`RelationStore.java`
lines ~275–278, exposed `public` exactly so tier-1 can bypass any
iterator wrapping):

```java
public long[] forwardKeysArray() { return forward.keysArray(); }
public Object[] forwardValuesArray() { return forward.valuesArray(); }
```

The generator's outer loop is a slot-by-slot walk over that pair of
arrays, skipping `null` values. No `Iterator`, no lambda, no
`HashMap$HashIterator` allocation.

!!! info "Empty-slot convention"
    `Long2ObjectOpenMap` uses `values[i] == null` to mark an empty
    slot. `keys[i]` is unused when the value is null — it may hold
    any stale prior occupant. Zero is therefore a valid key, which
    matters because `Entity.NULL` is packed as a non-zero id and all
    live Entity ids are unique non-zero longs. See
    `Long2ObjectOpenMap.java` lines ~20–24.

## `TargetSlice` and `SourceSlice` — flat inner maps

The inner map (per source, holding every target + payload) is
`TargetSlice<T>` (`ecs-core/.../relation/TargetSlice.java`, 148
lines). It is deliberately *not* a `HashMap`.

```java
public final class TargetSlice<T extends Record> {
    private static final int INITIAL_CAPACITY = 2;

    private long[] targetIds;   // target entity ids
    private Object[] values;    // payloads
    private int size;
    // ... linear scan get / put / remove ...
}
```

The "typical 1–10 pairs per entity" shape the relation feature is
built for makes the trade-off easy: a linear scan over 8 long
comparisons is cheaper than one hash computation + one node
allocation + one pointer chase. The header comment quantifies both
sides:

- **Lookup**: one tight array walk. No hash, no node-chain, no
  `Entity.equals`.
- **Iteration**: plain indexed `for` over contiguous slots. The JIT
  unrolls it and the access pattern is sequential.
- **Memory**: ~32 bytes for a size-1 slice vs ~192 bytes for a
  HashMap.Node + Node[] table. **6× smaller.**

The break-even point against `HashMap` is somewhere around 30–50
entries. For predators with hundreds of simultaneous hunt targets,
this class loses. The header is honest about it: "users who
genuinely need a predator with hundreds of simultaneous hunt pairs
will have to accept that this class won't scale with them."

`SourceSlice` is the symmetric inner map on the reverse side — one
`long[]` of source ids per target.

!!! tip "Raw arrays exposed to tier-1"
    `TargetSlice.targetIdsArray()` and `TargetSlice.valuesArray()`
    are public for the same reason the forward map's are: the
    tier-1 `@ForEachPair` generator walks them directly. "Framework-
    internal — do not mutate," says the Javadoc, but the public
    exposure is the performance-load-bearing detail. The whole
    tier-1 pair-iteration inner loop is two array loads per pair.

## `@Pair` + archetype marker components

Users writing a `@Pair(role = TARGET)` observer want to say "run
this system once per entity that is currently targeted by at least
one `Hunting` pair." Without help, the scheduler can't narrow the
query — every entity is potentially a target. The cost would be a
reverse-lookup per entity per tick.

The fix is an **archetype marker component**. When the
`ComponentRegistry` registers a `@Relation` type, it allocates two
synthetic `ComponentId`s: `markerId` and `targetMarkerId`. These are
real component ids with no user-facing representation — they exist
only to influence archetype membership. Whenever `World` wires up a
pair:

- If the source didn't already have an outgoing `Hunting` pair, add
  `markerId` as a component — the source migrates to the archetype
  with that marker.
- If the target didn't already have an incoming `Hunting` pair, add
  `targetMarkerId` as a component — the target migrates.
- Remove on the last-pair case.

A `@Pair(role = TARGET)` observer query can now require
`targetMarkerId` in its required-component set. `ArchetypeGraph.findMatching`
narrows to archetypes that carry the marker, and the observer walks
only the entities currently being hunted. No reverse-index scan, no
dead-entity skip logic — just a per-archetype iteration, same shape
as any other filtered query.

The registry-level wiring is in `RelationStore`'s constructor
arguments; see lines ~91–97:

```java
public RelationStore(Class<T> type, ComponentId sourceMarkerId,
                      ComponentId targetMarkerId, CleanupPolicy onTargetDespawn) { ... }
```

and the getter pair:

```java
public ComponentId markerId() { return markerId; }
public ComponentId targetMarkerId() { return targetMarkerId; }
```

Both `null` for stores built outside the registry (unit-test path).

!!! info "`@Pair(role = TARGET)` is narrow-by-construction"
    See [the relations tutorial](../tutorials/relations/18-pair-and-pair-reader.md)
    for the user-facing API. The deep-dive point is: the narrowing
    happens at the archetype-matching layer, not at a per-entity
    check layer. Adding a `@Pair(role = TARGET)` observer is
    scheduler-free — no extra runtime logic.

## Cleanup policies

`CleanupPolicy` (`ecs-core/.../relation/CleanupPolicy.java`, 38
lines) enumerates what happens when a target entity is despawned
while pairs still point at it:

| Policy | What happens | When to pick it |
|---|---|---|
| `RELEASE_TARGET` *(default)* | Drop every pair pointing at the despawned target. Each source observes this as a normal pair removal — tracker update, removal-log entry, source loses its marker if it runs out of pairs. | Almost always. The source entity itself is unaffected. |
| `CASCADE_SOURCE` | Despawn every source that pointed at the target. Transitively: sources of those sources get cleaned up in the same pass, bounded by the entity count. | Parent-child hierarchies where the child cannot exist without the parent. |
| `IGNORE` | Leave the pairs in place. Reads can return pair data whose target is no longer alive. User is responsible for liveness checks. | Escape hatch for tests / migration, not recommended in production. |

The cascade walk is cycle-safe because it runs as a **deferred
process** during the cleanup pass, not inline inside `despawn`.
`World` gathers the full list of despawns-to-perform in one queue,
walks it breadth-first until the queue is empty, and de-duplicates
via the entity allocator's liveness check. An entity already freed
is a silent no-op. A pair pointing at the despawning entity that is
itself the source of another pair being despawned in the same wave
is handled correctly — the entity shows up in the queue once and
triggers its own pairs' cleanup exactly once.

## `PairChangeTracker` and `PairRemovalLog`

The relation subsystem mirrors the component change-tracking stack,
one layer up:

- **`PairChangeTracker`**
  (`ecs-core/.../relation/PairChangeTracker.java`, 126 lines) — per-
  pair `added`/`changed` ticks keyed on `PairKey(source, target)`,
  plus a dirty list with membership-set dedup, plus a
  `fullyUntracked` flag set at plan-build time when no system
  declares a `@Filter(Added/Changed, target = T.class)` observer
  over the relation type. The API is the same shape as
  `ChangeTracker` but keyed on `PairKey` identity instead of
  archetype slot.
- **`PairRemovalLog`** — append-only log of dropped pairs with per-
  subscriber watermarks, the direct parallel of the component-level
  `RemovalLog` that backs `RemovedComponents<T>`. Read by
  `RemovedRelations<T>` consumers, pruned at end of tick with the
  minimum watermark across all subscribers.

The split between the two is load-bearing for the same reason it's
load-bearing on the component side: `@Filter(Changed)` wants a live
dirty slot view, while `RemovedRelations<T>` wants a history of
dropped pairs with per-subscriber cursors. One data structure
cannot efficiently do both — a dirty list is tick-local and a
removal log is subscriber-local.

!!! tip "Fully-untracked short-circuit"
    Just like the component-level `ChangeTracker`, `PairChangeTracker`
    has a `fullyUntracked` flag that makes `markAdded` / `markChanged`
    full no-ops when no observer exists. `World` sets it at plan-
    build time after scanning every system for filter targets. On
    the predator/prey benchmark, the `Hunting` relation has no
    `@Filter` observer, so its pair change tracker does nothing on
    every pair set — the cost is one branch per mutation.

## Two dispatch models on top of the store

The store is the data. `@Pair` and `@ForEachPair` are the user-
facing dispatch shapes. The design rationale for each shape is
already detailed in `DEEP_DIVE.md`, but the short version:

- **`@Pair(T.class)`** — set-oriented dispatch. System runs once per
  entity with at least one pair; the user body walks
  `PairReader.fromSource(self)` to see the full set. Right default
  when the system needs to see the whole set at once — sum, max,
  pick-best.
- **`@ForEachPair(T.class)`** — tuple-oriented dispatch. System
  runs once per live pair. Parameters bind directly to source
  components, target components (`@FromTarget`), the payload, and
  service arguments. Right default when the system cares about one
  pair in isolation. **Tier-1 bytecode-generated:** the generator
  walks `forwardKeysArray` / `forwardValuesArray` and the inner
  `TargetSlice` arrays directly, and invokes the user method with
  every argument in a local. The core win documented throughout the
  [optimisation journey](optimization-journey.md).

Both shapes run against the same `RelationStore`. Switching between
them is a one-line annotation change — the underlying data is the
same.

## What you never have to do by hand

Putting the pieces together, here is the list of correctness
properties the store maintains for you, none of which the hand-
rolled Bevy "optimised" version in the predator/prey benchmark
handles without bookkeeping:

- **Reverse index stays consistent** with the forward index on
  every `setRelation` and `removeRelation` — you cannot forget to
  update one side.
- **Target despawn releases every pair** pointing at the target —
  you cannot leave dangling reverse-index entries. Policy is
  configurable, default is safe.
- **Last-pair marker removal** — the source loses its archetype
  marker the moment its last outgoing pair is removed, so
  `@Pair` / `@ForEachPair` observers stop seeing it without you
  writing any gating code.
- **Change detection** for pairs, if you want it. Free if you
  don't.
- **Removed-pair log with per-subscriber cursors** so
  `RemovedRelations<T>` works the same way `RemovedComponents<T>`
  does.

Every one of those is a bug that a hand-rolled dirty-list approach
can silently introduce; every one of them is absent from the user's
code when they use the store.

## Related

- [Optimisation journey](optimization-journey.md) — every relations
  speedup round, with profile evidence, in one place
- [Tier-1 bytecode generation](tier-1-generation.md) — how
  `GeneratedPairIterationProcessor` emits the outer-loop walk over
  `RelationStore.forwardKeysArray()`
- [Change tracking](change-tracking.md) — the component-level
  counterpart of `PairChangeTracker`
- [Tutorial — Overview of relations](../tutorials/relations/17-overview.md)
  — the user-facing API for the machinery described here
- [Tutorial — `@ForEachPair`](../tutorials/relations/19-for-each-pair.md)
  — the tier-1 fast path
- [Tutorial — Cleanup policies](../tutorials/relations/20-cleanup-policies.md)
  — `RELEASE_TARGET` vs `CASCADE_SOURCE` in practice
