# Commands

Inside a running system, mutating the world directly is unsafe. The
scheduler may be running other systems in parallel, archetypes may be
mid-iteration, and even in single-threaded worlds a spawn during iteration
would invalidate the chunk you are walking. The solution is `Commands`: a
buffer you push into from your system body that gets replayed, in order,
at the **next stage boundary**.

!!! tip "One rule to internalise"
    Any time you want to spawn, despawn, add, remove, or replace a
    component from inside a `@System` method, do it through a
    `Commands` parameter. Direct `world.spawn(...)` /
    `world.despawn(...)` calls belong in your `main` / test code, not
    inside systems.

## Getting a `Commands` parameter

Declare a parameter of type `Commands` on your `@System` method. The
scheduler creates a fresh `Commands` per system and passes the same
instance to every invocation of that system during a tick:

```java
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;

public class Reaper {
    @System
    void reap(@Read Health h, Entity self, Commands cmds) {
        if (h.current() <= 0) {
            cmds.despawn(self);
        }
    }
}
```

`Reaper.reap` asks for `Health`, the current entity, and a `Commands`
handle. When health has dropped to zero it enqueues a despawn. Nothing
happens to the world right then — the call returns, iteration continues,
and at the end of the stage the world processes every queued command in
one batch.

## The eight commands

`Commands` exposes exactly eight operations, backed by a sealed
`Command` interface and eight record types. From `Commands.java`:

```java
public sealed interface Command permits
    SpawnCommand,
    DespawnCommand,
    AddCommand,
    RemoveCommand,
    SetCommand,
    InsertResourceCommand,
    SetRelationCommand,
    RemoveRelationCommand
{}
```

Each `record` mirrors a method on the `Commands` instance:

| Method                                                   | Effect at flush |
|----------------------------------------------------------|-----------------|
| `spawn(Record... components)`                            | `world.spawn(...)` with the given components |
| `despawn(Entity entity)`                                 | `world.despawn(entity)` — silently no-ops if already dead |
| `add(Entity entity, Record component)`                   | `world.addComponent(entity, component)` if the entity is alive |
| `remove(Entity entity, Class<? extends Record> type)`    | `world.removeComponent(entity, type)` if alive |
| `set(Entity entity, Record component)`                   | `world.setComponent(entity, component)` if alive |
| `insertResource(T resource)`                             | Calls `world.setResource(resource)` |
| `setRelation(Entity source, Entity target, T value)`     | Enqueues a relation write — teaser for the relations chapter |
| `removeRelation(Entity source, Entity target, Class<T>)` | Removes a relation edge |

That's the whole surface. Anything structural you might want to do to the
world from inside a system has a `Commands` method for it.

## Spawning from a system

The classic use of `Commands`: producing new entities in response to what
the system sees.

```java
public record Spawner(float rate, float cooldown) {}
public record Bullet() {}

public class Shooter {
    @System
    void fire(@Read Position p, @Write Mut<Spawner> s, Commands cmds) {
        var spawner = s.get();
        if (spawner.cooldown() > 0) {
            s.set(new Spawner(spawner.rate(), spawner.cooldown() - 1));
            return;
        }

        cmds.spawn(
            new Position(p.x(), p.y()),
            new Velocity(0, 50),
            new Bullet()
        );
        s.set(new Spawner(spawner.rate(), spawner.rate()));
    }
}
```

Every bullet is enqueued during the `Update` stage. When the stage
finishes, each `SpawnCommand` is handed to `World.spawn(components)` in
order. None of the newly-spawned bullets are visible to any system in the
same stage — the next stage is the earliest point they can be iterated.

## Adding, removing, and setting

```java
public record Stunned(int ticksLeft) {}

public class StunApplier {
    @System
    void applyStun(@Read Health h, Entity self, Commands cmds) {
        if (h.current() < 10) {
            cmds.add(self, new Stunned(60));     // attach Stunned
        }
    }
}

public class StunTicker {
    @System
    void tick(@Write Mut<Stunned> s, Entity self, Commands cmds) {
        var cur = s.get();
        if (cur.ticksLeft() <= 1) {
            cmds.remove(self, Stunned.class);    // detach Stunned
        } else {
            s.set(new Stunned(cur.ticksLeft() - 1));
        }
    }
}
```

- `add(entity, component)` attaches a component and moves the entity into
  the archetype that includes it.
- `remove(entity, componentClass)` detaches a component and moves the
  entity to the archetype without it.
- `set(entity, component)` replaces the value of a component the entity
  already has. For simple in-system mutations, prefer `@Write Mut<T>` —
  `set` is for the case where you want the write to apply at flush time
  instead of in place.

