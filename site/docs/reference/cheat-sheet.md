# System parameters cheat sheet

Every parameter type you can declare on a method annotated
[`@System`](../tutorials/basics/03-systems.md). Each row is verified
against the `SystemParser` classifier in
`ecs-core/src/main/java/zzuegg/ecs/system/SystemParser.java`.

The **Tier-1?** column indicates whether the bytecode-generated fast
path supports the parameter without falling back to the reflective
tier-2 dispatcher. See [Tier-1 vs tier-2 dispatch](tier-fallbacks.md)
for the full fallback matrix.

## Component parameters

Bind to a component on the entity being iterated.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `@Read C component` | Read-only view of the `C` record on the current entity. No tracker touched. | yes |
| `@Write Mut<C> mut` | Read-write handle over `C`. `mut.get()` reads, `mut.set(new C(...))` writes and stamps the change tick. | yes |
| `Entity entity` | Entity id of the current row. In `@ForEachPair` this is the **source** entity unless `@FromTarget` is also present. | yes |

See [Queries — @Read and @Write](../tutorials/basics/04-queries.md) and
[Change detection](../tutorials/basics/06-change-detection.md).

## Archetype / query filters (method-level)

Narrow the entity set a system iterates over. Applied before component
binding, so they never drop tier-1 by themselves.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `@With(C.class)` | Require `C` on every matched entity; `C` is not bound as a parameter. Repeatable. | yes |
| `@Without(C.class)` | Exclude entities that carry `C`. Repeatable. | yes |
| `@Where("expr")` | Per-entity predicate evaluated against component field values. Repeatable. | **no — drops to tier-2** |
| `@Filter(value = Added.class, target = C.class)` | Only iterate entities whose `C` was added since the system's last run. | yes |
| `@Filter(value = Changed.class, target = C.class)` | Only iterate entities whose `C` was written since the system's last run. | yes |
| `@Filter(value = Removed.class, target = C.class)` | Deprecated spelling. Use `RemovedComponents<C>` instead. | **no — drops to tier-2** |

See [Query filters — @With / @Without](../tutorials/advanced/13-query-filters.md),
[Field filters — @Where](../tutorials/advanced/14-where-filters.md),
[Change detection](../tutorials/basics/06-change-detection.md).

## Resources

Register with `WorldBuilder.addResource(...)` and depend on them from
any system.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `Res<R> res` | Read-only handle to the registered resource of type `R`. `res.get()` returns the instance. | yes |
| `ResMut<R> res` | Read-write handle. Touches the resource tick; other systems with a `Changed` filter on the resource will see it. | yes |

See [Resources](../tutorials/basics/05-resources.md).

## Per-system local state

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `Local<T> local` | A per-system mutable slot, allocated once and surviving across ticks. `local.get()` / `local.set(v)`. | yes |

See [Local state](../tutorials/advanced/10-local-state.md).

## Deferred edits

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `Commands cmds` | Buffered command queue. Spawn, despawn, add/remove components, insert relations. Flushed at the end of the stage. | yes |

See [Commands — deferred edits](../tutorials/basics/08-commands.md).

## Events

Event types are registered with `WorldBuilder.addEvent(E.class)` and
delivered double-buffered.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `EventReader<E> events` | Iterable view of all `E` events emitted in the previous tick. | yes |
| `EventWriter<E> emit` | Handle for appending `E` events during this tick. | yes |

See [Events](../tutorials/advanced/09-events.md).

## Removal signals

Out-of-band streams of entity ids whose component / relation was removed
since the system last ran.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `RemovedComponents<C> removed` | Iterable of `Removal<C>` entries for entities that lost their `C` component this tick. | yes |
| `RemovedRelations<T> removed` | Iterable of `Removal<T>` entries for pairs of relation `T` removed this tick. | yes |

See [RemovedComponents](../tutorials/basics/07-removed-components.md)
and [RemovedRelations](../tutorials/relations/21-removed-relations.md).

## Ad-hoc component access

