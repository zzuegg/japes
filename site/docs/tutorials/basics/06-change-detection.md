# Change Detection

Most systems do not need to run against every entity every tick — they only
care about the ones that were *just spawned* or *just mutated*. japes ships
with a per-component **change tracker** that remembers which slots were
touched since a given tick, and lets a system filter its iteration down to
just those slots.

!!! tip "Changed ≠ Added"
    `Added` fires on the tick an entity is spawned (or gains the component
    via `addComponent`) and once only. `Changed` fires whenever `Mut.set` is
    called with a new value and then flushed — i.e., the component was
    actually modified. Newly-spawned entities trigger `Added` but **not**
    `Changed`, by design.

## The `@Filter` annotation

Change filters go on the `@System` method, not the parameters. The
annotation lives at `zzuegg.ecs.system.Filter` and takes two values:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Filter.List.class)
public @interface Filter {
    Class<?> value();                          // Added.class | Changed.class | Removed.class
    Class<? extends Record> target();          // the component you're filtering on
}
```

Minimal example:

```java
import zzuegg.ecs.system.*;

public class SpawnWatcher {
    @System
    @Filter(value = Added.class, target = Position.class)
    void onSpawn(@Read Position pos) {
        java.lang.System.out.println("new entity at " + pos);
    }
}
```

This method runs **only on entities whose `Position` component was freshly
added since this system last ran**. On the first tick after two spawns you
see two invocations; on the tick after that — zero.

## `Added` — fires once per new entity

`@Filter(value = Added.class, target = X.class)` matches any entity whose
`X` component has been added since the previous execution of *this system*.
That includes:

- `world.spawn(new X(...))`
- `world.addComponent(entity, new X(...))`
- `commands.spawn(new X(...))` / `commands.add(entity, new X(...))` at
  command flush

```java
public class GreetPlayers {
    @System
    @Filter(value = Added.class, target = Player.class)
    void greet(@Read Position pos, Entity self) {
        java.lang.System.out.println("Welcome, " + self + " at " + pos);
    }
}
```

Each new player is visited exactly once — on the tick after they appear.

## `Changed` — fires whenever `Mut.set` is flushed

`@Filter(value = Changed.class, target = X.class)` runs only on entities
whose `X` component has had `Mut.set(...)` called on it since this system
last ran, **and** the new value differed from the old (for records marked
`@ValueTracked` — a separate advanced topic).

The canonical pair: one system writes, another reacts.

```java
public class Mover {
    @System
    void move(@Read Velocity v, @Write Mut<Position> p) {
        var cur = p.get();
        p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
    }
}

public class GridReindexer {
    @System(after = "Mover.move")
    @Filter(value = Changed.class, target = Position.class)
    void reindex(@Read Position pos, Entity self) {
        grid.move(self, pos);
    }
}
```

`GridReindexer.reindex` runs only on entities `Mover.move` actually
mutated. Static entities whose `Velocity` was zero (so `Position` did not
actually change — at least for `@ValueTracked` records) never reach the
reindexer.

!!! note "`set(...)` without a true change"
    Calling `mut.set(currentValue)` sets the `isChanged` flag, but when
    `Mut.flush()` notices the new and old values are `.equals()` for a
    `@ValueTracked` record it **skips** marking the tracker. For non
    value-tracked records, any `set` call is treated as a change. In
    practice: prefer `@ValueTracked` for components where "no-op writes"
    are common.

## Combining filters

Because `@Filter` is `@Repeatable`, you can stack multiple filters on a
single system. They combine with **AND** semantics — an entity must satisfy
every filter to be iterated:

```java
@System
@Filter(value = Changed.class, target = Position.class)
@Filter(value = Changed.class, target = Velocity.class)
void both(@Read Position p, @Read Velocity v) {
    // runs only for entities whose Position AND Velocity changed
    // since this system last ran
}
```

## How it works (the 30-second version)

Every chunk carries one `ChangeTracker` per component stored in it. The
tracker has two parallel tick arrays — `addedTicks[slot]` and
`changedTicks[slot]` — plus a deduplicated **dirty list** of slots that
have been touched since the last prune.

```
ChangeTracker (Position in chunk 0)
    addedTicks   = [103, 103, 0, 107, 0, ...]
    changedTicks = [0,   104, 0, 0,   0, ...]
    dirtyList    = [0, 1, 3]   // slots 0, 1, and 3 were touched