Each of these is safe to issue even if the entity might be dead by the
time flush runs. The `CommandProcessor` checks `world.isAlive(entity)`
before every mutation and silently drops commands for entities that
didn't survive.

## Commands flush at stage boundaries

The one timing rule you must internalise:

> Commands enqueued during stage *S* are flushed at the **end of stage
> *S*, before stage *S+1* begins**.

That means:

- A system in `"Update"` that spawns an entity will see the new entity
  iterated by systems in `"PostUpdate"` — not by any other `"Update"`
  system, even ones scheduled `after` it.
- If you despawn from `"Update"`, the entity still exists for the rest of
  `"Update"`. The `isAlive` check on flush prevents double-despawn errors
  but does not bring the entity back during the stage.
- Resources inserted via `cmds.insertResource(...)` are visible to the
  next stage, not the rest of the current one.

The flush happens in `World.executeStage(...)`, immediately after the
executor finishes running every system in the stage:

```java
private void executeStage(ScheduleGraph graph) {
    executor.execute(graph, this::executeSystem);
    flushPendingCommands();   // here — between stages
}
```

So if you need a command's effect to be visible *within the same stage*,
split your logic across two stages.

## Why commands exist

The short version: **thread safety**. When the scheduler runs systems in
parallel, every worker thread owns its own slice of the iteration. If one
of those workers called `world.spawn` directly, it would mutate the
archetype graph that another worker is iterating, and you would either
crash or silently lose work.

`Commands` sidesteps the whole problem:

- Each system gets its own `Commands` buffer — no contention between
  parallel systems.
- All buffers are drained serially between stages on the main thread.
- Every mutation is re-validated against the live world at flush time.

Even in a single-threaded executor, using `Commands` is still the right
habit — it keeps your systems portable to parallel execution with zero
refactoring, and it makes "spawn during iteration" trivially safe.

## A teaser for relations

`Commands` also has two methods for a feature you have not seen yet:

```java
cmds.setRelation(source, target, new ChildOf());
cmds.removeRelation(source, target, ChildOf.class);
```

These let you create and destroy edges between entities (parent/child,
owner/owned, targeting, follower-of, whatever you model) from inside a
system, with the same "apply at stage boundary" semantics as the other
commands. The relations chapter covers the full story; for now, just
know that structural relation changes also go through `Commands`.

## A full example

```java
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;

public record Health(int current, int max) {}
public record DamageOverTime(int perTick, int ticksRemaining) {}

public class DotProcessor {

    @System
    void tick(
        @Write Mut<DamageOverTime> dot,
        @Write Mut<Health> hp,
        Entity self,
        Commands cmds
    ) {
        var d = dot.get();
        var h = hp.get();

        int newHp = h.current() - d.perTick();
        hp.set(new Health(Math.max(0, newHp), h.max()));

        if (newHp <= 0) {
            cmds.despawn(self);
            return;
        }

        if (d.ticksRemaining() <= 1) {
            cmds.remove(self, DamageOverTime.class);
        } else {
            dot.set(new DamageOverTime(d.perTick(), d.ticksRemaining() - 1));
        }
    }
}
```

This one system applies damage, removes the DoT once its duration ticks
out, and despawns entities it kills — all without ever touching the world
directly. The scheduler is free to run `DotProcessor.tick` in parallel
with any other system that does not write `Health` or `DamageOverTime`,
and every mutation lands safely at the end of the `Update` stage.

## Quick recap

- `Commands` is a service parameter: `@System void foo(..., Commands cmds)`.
- The eight methods cover every structural mutation you can do from a
  system body.
- All commands flush at the end of the stage they were enqueued in.
- Use `Commands` any time you mutate the world from inside a system —
  never call `world.spawn` / `world.despawn` / `world.addComponent`
  directly from a system.
- `setRelation` / `removeRelation` are how relations plug into the same
  flush pipeline; more on that in the relations chapter.

## What's next

You have reached the end of the **Basics** section. You can now:

- Design components as records and understand how archetypes form.
- Spawn, inspect, and despawn entities.
- Write systems with `@Read`, `@Write Mut<T>`, `Res<T>`, `ResMut<T>`,
  `Entity`, `Commands`, and `RemovedComponents<T>`.
- React to newly-added and recently-changed components with `@Filter`.
- Stay safely scheduled under parallel execution by enqueueing structural
  changes through `Commands`.

From here you are ready to move on to the advanced tutorials — stages and
scheduling, events, local state, the `@Where` predicate language, and
**relations**, which generalise parent/child and targeting into a
first-class concept.
