# Architecture — archetype + chunk storage

**What you'll learn:** how japes lays out components in memory, why
it picked an archetype model over sparse sets or a single table-of-
structs, how an entity's physical address is encoded in
`EntityLocation`, and why moving an entity between archetypes is just
a pair of swap-removes on parallel arrays. The goal is that by the
end, you can read any hot path in `ecs-core` and know which data
structure every field load is walking.

## The three candidates

Before getting to what japes does, the shape of the decision itself is
worth spelling out. Every ECS storage layer picks one of roughly three
ways to arrange components in memory:

| Model | How it stores things | Iteration cost | Structural-change cost |
|---|---|---|---|
| **Sparse set** (e.g. EnTT) | One dense `T[]` per component type, plus an entity→slot redirect table | Cheapest per-component iteration; can pack to `N` slots where `N` is entities *with that component* | Cheap add/remove — just append/swap-remove in the dense array |
| **Table-of-structs** | One big table, every entity has every component, unused fields default | Tightest cache layout when most entities have most components | Every add of a new component type reshapes the whole table |
| **Archetype + chunk** (japes, Bevy, Flecs) | Group entities by *component set*; each group owns a list of fixed-capacity chunks; each chunk holds one parallel column per component | Close to sparse-set per-component, still competitive on multi-component queries | Structural change = move between archetypes; pair of swap-removes |

japes picks the third. The decision is driven by three specific
requirements:

1. **Multi-component queries must be fast.** A `@System` that reads
   `Position` and `Velocity` together needs the two component arrays
   to be pointer-equal-indexable — slot `i` in the `Position[]` and
   slot `i` in the `Velocity[]` must describe the same entity. Sparse
   sets require an indirection through the entity→slot table on every
   second and subsequent component; archetype chunks make it a flat
   indexed load.
2. **Change detection has to be free when nobody is watching.**
   Every chunk carries one `ChangeTracker` per component; trackers
   for components with no observer are flipped to
   `fullyUntracked = true` and become no-ops. This lines up naturally
   with chunk-per-archetype, because the set of observers for a
   component is statically resolved at plan build.
3. **Entity handle stability under structural change.** Users hold
   `Entity` (a packed `long` — see
   `ecs-core/.../entity/Entity.java`) across ticks. The archetype
   model makes the handle stable through component add/remove: the
   entity moves between archetypes, but its `Entity` value
   (`index << 32 | generation`) is unchanged, and a single table
   update in the entity allocator re-points it.

## The shape of an archetype

An `Archetype` (`ecs-core/.../archetype/Archetype.java`) is the
container for every entity that carries exactly the same set of
component types. Its fields:

```java
final ArchetypeId id;                               // sorted set of ComponentId
final Map<ComponentId, Class<? extends Record>> componentTypes;
final int chunkCapacity;                            // how many entities per chunk
final List<Chunk> chunks = new ArrayList<>();
final Set<ComponentId> dirtyTrackedComponents;      // shared with ArchetypeGraph
final Set<ComponentId> fullyUntrackedComponents;    // shared with ArchetypeGraph
int openChunkIndex = -1;                            // lazy open-slot cache
```

Every time a new archetype is created (because some entity gained or
lost a component), the `ArchetypeGraph` memoises the transition in its
`addEdges` / `removeEdges` map
(`ecs-core/.../archetype/ArchetypeGraph.java`, ~lines 116–134) so the
next entity making the same move skips the `ArchetypeId.with(...)`
computation entirely. That's the "graph" in `ArchetypeGraph`: a lazy,
pay-as-you-go index of "add component X from this archetype goes to
*that* archetype." New nodes are materialised on first visit.

!!! tip "Cached open chunk"
    `openChunkIndex` used to be a linear scan on every add — O(chunks).
    Profiling `ParticleScenarioBenchmark` (which respawns ~100 entities
    per tick) showed it sitting at a measurable fraction of tick time.
    It now starts at -1, is bumped on `add` when the selected chunk is
    still non-full, and is reset to the last-modified chunk on `remove`.
    Every live chunk is either the open one or full, so the "find the
    next open slot" operation is one integer comparison. See
    `Archetype.findOrCreateChunkIndex` at line ~106.

## A chunk is parallel `Object[]` columns

`Chunk` (`ecs-core/.../storage/Chunk.java`) holds `chunkCapacity`
entity slots. For each component type in the archetype, the chunk
allocates **one** `ComponentStorage` — in the default configuration
(`DefaultComponentStorage`), this is a single reference array sized to
`capacity`. So one chunk with 1024 slots carrying
`{Position, Velocity, Health}` physically owns:

