# `RemovedRelations<T>`

> Per-system, watermark-drained access to pair-removal events.
> Declare `RemovedRelations<T>` as a system parameter and the
> framework hands you every pair of type `T` that was dropped
> since this system last ran — same pattern as
> `RemovedComponents<T>`, but keyed on pair identity instead of
> component id.

## When you need it

A relation is a typed edge. Dropping an edge is meaningful —
sometimes as meaningful as creating one. Common reactions to
pair removal:

- **Scoring.** A `Hunting` pair disappears when a prey is caught.
  A scoring system drains the removal log and increments a
  counter per event.
- **Cache invalidation.** You have a `PairKey`-indexed cache of
  derived data (e.g. path-finding results between source and
  target). When the pair goes, flush the cache entry.
- **Notification.** UI overlay that highlights "X is targeting Y"
  has to drop the overlay when the targeting relation ends — by
  despawn, by manual removal, or by a policy cascade.
- **Resource release.** The source had a reservation held against
  the target; when the pair ends, release the reservation.

All of these would be clumsy to write on top of the
`World.setRelation` / `removeRelation` call sites, because the
calls come from many systems and some removals happen implicitly
via cleanup policies in the despawn path. `RemovedRelations<T>` is
the one place every drop is visible.

## Requesting the observer

Add a parameter of type `RemovedRelations<T>` to any `@System`
method, same as you would with `RemovedComponents<T>`:

```java
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.System;

public record Score(long kills) {}

public static class Scoring {

    @System(stage = "PostUpdate")
    public void tallyCatches(
            RemovedRelations<Hunting> dropped,
            ResMut<Score> score
    ) {
        if (dropped.isEmpty()) return;
        for (var event : dropped) {
            // event.source()    — the predator
            // event.target()    — the prey (may be dead)
            // event.lastValue() — the Hunting payload at the instant of drop
            score.get();  // bump score
        }
    }
}
```

The iterator is single-use per tick. Iterating once consumes the
current window; the system's watermark advances on return so
consecutive ticks observe disjoint removal windows. If a `RunIf`
or a disabled stage causes this system to skip a tick, the
watermark does **not** advance — the next run sees everything
since the last real execution, so observers can't miss removals.

## The `Removal<T>` record

```java
public interface RemovedRelations<T extends Record> extends Iterable<RemovedRelations.Removal<T>> {

    record Removal<T extends Record>(Entity source, Entity target, T lastValue) {}

    Iterator<Removal<T>> iterator();
    List<Removal<T>> asList();
    boolean isEmpty();
}
```

Each `Removal` carries:

- `source()` — the source entity of the dropped pair. Often still
  alive (in the `RELEASE_TARGET` case, the normal
  `removeRelation` case, and the `removeAllRelations` case). May
  be dead if the source entity was itself despawned.
- `target()` — the target entity. Often dead, especially when the
  removal came from the cleanup policy path.
- `lastValue()` — the payload record as it existed in the store
  the instant the pair was dropped. Capturing this means a scoring
  or audit system can read the final state without having to peek
  into the store before the drop.

!!! warning "Don't assume either entity is alive"
    The only thing `Removal` guarantees is that the pair *was*
    live recently. For pairs dropped by a `despawn` cleanup, the
    target is always dead and the source is dead if the policy was
    `CASCADE_SOURCE`. Always gate `world.isAlive` before doing
    anything with the entities beyond reading the payload.

## Removal sources tracked

Every code path that drops a pair feeds the per-type
`PairRemovalLog`, so a `RemovedRelations<T>` observer sees **all**
removals regardless of origin:

| Call site                                                 | Tracked? |
|-----------------------------------------------------------|----------|
| `World.removeRelation(source, target, T)`                 | Yes      |
| `World.removeAllRelations(source, T)`                     | Yes      |
| `Commands.removeRelation(...)` (flushed via CommandProcessor) | Yes  |
| `World.despawn(entity)` → cleanup policy drops a pair     | Yes      |
| `World.despawn(entity)` with `CASCADE_SOURCE`             | Yes      |
| `RelationStore.remove(source, target, tick)` directly     | Yes      |
| `RelationStore.remove(source, target)` (tick-less)        | **No** — untracked sentinel |

The tick-less `set` / `remove` overloads on `RelationStore` are
reserved for unit tests that want to seed state without polluting
the change tracker. Application code never calls them directly.

## Per-consumer watermarks

The retention model is deliberately pay-as-you-go:

