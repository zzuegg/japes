# Change tracking

**What you'll learn:** how japes implements `@Filter(Added)` and
`@Filter(Changed)` efficiently enough that observer systems scale
with the number of dirty entities, not the number of total entities.
We'll cover the per-component `ChangeTracker`, the dirty bitmap and
dirty list it maintains, the per-system `lastSeenTick` watermark, why
strict-`>` comparisons against that watermark are correct, and the
tick-0 "untracked" sentinel. Source of truth is
`ecs-core/.../change/ChangeTracker.java` (~210 lines) and the
change-filter path in `SystemExecutionPlan.processChunk` (lines
~227–270).

## What the user wrote, and what has to happen

Here's the user-facing API:

```java
@System(stage = "PostUpdate")
@Filter(value = Changed.class, target = Health.class)
void observe(@Read Health h) {
    // react to mutated health values
}
```

The contract is: on every tick, this method runs exactly once per
entity whose `Health` component was mutated since the last time the
method ran. No duplicate invocations per mutation, no missed
invocations if the user wrote the same entity twice, no re-running
for entities that were merely iterated past. And critically: the
method should run in O(dirty) time, not O(entities-with-Health).

Cost model on [the realistic multi-observer tick](../benchmarks/realistic-tick.md),
for 100 dirty entities per tick at 100k total:

- **japes / Zay-ES**: dirty-list skip — O(K) where K is dirty count.
  Scales ~1.3× from 10k → 100k.
- **Bevy**: full archetype scan with `Changed<T>` — O(N).
  Scales ~9× over the same range.
- **Dominion / Artemis**: no built-in change detection, full scan at
  the user level — O(N).

japes wins this benchmark because the library maintains a
dirty-per-component data structure *and* exposes a sparse iteration
path that consumes it without rebuilding the query.

## Per-component `ChangeTracker` per chunk

Every chunk carries one `ChangeTracker` per component type the chunk
owns. It is allocated in `Chunk`'s constructor
(`Chunk.java` lines ~54–68). Its fields:

```java
private final long[] addedTicks;     // per-slot
private final long[] changedTicks;   // per-slot
private final long[] dirtyBits;      // per-slot membership bitmap
private int[] dirtyList;             // per-slot, insertion order
private int dirtyCount;
private boolean dirtyTracked = false;
private boolean fullyUntracked = false;
```

Four ideas live on top of this layout.

**Added and Changed are independent.** A newly-spawned entity has its
`addedTick` stamped but *not* its `changedTick`. This matches Bevy's
semantics: `@Filter(Added)` sees spawns once, `@Filter(Changed)`
does not see spawns unless the entity is subsequently mutated. See
the comment at `markAdded` (line ~95):

> Intentionally not updating changedTicks: 'added' and 'changed' are
> independent in Bevy-style change detection. Newly spawned entities
> are observable via @Filter(Added) but not @Filter(Changed) until
> something actually mutates them.

**Dedup'd dirty list.** Marking the same slot dirty twice in one
tick is a no-op on the list. The `dirtyBits` bitmap is a one-bit-per-
slot membership set: `appendDirtyUnchecked` checks the bit before
appending and sets it on first insert. So a busy loop that mutates
the same entity 100 times in one tick still produces one list entry,
which the observer visits once. Dedup is structurally free — one bit
test on append.

**Per-tracker tick arrays are linearly indexed by slot.** Slot `s`
in the `Health` storage shares `s` with `addedTicks[s]` and
`changedTicks[s]`. No indirection, no map.

**Dirty bits, dirty list, and tick arrays all live on the same
per-chunk tracker.** When a chunk does a swap-remove, every
corresponding array is shifted by the same `swapRemove(slot, count)`
call. There is no "dirty-list cleanup pass" at the end of the tick
— the list is allowed to hold stale entries so long as iteration
filters them out (see `processChunk` below).

## Three-level short-circuit