- `Entity[1024] entities` — the handle list
- `Position[1024]` behind a `ComponentStorage<Position>`
- `Velocity[1024]` behind a `ComponentStorage<Velocity>`
- `Health[1024]` behind a `ComponentStorage<Health>`
- One `ChangeTracker` per component, also sized to 1024

Slot `i` in every array describes the same logical entity. A tight
iteration loop over `Position` + `Velocity` is a single
`for (int slot = 0; slot < count; slot++)` walking two parallel
indexed loads — exactly what the tier-1 generator emits (see
[tier-1 generation](tier-1-generation.md)).

!!! info "Why one storage per component per chunk — not one per archetype"
    An earlier prototype kept one `ComponentStorage` per archetype and
    segmented it into logical ranges. That was trivially shown to
    regress both iteration (the loop had to bounds-check chunk edges)
    and structural change (moving an entity out of a chunk left a hole
    that could not be swap-filled from anywhere except the same chunk).
    One storage per chunk gives the swap-remove trick a clean domain:
    every component column knows its own chunk-local `count`, and its
    last-slot is always the tail of *this* chunk.

### Flat `ComponentStorage<?>[]` lookup by id

Component lookups (`Chunk.componentStorage(ComponentId)`,
`Chunk.changeTracker(ComponentId)`) used to be `HashMap` gets. They're
now flat arrays indexed by `ComponentId.id()`
(`Chunk.java` lines ~19–27):

```java
private final ComponentStorage<?>[] storagesById;
private final ChangeTracker[] changeTrackersById;
```

Sized to `maxGlobalComponentId + 1`, with `null` in every slot the
chunk doesn't own. Two hot paths — `World.setComponent` and every
tier-1 generator's "load storages at chunk entry" preamble — now do
`storagesById[id]` instead of `storages.get(compIdObject)`. That is
one aaload versus a hash, an equals call, and a node walk. On the
sparse-delta benchmark this single change was worth several µs.

A second pair of flat arrays — `storageList` and `trackerList` — holds
the *same* storages and trackers indexed densely, so whole-chunk
sweeps (`remove`, `markAdded`) can iterate without touching `null`
slots.

## EntityLocation: the entity-to-slot table

`EntityAllocator` (`ecs-core/.../entity/EntityAllocator.java`) hands
out `Entity` handles and owns the `Entity → EntityLocation` table.
`EntityLocation` (`ecs-core/.../archetype/EntityLocation.java`) is a
record:

```java
public record EntityLocation(Archetype archetype, int chunkIndex, int slotIndex) {
    public ArchetypeId archetypeId() { return archetype.id(); }
}
```

Two things are worth noticing.

**It holds a direct `Archetype` reference, not an `ArchetypeId`.**
The comment at the top of the file explains why:
`World.setComponent` can now skip the `archetypeGraph.get(archetypeId)`
map lookup entirely — the archetype is reachable in a single field
load from the location. `archetypeId()` still exists but just
forwards.

**It does NOT hold a direct `Chunk` reference.** A previous revision
tried that and it regressed `setComponent`-heavy workloads by ~9 %.
The JIT was able to hoist `archetype.chunks().get(chunkIndex)` out of
the loop when the archetype was stable, whereas `location.chunk()`
varies per entity and prevents the hoist. The comment on
`EntityLocation.java` documents this exactly. The indirection is
cheaper than the cache pollution.

## Component registry keys are `Class<?>`

`ComponentRegistry` keys every component type by the `Class` object
itself. No string interning, no annotation scanning, no `String.hashCode`
on the hot path. The resulting `ComponentId` is a densely-packed
integer — `ComponentId.id()` — allocated in registration order.

Two consequences:

- Flat `ComponentStorage<?>[]` arrays indexed by `id()` work because
  ids are dense.
- The per-system metadata (`SystemDescriptor`) can resolve every
  component reference to its `ComponentId` once at plan build time, so
  the hot path never touches a `Class` object again.

## Moves between archetypes are swap-removes

Suppose entity `E` lives in archetype `A = {Position, Velocity}`, and
a system adds a `Health` component. `E` must move to
`B = {Position, Velocity, Health}`. The sequence is:

1. Look up `B = archetypeGraph.addEdge(A.id(), HealthId)` — memoised,
   O(1) after the first hit.
