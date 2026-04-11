# `@ForEachPair`

> The tuple-oriented dispatch model. The framework calls the system
> **once per live pair** of a given relation type, with source and
> target components bound directly as method parameters. No walker,
> no reader, no per-source dispatch — the tier-1 bytecode generator
> emits a tight loop that calls your body through `invokevirtual`.

## The big idea

`@Pair` is "give me every entity that has pairs and let me walk
them." `@ForEachPair` is "just give me each pair — one at a time."

The framework takes over the iteration for you. It walks the
forward-index slice of every source, and for every `(source, target)`
in the store it:

1. resolves the source's components and loads the `@Read` values,
2. sets up the source's `@Write Mut<T>` slots,
3. resolves the target's components and loads any
   `@FromTarget @Read` values,
4. fetches the payload record,
5. invokes your method once.

Because nothing inside your body has to "walk" anything, the body
collapses to the actual per-pair work — a handful of arithmetic
operations in the typical case.

## Minimal example

```java
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.FromTarget;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;

public record Position(float x, float y) {}
public record Velocity(float dx, float dy) {}

@Relation
public record Hunting(int ticksLeft) {}

public static class Pursuit {

    @System
    @ForEachPair(Hunting.class)
    public void pursue(
            @Read Position sourcePos,
            @Write Mut<Velocity> sourceVel,
            @FromTarget @Read Position targetPos,
            Hunting hunting
    ) {
        float dx = targetPos.x() - sourcePos.x();
        float dy = targetPos.y() - sourcePos.y();
        float mag = (float) Math.sqrt(dx * dx + dy * dy);
        if (mag > 1e-4f) {
            sourceVel.set(new Velocity(dx / mag * 0.1f, dy / mag * 0.1f));
        }
    }
}
```

No `PairReader`, no `self`, no iteration loop inside the body. The
method fires once per live `Hunting` pair; the four parameters are
the source's `Position`, a writable `Mut<Velocity>` on the source,
the target's `Position`, and the `Hunting` payload itself.

## Parameter binding rules

The `@ForEachPair` dispatch looks at each parameter and decides
what to bind based on its annotations and type.

| Parameter shape                    | Binds to                                        |
|------------------------------------|-------------------------------------------------|
| `@Read Component`                  | Source entity's component (read-only copy)      |
| `@Write Mut<Component>`            | Source entity's component (writable handle)     |
| `@FromTarget @Read Component`      | Target entity's component (read-only copy)      |
| `@FromTarget @Write Mut<...>`      | **Forbidden.** Rejected at plan-build time.     |
| `Entity`                           | Source entity id (per pair)                     |
| `@FromTarget Entity`               | Target entity id (per pair)                     |
| Relation payload record (by type)  | The pair's payload (no annotation required)     |
| `Commands`, `Res<T>`, `ResMut<T>`  | Regular service parameters                      |
| `ComponentReader<T>`, `World`      | Regular service parameters                      |

The key rules:

- **Source is default.** Any `@Read` / `@Write` without `@FromTarget`
  binds to the source entity of the current pair.
- **`@FromTarget` flips to target.** Applicable to read-only
  components and to `Entity` parameters. Not applicable to
  `Mut<T>`.
- **The payload binds by type.** The system descriptor looks for a
  parameter whose type matches the `@ForEachPair(T.class)` value
  and hands the per-pair record into it. No annotation needed; the
  type match is unambiguous because a relation pair has exactly
  one payload record.
- **Service parameters are unchanged.** `Commands`, `Res<T>`,
  `ResMut<T>`, `ComponentReader<T>`, and `World` work exactly the
  same as in a plain `@System` method.

## Why `@FromTarget @Write` is forbidden

!!! warning "Writing target-side components from `@ForEachPair` is rejected"
    The tuple-oriented dispatch iterates pairs in forward-index
    order. Two different predators can legitimately share the same
    prey — both pairs would fire the system body, and both bodies
    would try to `set` a new value into the same target slot. Last
    write wins, but which write is "last" depends on iteration
    order, which is an implementation detail. Rather than define
    undefined semantics, the plan builder rejects
    `@FromTarget @Write` at parse time.

    If you need to accumulate target-side updates, do it through
    `Commands` — the command buffer is ordered and deterministic.
    Example: apply damage via
    `cmds.add(targetEntity, new IncomingDamage(...))` and let a
    separate `@System` apply the damage once per prey.

## How the dispatch works under the hood