`ChangeTracker` has three escalating levels of "don't do work,"
chosen once at plan-build time:

1. **Regular tracking** (default when a component has at least one
   `@Filter(Added/Changed)` consumer *or* a `RemovedComponents<T>`
   consumer) — per-slot tick writes happen, dirty list is maintained.
2. **`dirtyTracked = false`** — per-slot tick writes still happen
   (for `isAddedSince` / `isChangedSince` semantics), but the dirty
   list and bitmap are skipped. `appendDirty` short-circuits on line
   112.
3. **`fullyUntracked = true`** — the entire tracker is a no-op.
   `markAdded` and `markChanged` return immediately; nothing writes
   the tick arrays, nothing touches the dirty list. Used when the
   component has zero observers and zero `RemovedComponents` readers.

This is set up by `World` at plan-build time: after it has computed
the union of all observed components across every plan, it calls
`ArchetypeGraph.setFullyUntrackedComponents(untracked)` with every
component that has no observer. That walk flips the flag on every
existing tracker (`ArchetypeGraph.setFullyUntrackedComponents`, lines
~101–114) and the shared-reference set ensures newly-created chunks
pick it up automatically.

!!! tip "Why the three-level split exists"
    A previous revision only had two levels: tracked or untracked. A
    benchmark in the middle of the spectrum — components that no
    `@Filter` observer watches but that some `RemovedComponents<T>`
    reader drains — was paying full dirty-list maintenance cost for
    nothing, because `RemovedComponents<T>` doesn't read the dirty
    list; it reads a separate removal log. Splitting the switch
    produced the `dirtyTracked` intermediate level, which writes
    ticks for `isChangedSince` semantics without the list overhead.

## How `markChanged` gets called

`World.setComponent(entity, newValue)` eventually calls
`chunk.changeTracker(compId).markChanged(slot, currentTick)`. The
tracker's hot path (lines ~105–125):

```java
public void markChanged(int slot, long tick) {
    if (fullyUntracked) return;
    changedTicks[slot] = tick;
    appendDirty(slot);
}

private void appendDirty(int slot) {
    if (!dirtyTracked) return;
    appendDirtyUnchecked(slot);
}

private void appendDirtyUnchecked(int slot) {
    int word = slot >>> 6;
    long mask = 1L << (slot & 63);
    if ((dirtyBits[word] & mask) != 0) return;
    dirtyBits[word] |= mask;
    if (dirtyCount == dirtyList.length) {
        dirtyList = Arrays.copyOf(dirtyList, dirtyList.length * 2);
    }
    dirtyList[dirtyCount++] = slot;
}
```

Per-write cost:

- One branch on `fullyUntracked`
- One array store into `changedTicks`
- One branch on `dirtyTracked`
- One word-load, one mask test, one word-store into `dirtyBits`
- One increment and one array store into `dirtyList`

Five to six memory ops total on the write side. That is the "write-
path tax" versus a mutable POJO that would do one field write — see
[write-path tax](write-path-tax.md) for what you get in exchange.

## The sparse iteration path

The consumer side is `SystemExecutionPlan.processChunk` (lines
~231–270). The plan-build phase resolved every
`@Filter(Added/Changed, target = T)` into a `ResolvedChangeFilter`
list. At chunk entry, it caches one `ChangeTracker` per filter
target via `cachedFilterTrackers[i]`. Then:

```java
if (changeFilters.length > 0) {
    var primary = cachedFilterTrackers[0];
    int[] dirty = primary.dirtySlots();
    int dirtyN = primary.dirtyCount();

    for (int d = 0; d < dirtyN; d++) {
        int slot = dirty[d];
        if (slot >= count) continue;  // swap-removed since the mark

        boolean match = true;
        for (int i = 0; i < changeFilters.length; i++) {
            var cf = changeFilters[i];
            var tracker = cachedFilterTrackers[i];
            boolean ok = switch (cf.kind()) {
                case ADDED -> tracker.isAddedSince(slot, lastSeenTick);
                case CHANGED -> tracker.isChangedSince(slot, lastSeenTick);
            };
            if (!ok) { match = false; break; }
        }
        if (!match) continue;

        processSlot(chunk, slot, invoker, currentTick);
    }
} else {
    // Dense path: full slot scan. Unchanged from the pre-dirty-list
    // implementation; systems without change filters pay no bitmap
    // setup, no dirty-list bookkeeping.
    for (int slot = 0; slot < count; slot++) {
        processSlot(chunk, slot, invoker, currentTick);
    }
}
```

