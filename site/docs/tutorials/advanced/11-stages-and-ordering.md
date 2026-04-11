# Stages and ordering

Every system belongs to a **stage**, and within a stage the scheduler builds a dependency DAG that respects `@System(after = ..., before = ...)` constraints plus component-access conflicts. This chapter covers the default stage layout, how to place a system, and how the DAG builder picks an execution order.

## Default stages

The `WorldBuilder` installs five stages in this order:

| Name         | Order |
|--------------|-------|
| `First`      | 0     |
| `PreUpdate`  | 100   |
| `Update`     | 200   |
| `PostUpdate` | 300   |
| `Last`       | 400   |

These come from `scheduler/Stage.java` as `Stage.FIRST`, `Stage.PRE_UPDATE`, `Stage.UPDATE`, `Stage.POST_UPDATE`, `Stage.LAST`. A stage is just a `(name, order)` record; the order value is what the world uses to sort stages before it runs them each tick.

At tick time the world walks stages in ascending order, runs every system in each stage to completion, then advances to the next.

## Placing a system in a stage

An `@System` method without `stage = ...` goes into `Update`.

```java
class Physics {
    @System                                         // → Update
    void integrate(@Write Mut<Position> p, @Read Velocity v) { ... }

    @System(stage = "PreUpdate")                    // → PreUpdate
    void clearForces(@Write Mut<Force> f) { ... }

    @System(stage = "PostUpdate")                   // → PostUpdate
    void clampVelocities(@Write Mut<Velocity> v) { ... }
}
```

The `stage` string must match a registered stage name — misspellings throw at schedule-build time.

## Class-level defaults with `@SystemSet`

`@SystemSet` on the system class sets the default stage and the default `after`/`before` constraints for every `@System` inside the class. A method can still override with its own explicit `stage = "..."`.

```java
@SystemSet(name = "Input", stage = "PreUpdate", after = {})
class Input {
    @System void poll()   { ... }                     // → PreUpdate
    @System void dispatch() { ... }                   // → PreUpdate
    @System(stage = "Update") void forceUpdateOverride() { ... }
}
```

The parser reads `@SystemSet` once per class; all three values (`stage`, `after`, `before`) are merged with whatever the method-level `@System` declares. When the method's `stage` is the empty default sentinel, it inherits the set's stage; otherwise the method wins.

!!! tip "Group related systems in one `@SystemSet`"

    Putting all input systems in one `@SystemSet(stage = "PreUpdate")` class avoids copy-pasting `stage = "PreUpdate"` onto every method. When you want to move all of them to a new custom stage later, you change one annotation.

## Ordering within a stage

Two mechanisms combine to produce the final order:

1. **Explicit constraints.** `@System(after = "movement")` says *this system must run after the system named `movement`*. `before = "collision"` says *this system must run before `collision`*.
2. **Automatic conflict edges.** If system A writes a component that system B reads or writes, the DAG builder inserts an ordering edge so they cannot run simultaneously.

```java
class Movement {
    @System(after = "clearForces", before = "collision")
    void integrate(@Write Mut<Position> p, @Read Velocity v) { ... }
}

class Collisions {
    @System                                   // name: "Collisions.collision"
    void collision(@Read Position p, @Write Mut<Hit> h) { ... }
}
```

System references can be either simple names (`"clearForces"`) or qualified (`"Physics.clearForces"`). Simple names only resolve if they're unique; if two classes both define a `cleanup` method, any unqualified reference throws `IllegalStateException` — qualify it explicitly.

## Automatic parallelism

When you use `Executors.multiThreaded()`, the DAG builder uses the same graph to figure out what can run in parallel. Two systems in the same stage can run concurrently when **none** of the following hold:

- One has an explicit `after`/`before` pointing at the other.
- They share a component where at least one side is a write, *and* their `@With`/`@Without` filters do not put them on disjoint archetype sets.
- Both access the same resource, and at least one writes.
- Either is `@Exclusive`.

The builder also understands that an `@Without(Hunter.class)` system and an `@With(Hunter.class)` system can never touch the same chunk, so their write-conflict edge is dropped — they run in parallel even if they both write to the same component type.

```java
// These run in parallel even though they both write Position.
@System void movePlayers(@Write Mut<Position> p) {}         @With(Player.class)
@System void moveEnemies(@Write Mut<Position> p) {}         @With(Enemy.class) @Without(Player.class)
```

See [Query filters](13-query-filters.md) for the marker-component pattern that unlocks this.

## What happens at stage boundaries

At the end of every stage (not every system, and not every tick), the world calls `flushPendingCommands()`. Every `Commands` buffer held by a system in that stage is drained into the structural change pipeline: spawns, despawns, component inserts/removes, and resource inserts are all applied *before* the next stage begins.

```
┌──────────── tick N ────────────┐
│ First         [flush commands] │
│ PreUpdate     [flush commands] │
│ Update        [flush commands] │
│ PostUpdate    [flush commands] │
│ Last          [flush commands] │
└────────────────────────────────┘
```

The practical consequences:

- A command issued in `PreUpdate` is visible to every `Update` system — the entity exists, the component is there.
- A command issued in `Update` is **not** visible to other systems in the same `Update` stage — it lands at the stage boundary.
- If you need immediate structural change, use an `@Exclusive` system at the top of the next stage.

!!! warning "Don't read back what you just spawned in the same stage"

    A `commands.spawn(new Bullet(...))` in `Update` will not produce a hit in another `Update` system's `@Read Bullet` iteration, no matter how you order them. Use `@System(stage = "PostUpdate")` on the reader, or issue the spawn from a `PreUpdate` system.

## Adding a custom stage

`WorldBuilder.addStage(name, stage)` registers another stage. The second argument's `order()` controls where it slots into the execution order.

```java
var world = World.builder()
    .addStage("Physics",  new Stage("Physics",  150))  // between PreUpdate (100) and Update (200)
    .addStage("Rendering", new Stage("Rendering", 350)) // between PostUpdate (300) and Last (400)
    .addSystem(Physics.class)
    .build();
```

`Stage.after("Update")` is a convenience that returns a stage with order + 50, useful if you don't want to hard-code a number:

```java
.addStage("Physics", Stage.after("PreUpdate"))   // order = 150
```

## Debugging the schedule

If the world refuses to build with `Cycle detected in system dependency graph`, your `after`/`before` annotations are contradictory — usually via a conflict edge the DAG builder inserted on top of your explicit ones. Break the cycle by relaxing one of the constraints (prefer `before` on the system that is free to move later).

If two systems you expected to run in parallel run sequentially, check that they don't share a writable component without disjoint filters. Adding `@With(SomeMarker.class)` / `@Without(SomeMarker.class)` on the two sides is usually the fix.

## What's next

- [Multi-threading](12-multi-threading.md) — pick an executor, and understand how the DAG becomes parallel work.
- Related basics: [Systems](../basics/03-systems.md), [Commands](../basics/08-commands.md).