1. `PairRemovalLog.append` is a no-op unless at least one consumer
   has registered. Worlds with no `RemovedRelations<T>` observers
   pay zero memory for the log — the write side short-circuits on
   `consumerCount == 0`.
2. When a consumer is present, every drop is appended as
   `(source, target, lastValue, tick)`.
3. Each `RemovedRelations<T>` system parameter is bound to a
   `SystemExecutionPlan`. On iteration, the backing
   `RemovedRelationsImpl` reads the log with
   `plan.lastSeenTick()` as an **exclusive** lower bound — it
   returns only entries whose tick is strictly greater.
4. At end-of-tick, `World` advances the per-type minimum watermark
   across all plans that consume this relation type, and
   `PairRemovalLog.collectGarbage` drops entries at or below that
   watermark. Plans that never ran this tick (disabled,
   `RunIf == false`) hold the log back with their old watermark,
   so their next real run still observes every drop since the
   last time they actually fired.

The net effect: every consumer observes every drop exactly once,
in tick order, even when dispatch is irregular.

## Worked example: GC a derived-data cache

Suppose you maintain a cache of path-finding results keyed by
`PairKey`. The cache entries are expensive to recompute, so you
only want to drop one when the underlying pair goes away.

```java
import zzuegg.ecs.relation.PairKey;
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.System;

import java.util.HashMap;

public final class PathCache {
    public final HashMap<PairKey, int[]> entries = new HashMap<>();
}

@Relation
public record Pursuing() {}

@System(stage = "PostUpdate")
public void evictDeadPaths(
        RemovedRelations<Pursuing> dropped,
        ResMut<PathCache> cache
) {
    if (dropped.isEmpty()) return;
    var table = cache.get().entries;
    for (var event : dropped) {
        table.remove(new PairKey(event.source(), event.target()));
    }
}
```

`PairKey` (`zzuegg.ecs.relation.PairKey`) is the record
`(Entity source, Entity target)` that the change tracker and
removal log already use internally — it's reusable in your own
data structures because `Entity` equality carries generation, so
stale entity slots never collide with fresh ones.

## Worked example: score on drop

```java
import zzuegg.ecs.relation.RemovedRelations;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.System;

public final class Counters {
    public long hunts;
    public long catches;
}

@System(stage = "PostUpdate")
public void scoreHunts(
        RemovedRelations<Hunting> dropped,
        ResMut<Counters> counters
) {
    var c = counters.get();
    for (var event : dropped) {
        // Every dropped Hunting is one completed (or abandoned) hunt.
        c.hunts++;
        // A catch is a drop whose target has also died this tick.
        // event.target() might not be alive anymore, so we can
        // distinguish catches from abandoned hunts without touching
        // the prey's component data at all.
    }
}
```

This is the pattern used by the predator-prey worked example in
the next chapter: `RELEASE_TARGET` drops the `Hunting` pair when
the prey is caught and despawned, and a scoring system drains
`RemovedRelations<Hunting>` during `PostUpdate` to keep the tally.

## Why not a callback?

Callback-driven observers look tempting but have two failure
modes:

1. **Order coupling.** If the observer runs inline with the drop,
   it sees partial world state — some cleanup policies have run,
   others haven't. The watermark drain model sees a consistent
   view because it runs in its own system in its own stage.
2. **Re-entrancy.** An inline callback can call back into
   `setRelation` / `removeRelation` from inside the cleanup loop,
   which mutates the store mid-iteration. The watermark drain is
   intrinsically deferred — the observer runs when the scheduler
   schedules it, not inside the despawn path.

`RemovedRelations<T>` also parallels `RemovedComponents<T>`
one-to-one. If you already know one, you know the other.

## What you learned

!!! summary "Recap"
    - `RemovedRelations<T>` is a per-consumer, watermark-drained
      view of every pair of type `T` dropped since the last run.
    - Each `Removal<T>` carries the source, target, and the
      payload at the instant of drop — entities may or may not
      still be alive.
    - The log short-circuits when no consumer is registered, so
      worlds with no observers pay nothing.
    - Use it for scoring, cache invalidation, and any system that
      reacts to the disappearance of a relation.

## What's next

!!! tip "Next chapter"
    Time to assemble everything into a complete worked example.
    The [Predator-prey walkthrough](22-predator-prey.md) mirrors
    the benchmark suite and uses every piece in this section:
    acquisition, pursuit with `@ForEachPair`, exclusive catch
    detection via `forEachPairLong`, cleanup via
    `RELEASE_TARGET`, and scoring via `RemovedRelations<Hunting>`.