Four things deserve a note.

**The primary filter drives iteration.** When a system has multiple
filters (e.g. `@Filter(Changed, target = A.class)
@Filter(Changed, target = B.class)`), the AND semantics guarantees any
matching slot must appear in the *first* filter's dirty list. So
iterating slot-indices from `filter[0]`'s list and post-filtering
against the remaining filters is sound. The comment on line ~232
spells this out.

**`slot >= count` filters out swap-removed stale entries.** When an
entity is swap-removed, its slot index goes out of range relative to
the new `chunk.count()`. Any stale entry pointing to it gets
silently dropped by the `slot >= count` guard. No pre-tick scrub
pass needed.

**Multi-filter mismatches are filtered per slot.** The secondary-
filter loop does the `isChangedSince` check with the same
`lastSeenTick` watermark, so if slot `s` is dirty in `A` but not in
`B`, the system does not run at `s`. Correctness holds even though
we iterate from `filter[0]`'s list.

**The dense path is the same code as before filters existed.**
Systems without any change filter pay zero extra overhead compared
to the pre-filter implementation: `changeFilters.length == 0` and
the plain `for (slot = 0; slot < count; slot++)` runs. No bitmap
setup, no dirty-list setup. Adding filters to your system library
does not slow down systems that don't use them.

The tier-1 generator emits an equivalent of this loop in bytecode —
see `GeneratedChunkProcessor.java` lines ~396–490 for the filter
setup preamble and the per-slot filter check.

## `lastSeenTick` and strict-`>` comparisons

Every `SystemExecutionPlan` carries a per-system `lastSeenTick`:

```java
// SystemExecutionPlan.java, line ~38
private long lastSeenTick = 0;

public void markExecuted(long currentTick) {
    this.lastSeenTick = currentTick - 1;
}
```

`World` calls `markExecuted(currentTick)` after the system has
finished iterating every chunk for this tick. Two subtle choices
here.

**Strict-`>` comparison against `lastSeenTick`.** `isAddedSince` and
`isChangedSince` on the tracker (lines ~135–141) use strict `>`:

```java
public boolean isAddedSince(int slot, long sinceExclusive) {
    return addedTicks[slot] > sinceExclusive;
}
```

The watermark is stored as `currentTick - 1`, not `currentTick`. The
effect is that entities mutated *during* the current tick — for
instance between two `world.tick()` calls, or in a stage earlier in
the same tick — are still visible to the system on its next run,
because their `changedTick` equals `currentTick` which is strictly
greater than `currentTick - 1`.

!!! info "Why tick-0 is the untracked sentinel"
    `addedTicks` and `changedTicks` are `long[]` arrays initialised to
    zero. An entity that was never touched reads as `tick == 0`.
    Because `isChangedSince(slot, lastSeenTick)` uses strict `>`,
    tick 0 is never visible to any system whose `lastSeenTick >= 0`
    — which is every real system, because `world.tick(...)` starts
    counting from tick 1. Zero is therefore a safe "never touched"
    sentinel without reserving a magic value at the application
    level.

## Prune and swap-remove hygiene

The dirty list would grow unbounded if entries were never retired.
The retirement pass is `ChangeTracker.pruneDirtyList(minWatermark)`
(lines ~164–175):