Here's the execution path. You don't need to know any of this to
use `@ForEachPair`, but it explains why the benchmark numbers
look the way they do.

### Tier 1: bytecode generation

When a `@ForEachPair` system is built, the framework first asks
`GeneratedPairIterationProcessor` to emit a hidden class. The hidden
class has a `run(long tick)` method that:

1. Hoists the relation store's forward-map key/value arrays into
   local variables.
2. Walks the outer table slot by slot, skipping nulls.
3. For each live source:
    - resolves the source's `EntityLocation`,
    - compares the source's archetype id against a one-slot cache
      and re-resolves the per-component storages on a miss,
    - loads every `@Read` source component into a local,
    - calls `Mut.setContext` + `Mut.resetValue` **once per source**
      for every `@Write` slot, not once per pair.
4. Inner loop walks the `TargetSlice`'s raw `long[]` target ids and
   `Object[]` payload values.
5. For each pair:
    - resolves the target's location + archetype cache,
    - loads `@FromTarget @Read` components,
    - calls your system method via a direct `invokevirtual` — no
      `MethodHandle`, no `SystemInvoker`, no reflection.
6. After the inner loop, flushes each source-side `Mut<T>` back
   into the chunk storage.

The code is in
`ecs-core/src/main/java/zzuegg/ecs/system/GeneratedPairIterationProcessor.java`.
The store-level `forEachPairLong` callback in
`ecs-core/src/main/java/zzuegg/ecs/relation/RelationStore.java` is
the same walk, minus the component-resolution steps.

### Tier 2: reflective fallback

Some signatures don't fit the tier-1 emitter (current caps: ≤ 4
source `@Read`, ≤ 2 source `@Write`, ≤ 2 `@FromTarget @Read`,
non-static methods). When that happens the framework silently
falls back to `PairIterationProcessor`, which does the same work
through `ComponentReader.get` + `SystemInvoker.invoke`. Correct
but slower. The caps aren't fundamental — they're bytecode-emission
complexity limits, and they'll loosen over time.

## `@Pair` vs `@ForEachPair`: pick by shape

Both models are first-class. Neither is a footgun. Use the one
whose shape matches your system:

| If your body…                                               | Prefer                           |
|-------------------------------------------------------------|----------------------------------|
| Does entity-level setup once, then optionally walks pairs   | `@Pair` + `PairReader`           |
| Does identical work on every pair, with no entity-level state | `@ForEachPair`                 |
| Reads target components                                     | Either — `@ForEachPair` is terser |
| Writes target components                                    | `@Pair` (with `Commands`)        |
| Needs "at most one pair per entity" semantics               | `@Pair` + `reader.get`           |
| Is the hot-path pair kernel                                  | `@ForEachPair` (tier-1 wins)     |

!!! tip "Prefer `@ForEachPair` for per-pair hot paths"
    If profiling shows the pair walk dominating a system's CPU cost,
    and the body does the same thing for every pair, move it to
    `@ForEachPair`. The tier-1 generator removes the `PairReader`
    wrapper, the `fromSource` iterable allocation, and the per-pair
    `ComponentReader.get` dispatch, and it cuts the source-side
    `Mut<T>` setup cost down to once per source instead of once per
    pair.

## Safety: same mutation rule as `@Pair`

!!! warning "Don't mutate the store during iteration"
    A `@ForEachPair` body runs inside the iteration loop — same
    constraint as the `@Pair` walker. Calls to `world.setRelation`
    or `world.removeRelation` for the relation type being iterated
    are unsafe. Use `Commands.setRelation` /
    `Commands.removeRelation` to defer mutations to the next stage
    boundary. Service parameters like `Commands` and `ResMut<T>`
    are resolved before the iteration starts and remain valid for
    the full stage.

## What you learned

!!! summary "Recap"
    - `@ForEachPair(T.class)` fires a system method once per live
      pair of relation type `T`.
    - Parameters bind by annotation: default to source-side,
      `@FromTarget` flips to target-side, the payload binds by
      type match.
    - `@FromTarget @Write` is forbidden because pair iteration
      order is not user-visible.
    - The tier-1 runner is a generated hidden class with a
      direct `invokevirtual` into your body — no reflection on
      the hot path.

## What's next

!!! tip "Next chapter"
    When an entity despawns, what happens to its pairs — and to
    pairs pointing at it? The answer depends on the relation's
    cleanup policy. See
    [Cleanup policies](20-cleanup-policies.md).
