# Queries

A query is how a system asks for component data. In japes a "query" is not a
separate object — it is simply the parameter list of your `@System` method.
Annotate each component parameter with `@Read` or `@Write` and the scheduler
gives you only the entities that have every one of those components.

!!! tip "Parameters are the query"
    Any `@Read C` or `@Write Mut<C>` parameter acts as an implicit `With` on
    component `C`. A system with `@Read Position` and `@Read Velocity` runs
    once per entity that has *both* components. Entities missing either are
    never visited.

## Read-only access with `@Read`

Use `@Read` for components you only need to look at. The parameter type is
the component record itself:

```java
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Read;

public class Debug {
    @System
    void logPositions(@Read Position pos) {
        java.lang.System.out.println(pos);
    }
}
```

The method is invoked **once per matching entity**. `pos` is the current
value of that entity's `Position`, already resolved by the framework. You do
not look it up, you do not ask for an id.

Because records are immutable, there is nothing you can do to a `@Read`
parameter that mutates the world — the type system protects you.

## Mutable access with `@Write Mut<T>`

To mutate a component you need two things: the `@Write` annotation on the
parameter, and the parameter's type has to be `Mut<T>` where `T` is the
component record:

```java
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.Write;

public class Movement {
    @System
    void integrate(@Read Velocity vel, @Write Mut<Position> pos) {
        var p = pos.get();
        pos.set(new Position(p.x() + vel.dx(), p.y() + vel.dy()));
    }
}
```

That is the full shape: `@Write Mut<Position> pos`. The `Mut<T>` handle is
your mutation receipt — you call `get()` to read the current value, `set(T)`
to stage a new one, and the framework writes it back after your method
returns.

## The `Mut<T>` surface

`Mut<T>` has a small, deliberate API. Everything you can do to it is on this
one class:

| Method              | Purpose |
|---------------------|---------|
| `T get()`           | Read the current staged value. First call returns the existing storage value. |
| `void set(T value)` | Stage a new value and mark the `Mut` as changed. |
| `boolean isChanged()` | Returns `true` if `set` has been called on this iteration. |
| `T flush()`         | Framework-only: writes the staged value back and marks the change-tracker. You do not call this. |

A typical read-modify-write pattern looks like:

```java
@System
void decay(@Write Mut<Health> hp) {
    var h = hp.get();
    if (h.current() > 0) {
        hp.set(new Health(h.current() - 1, h.max()));
    }
}
```

Records are immutable, so you always construct a new instance and hand it to
`set`. If you do not call `set`, the `Mut` stays in its "unchanged" state and
the component is not written back — which also means the
[change-detection](06-change-detection.md) tracker is not triggered. That is
how japes avoids paying for writes you did not actually make.

## Asking for the current entity

Sometimes your system needs to know *which* entity it is visiting — for
example to hand that id to `Commands.despawn`. Declare an `Entity` parameter
anywhere in the method and the scheduler will fill it with the current
entity for each iteration:

```java
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.command.Commands;

public class Reaper {
    @System
    void reap(@Read Health h, Entity self, Commands cmds) {
        if (h.current() <= 0) {
            cmds.despawn(self);
        }
    }
}
```

`self` is not a query filter — it does not change which entities you visit,
it just makes the current entity's id available inside the method body.
Multiple `Entity` parameters all receive the same id (unlikely to be useful,
but it is legal).

## Implicit `With` semantics

Because every `@Read` / `@Write` parameter also acts as a **With** filter,
you get the intersection of all required components for free:

```java
@System
void aiStep(@Read Position p, @Read Velocity v, @Write Mut<Brain> b) {
    // Runs only on entities that carry all three of:
    //   Position, Velocity, Brain
}
```

If you want to *exclude* a component (runs only on entities that do NOT
have it), or require a component without actually reading it, look up
`@With` and `@Without` in the advanced queries chapter.

## What the code generator does with your query

You can skip this section — it is informational — but it is useful to know
what you are paying for.

When you build a world, japes's tier-1 **ChunkProcessorGenerator** produces
a specialised bytecode class **per system method**. Inside that class:

1. The storages for every component the method touches are **hoisted out of
   the per-entity loop** so the chunk's storage array lookup happens once
   per chunk instead of once per entity.
2. The call into your `@System` method becomes an **inlined `invokevirtual`**
   on a hidden class that the JIT can fully specialise — no `MethodHandle`
   dispatch, no reflection at iteration time.
3. For `@Write Mut<T>` parameters the generator even hoists the `Mut`
   instance and reuses it across every entity in the chunk (reset with a
   new value per iteration) to keep allocation out of the hot loop.

Net effect: `integrate(@Read Velocity, @Write Mut<Position>)` compiles down
to tight code that looks roughly like this in pseudocode:

```java
// one prepareChunk call, then:
for (int slot = 0; slot < chunkCount; slot++) {
    velocity = velStorage.get(slot);       // direct array access
    mut.resetValue(posStorage.get(slot), slot);
    system.integrate(velocity, mut);       // specialised invokevirtual
    if (mut.isChanged()) {
        posStorage.set(slot, mut.flush()); // mark changed, write back
    }
}
```

You get to write one readable `@System` method and the framework turns it
into something that autovectorises nicely. That is the point.

## A fuller example

```java
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.entity.Entity;

public class Simulation {
    @System
    void applyGravity(@Write Mut<Velocity> v) {
        var cur = v.get();
        v.set(new Velocity(cur.dx(), cur.dy() - 9.8f));
    }

    @System(after = "Simulation.applyGravity")
    void integrate(@Read Velocity v, @Write Mut<Position> p) {
        var pos = p.get();
        p.set(new Position(pos.x() + v.dx(), pos.y() + v.dy()));
    }

    @System(after = "Simulation.integrate")
    void killIfOffScreen(@Read Position p, Entity self, Commands cmds) {
        if (p.y() < -1000) cmds.despawn(self);
    }
}
```

Three systems, three `@System` annotations, three different access patterns.
Each one runs once per matching entity per tick; each one takes what it
needs and nothing else.

## What's next

Queries give you per-entity data. But sometimes you want **world-wide** state
shared across every system — configuration, a random generator, an elapsed
time counter. That is resources.

Continue to **[Resources](05-resources.md)**.