```java
public void pruneDirtyList(long minWatermark) {
    int write = 0;
    for (int i = 0; i < dirtyCount; i++) {
        int slot = dirtyList[i];
        if (addedTicks[slot] > minWatermark || changedTicks[slot] > minWatermark) {
            dirtyList[write++] = slot;
        } else {
            dirtyBits[slot >>> 6] &= ~(1L << (slot & 63));
        }
    }
    dirtyCount = write;
}
```

Called at end of tick with the **minimum `lastSeenTick`** across all
systems that observe the component. Any slot below every system's
watermark is no longer reachable via any filter and can be dropped
from both the list and the bitmap. This means a system that runs
every 3 ticks still sees everything that happened in the 3-tick
window without any explicit state: the dirty list stays populated
for the slowest consumer.

Swap-remove is the other edge of the same problem. When entity `E`
at slot `s` is swap-removed, the entity at the last slot takes `s`'s
place. `ChangeTracker.swapRemove(slot, count)` (lines ~177–204):

```java
if (slot < last) {
    addedTicks[slot] = addedTicks[last];
    changedTicks[slot] = changedTicks[last];
    // If the moved entity (last) was in the dirty list, propagate
    // its dirty-bit to its new position (slot) so
    // @Filter(Changed/Added) systems don't silently miss it after
    // the despawn.
    long lastMask = 1L << (last & 63);
    if ((dirtyBits[last >>> 6] & lastMask) != 0) {
        long slotMask = 1L << (slot & 63);
        if ((dirtyBits[slot >>> 6] & slotMask) == 0) {
            appendDirtyUnchecked(slot);
        }
    }
}
addedTicks[last] = 0;
changedTicks[last] = 0;
dirtyBits[last >>> 6] &= ~(1L << (last & 63));
```

Two things are propagated: the tick values and the dirty-bit.

!!! warning "This was a silent correctness bug"
    An earlier revision copied the tick values but *not* the dirty
    bit. If entity `E` was dirty at the time another entity was
    swap-removed into `E`'s slot, `E`'s dirty bit was effectively
    cleared — `appendDirty` skipped it on subsequent writes because
    the old bit was still set elsewhere, and the observer missed the
    update. The benchmark cases in the suite don't mix mutation and
    despawn inside the same archetype, so the bug did not show up in
    any benchmark, but it was a real correctness violation. The fix
    is the `appendDirtyUnchecked(slot)` call in the block above.
    Documented in `DEEP_DIVE.md` as one of the PR-review fixes.

## Per-tracker lazy upgrade

One last subtlety: `setDirtyTracked(true)` has to re-seed the dirty
list for slots that were mutated *before* tracking was enabled.
Otherwise a system registered mid-world would miss everything that
happened before it existed. `ChangeTracker.setDirtyTracked` (lines
~54–64):

```java
public void setDirtyTracked(boolean tracked, int occupiedCount) {
    if (this.dirtyTracked == tracked) return;
    this.dirtyTracked = tracked;
    if (tracked) {
        for (int slot = 0; slot < occupiedCount; slot++) {
            if (addedTicks[slot] != 0L || changedTicks[slot] != 0L) {
                appendDirtyUnchecked(slot);
            }
        }
    }
}
```

Bounded to `occupiedCount` so stale data beyond the live population
is never included. Only traversed when the `true`→`true` transition
happens, so the seed cost is paid once per chunk per tracker
upgrade.

## Related

- [Architecture](architecture.md) — the chunk layout that hosts the
  per-component `ChangeTracker`
- [Tier-1 bytecode generation](tier-1-generation.md) — how the
  generator emits the sparse-iteration path directly in bytecode so
  there is no `SystemExecutionPlan.processChunk` call on the hot
  path for filtered systems
- [Write-path tax](write-path-tax.md) — what the per-write
  `markChanged` cost buys and what it costs
- [Optimisation journey](optimization-journey.md) — several of the
  PR-review fixes referenced above
- [Tutorial — Change detection](../tutorials/basics/06-change-detection.md)
  — the user-facing API for the machinery described here