2. Allocate a new slot in `B`'s open chunk.
3. Copy every shared component (`Position`, `Velocity`) from `E`'s old
   slot to the new slot.
4. Write the newly-added `Health` into the new slot.
5. **Swap-remove** `E`'s old slot in `A`: overwrite it with the last
   entity in the same chunk, decrement `count`, and update the swapped
   entity's `EntityLocation` to point at `E`'s old index. This is
   `Archetype.remove` + `Chunk.remove` — a per-storage, per-tracker
   swap-remove loop.
6. Update `entityLocations[E.index()] = new EntityLocation(B, newChunkIdx, newSlot)`.

Every column in the chunk does its swap-remove the same way — the
code is in `Chunk.remove`:

```java
public void remove(int slot) {
    int lastIndex = count - 1;
    if (slot < lastIndex) {
        entities[slot] = entities[lastIndex];
    }
    for (var storage : storageList) {
        storage.swapRemove(slot, count);
    }
    for (var tracker : trackerList) {
        tracker.swapRemove(slot, count);
    }
    entities[lastIndex] = null;
    count--;
}
```

The per-component `swapRemove` is one field load, one field store,
one null-write — constant-time per column, linear in the number of
components the archetype carries (typically 2–6).

!!! warning "The dirty-bit propagation bug"
    `ChangeTracker.swapRemove` has to propagate the dirty bit of the
    *moved* entity, because the slot index that slot held is gone.
    Otherwise entities that were dirty at the moment another entity
    was swap-removed become invisible to every `@Filter(Changed)`
    observer. This was a real silent-correctness bug fixed in the
    PR referenced in `DEEP_DIVE.md`. The fix is in
    `ChangeTracker.swapRemove` lines ~177–204 — see the
    [change tracking page](change-tracking.md) for the detail.

## Archetype generation counter

`ArchetypeGraph` bumps a `long generation` every time a new archetype
is materialised (`getOrCreate`, line ~54). The generation is load-
bearing for one very specific optimisation:
`SystemExecutionPlan.cachedMatchingArchetypes`.

Query systems re-resolve "which archetypes do I iterate" at tick
start. Without a cache, this walks `findMatchingCache` — itself a
`ConcurrentHashMap<Set<ComponentId>, List<Archetype>>` — and
`AbstractSet.hashCode` walks every element of the required set on
every call. Profiling showed it was ~18 % of a `RealisticTick` tick.

The fix is two-level:

- `findMatchingCache` (at the graph level) memoises the result by
  required-set identity. Rebuilt only when a new archetype is
  created.
- `SystemExecutionPlan.cachedMatchingArchetypes(graphGeneration)` (at
  the plan level) memoises the lookup by `long` generation. Tick
  start compares two longs; if they match, the cached list is still
  valid.

One long comparison per system per tick instead of one set hash. The
generation counter is the whole reason it works.

## What happens on `World.setComponent`

This is the hottest write path in the library. The full sequence:

1. `entityAllocator.locationOf(entity)` returns the cached
   `EntityLocation` — one array load.
2. `location.archetype()` is the direct `Archetype` reference — one
   field read.
3. `archetype.chunks().get(location.chunkIndex())` — one `ArrayList`
   get.
4. `chunk.componentStorage(compId)` — one `storagesById[id]` aaload.
5. `storage.set(slot, value)` — one aastore.
6. `chunk.changeTracker(compId).markChanged(slot, currentTick)` — one
   aaload, one `addedTicks[slot] = tick` store, one dirty-list append
   (if observed).

**Five loads, two stores, one conditional append.** No hash lookups,
no reflection, no boxing (the `value` is already a record reference
held by the user). Every stretch where this path gets slower is
visible in the benchmark numbers; every optimisation round in the
[optimisation journey](optimization-journey.md) is a line-level change
to this sequence.

## Related

- [Tier-1 bytecode generation](tier-1-generation.md) — how the per-entity
  iteration loop binds to the column layout described above
- [Change tracking](change-tracking.md) — how `ChangeTracker` plugs into
  the archetype / chunk machinery
- [Relations](relations.md) — the side-table alternative for
  entity-to-entity pairs, which does *not* fragment archetypes
- [Optimisation journey](optimization-journey.md) — the war story for
  some of the specific changes referenced above
- [Tutorial — Components](../tutorials/basics/01-components.md) — the
  user-facing view of what a record-based component looks like