```

Each system keeps its own **"last seen tick"** — the tick number when it
last finished running. `@Filter(Added)` translates to "is `addedTicks[slot]
> lastSeenTick`?". `@Filter(Changed)` is the same test on `changedTicks`.
Because the comparison is strict `>`, a system that spawned an entity on
tick *T* will still see it as "Added" on its next run (which sees tick
`T+1` or later).

### The sparse iteration path

For systems with change filters, `SystemExecutionPlan.processChunk` takes a
**sparse path**:

1. Grab the dirty list from the *first* filter's tracker.
2. Iterate only those slot indices.
3. For each one, confirm every filter still matches (slots may have been
   swap-removed, or this filter may be stricter than the primary).
4. Invoke the system body only on slots that pass.

A chunk with one million entities, of which ten changed this frame, costs
roughly ten invocations for a `@Filter(Changed)` system — not one million.
Systems without any `@Filter` fall back to a dense loop over the whole
chunk, so you only pay for sparse iteration when you ask for it.

### When dirty bookkeeping is on

The tracker's dirty-list maintenance is **opt-in per component**. If no
system in your world observes `Position` via a change filter (and no
system consumes `RemovedComponents<Position>`), the world flips the
`Position` tracker to `fullyUntracked` mode at plan-build time and every
`markAdded` / `markChanged` call becomes a no-op. Pure-write workloads do
not pay a cent for bookkeeping they never read.

### The prune pass

At the end of every tick, the world prunes each dirty list: entries whose
added- and changed-ticks are both `<= min(lastSeenTick across all
observers)` are dropped and their bits cleared. A tracker with ten thousand
dirty slots that every observer has already processed shrinks back to
empty on the next tick, so the sparse iteration starts cheap again.

## Multi-target `@Filter`

A single `@Filter` annotation can target multiple component types with OR semantics — the system fires once per entity where **any** of the targets changed:

```java
@System
@Filter(value = Changed.class, target = {Position.class, Velocity.class, Health.class})
void onAnyChanged(@Read Position p, @Read Velocity v, @Read Health h) {
    // fires if Position OR Velocity OR Health was mutated
    // the entity is visited exactly once even if multiple components changed
}
```

!!! tip "When to use multi-target"

    Multi-target shines when one observer logically watches several component types and doesn't care which one triggered the change. Without it, you'd need N separate systems — one per component — each with its own scheduler dispatch overhead.

The rules:

- **Within one `@Filter` annotation**: targets are OR'd. Any match fires.
- **Across multiple stacked `@Filter` annotations**: still AND'd (same as before).
- **Deduplication**: an entity that changed on 2 of the 3 targets is visited exactly once.
- **Tier-1 supported**: the generated chunk processor calls `MultiFilterHelper.unionDirtySlots` to merge the dirty lists, then iterates with inline `invokevirtual`.

```java
// OR within, AND across — fires for entities where
// (Position OR Velocity changed) AND (Health was added)
@System
@Filter(value = Changed.class, target = {Position.class, Velocity.class})
@Filter(value = Added.class, target = Health.class)
void complexFilter(@Read Position p, @Read Velocity v, @Read Health h) { ... }
```

## `@Filter(Removed)` — reacting to deletions

`@Filter(Removed)` is the third leg of the symmetric API. It fires once per entity that **lost** any target component since the last tick, with `@Read` params bound to the **last-known values** before removal:

```java
@System
@Filter(value = Removed.class, target = {State.class, Health.class, Mana.class})
void onRemoved(@Read State s, @Read Health h, @Read Mana m, Entity self) {
    // s, h, m are the values BEFORE removal
    // For a component that was stripped: last value from the removal log
    // For components still live: current value from the entity
    // For a fully despawned entity: all values from the removal log
}
```

!!! tip "This replaced `RemovedComponents<T>` for multi-type observation"

    Previously you needed 3 separate `RemovedComponents<T>` systems to watch 3 component types. Now one `@Filter(Removed, target = {A, B, C})` does the same work in one system dispatch with type-safe `@Read` binding.

How it works under the hood:

- The entity that lost a component is **no longer in a matching archetype** — normal chunk iteration can't find it.
- Instead, `@Filter(Removed)` systems are dispatched via a dedicated `GeneratedRemovedFilterProcessor` that walks the **removal log** (not the dirty list).
- The removal log captures `(entity, lastValue, tick)` at every `removeComponent` / `despawn` call.
- Multi-target deduplication: a despawned entity with 3 components produces 3 log entries but only 1 observer call.
- **Tier-1 supported**: the generated hidden class calls `RemovedFilterHelper.resolve()` for dedup + value resolution, then iterates with inline `invokevirtual`.

`RemovedComponents<T>` still works and is simpler for single-type drains — see the [next chapter](07-removed-components.md).

## Quick recipe list

| You want... | Use |
|---|---|
| React the first tick after an entity with `Health` spawns | `@Filter(value = Added.class, target = Health.class)` |
| React every tick a `Position` was actually mutated | `@Filter(value = Changed.class, target = Position.class)` |
| React when ANY of several components changed | `@Filter(value = Changed.class, target = {A.class, B.class})` |
| React only when both `Position` AND `Velocity` changed | Two stacked `@Filter(Changed)` annotations |
| React when a component was just removed (with last values) | `@Filter(value = Removed.class, target = Health.class)` |
| React when ANY of several components were removed | `@Filter(value = Removed.class, target = {A.class, B.class, C.class})` |
| Simple per-type removal drain (no @Read binding) | `RemovedComponents<T>` ([next chapter](07-removed-components.md)) |

## What's next

`RemovedComponents<T>` is the simpler alternative for single-type removal drains — useful when you don't need `@Read` binding or multi-type observation.

Continue to **[Removed Components](07-removed-components.md)**.