Non-iterating reads of a component on an arbitrary entity id (the
entity the system is iterating, a neighbour, a target, etc).

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `ComponentReader<C> reader` | `reader.get(entityId)` returns the `C` record for that entity, or `null` if it doesn't have one. Read-only. | yes |

See [Change detection](../tutorials/basics/06-change-detection.md).

## Relations

Two iteration models. Pick one per system.

### `@Pair` — per-entity walker

The system is called **once per entity** that participates in the
relation; the body walks the entity's pairs via a `PairReader<T>`
service param.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `@Pair(T.class)` method annotation | Require the entity to participate in at least one pair of `T`. Role defaults to `SOURCE`. Also `TARGET` (incoming pairs) and `EITHER` (no narrowing). Repeatable. | yes |
| `PairReader<T> reader` | Walker service. `reader.fromSource(self)`, `reader.withTarget(self)`, etc. | yes |

See [@Pair and PairReader](../tutorials/relations/18-pair-and-pair-reader.md).

### `@ForEachPair` — per-pair walker

The system is called **once per live pair**. Parameters bind directly
to the source-side entity by default; opt-in to target-side with
`@FromTarget`.

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `@ForEachPair(T.class)` method annotation | Drive iteration with one dispatch per live pair of `T`. | yes |
| `@Read C sourceC` | Read of `C` on the pair's **source** entity. | yes |
| `@Write Mut<C> sourceMut` | Write of `C` on the pair's **source** entity. | yes |
| `T payload` | The relation payload record. Bound by type match against the annotation value. | yes |
| `Entity source` | The source entity id of the current pair. | yes |
| `@FromTarget @Read C targetC` | Read of `C` on the pair's **target** entity. | yes |
| `@FromTarget Entity target` | The target entity id of the current pair. | yes |
| `@FromTarget @Write Mut<C>` | **Forbidden.** Rejected at parse time — write conflicts between pairs sharing a target are ambiguous in v1. | — |

See [@ForEachPair and @FromTarget](../tutorials/relations/19-for-each-pair.md).

## World handle

| Parameter syntax | What it binds to | Tier-1? |
|---|---|---|
| `World world` | The world instance itself. Escape hatch — most users never need it. | yes |

## Execution control (method-level)

These don't add parameters; they change *when* a system runs.

| Annotation | Effect |
|---|---|
| `@System` | Marks the method as a system. Optional `stage=`, `after={...}`, `before={...}`. |
| `@System(stage = "PreUpdate")` | Place the system in a named stage. Defaults inherit from the enclosing `@SystemSet`, else `"Update"`. |
| `@System(after = {"Physics"})` | Order this system after systems / sets named `Physics` within the same stage. |
| `@System(before = {"Render"})` | Order this system before systems / sets named `Render` within the same stage. |
| `@Exclusive` | System takes no component parameters and runs once per tick, serially, with full world access. See [@Exclusive systems](../tutorials/advanced/15-exclusive-systems.md). |
| `@RunIf("condName")` | Only run when the named `@RunCondition` returns `true`. See [Run conditions](../tutorials/advanced/16-run-conditions.md). |

## Class-level annotations

| Annotation | Effect |
|---|---|
| `@SystemSet(name = "...", stage = "...", after = {...}, before = {...})` | Group every `@System` method in a class under a named set. Ordering constraints apply to the group. See [Stages and ordering](../tutorials/advanced/11-stages-and-ordering.md). |
| `@RunCondition` | Marks a no-arg `boolean` method as a named run-condition referable from `@RunIf`. Optional name override via the annotation value. |

## Quick reminders

- **Components must be `record` types.** The registry rejects
  non-records in `ComponentRegistry.register`.
- **Resources are any class.** Not required to be a record.
- **Service parameters never block tier-1.** Only component count,
  `@Where`, `@Filter(Removed)`, and total param count can — see the
  [fallback table](tier-fallbacks.md).
- **`@FromTarget @Write` is always rejected**, not just tier-2.
