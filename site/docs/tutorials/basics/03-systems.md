# Systems

A system is the behaviour half of the ECS: a plain Java method, annotated
with `@System`, that the world runs every tick. You do not subclass a base
class, you do not implement an interface — you write a method on an
arbitrary class and japes figures out the rest.

!!! tip "Systems are methods, not classes"
    When people in other ECS libraries say "a system", they usually mean a
    class. In japes, **the method is the system**. You can put many systems
    on the same class and each one will be registered, parsed, and scheduled
    independently.

## Your first system

The simplest possible system logs something:

```java
package game;

import zzuegg.ecs.system.System;

public class Greeter {
    @System
    void sayHello() {
        java.lang.System.out.println("Tick!");
    }
}
```

And you wire it into the world builder:

```java
var world = World.builder()
    .addSystem(Greeter.class)
    .build();

world.tick(); // prints "Tick!"
```

`World.builder().addSystem(Class)` tells the world to inspect the class,
parse every `@System` method on it, and register them. `World.tick()` then
runs every registered system once (grouped by stage; see below).

## One instance per class

This is the one rule of `addSystem(Class<?>)` you need to remember:

> For a given class, japes creates **exactly one instance** and all of its
> `@System` methods share that instance.

Concretely: if `Greeter` has three `@System` methods, they will all be
invoked on the same `Greeter` object. This means you can keep ordinary
Java field state on the class and share it across systems on that class:

```java
public class Counter {
    private int ticks = 0;          // shared across both systems below
    private int spawns = 0;

    @System void countTicks()   { ticks++; }
    @System void reportIfLoud() { if (ticks % 100 == 0) log(ticks, spawns); }
}
```

If you need multiple independent instances of the same class, or you want
to construct the instance yourself with a DI container, pass an object to
the overload `addSystem(Object)` instead:

```java
var counterA = new Counter();
var counterB = new Counter();

var world = World.builder()
    .addSystem(counterA)
    .addSystem(counterB)
    .build();
```

Otherwise japes calls the class's no-arg constructor reflectively (it will
set it accessible, so `private` is fine). If there is no no-arg constructor,
parsing fails loudly at build time — see `SystemParser.parse(...)`.

## What `@System` actually looks like

The annotation lives at `zzuegg.ecs.system.System` and carries three
attributes, all optional:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface System {
    String   stage()  default "";
    String[] after()  default {};
    String[] before() default {};
}
```

### `stage()`

Stages are named groups of systems that run in order every tick. The world
comes with five built-in stages:

| Stage        | Typical use                                     |
|--------------|--------------------------------------------------|
| `"First"`    | Frame setup, clearing per-tick state             |
| `"PreUpdate"`| Input gathering, network receive                 |
| `"Update"`   | Gameplay logic (**this is the default**)         |
| `"PostUpdate"`| Transform propagation, post-process             |
| `"Last"`     | Cleanup, metrics, frame end                      |

If you don't set `stage()`, the system runs in `"Update"`:

```java
public class Gameplay {
    @System void move(@Read Velocity v, @Write Mut<Position> p) { /* ... */ }

    @System(stage = "PostUpdate")
    void applyTransforms(@Write Mut<Position> p) { /* ... */ }
}
```

Commands enqueued by systems in a given stage are flushed at the end of
that stage, before the next stage runs — the
[Commands chapter](08-commands.md) has the details.

### `after()` and `before()`

Inside a single stage, systems run in an order determined by the scheduler.
Use `after` and `before` to constrain that order by **system name**. A
system's name is the simple form `ClassName.methodName`:

```java
public class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p) { /* ... */ }

    @System(after = "Physics.integrate")
    void clampToWorldBounds(@Write Mut<Position> p) { /* ... */ }

    @System(before = "Physics.integrate")
    void applyGravity(@Write Mut<Velocity> v) { /* ... */ }
}
```

`after` says "run me strictly after these system(s)". `before` says "run me
strictly before these". Both take a `String[]` so you can list several:

```java
@System(after = {"Input.poll", "AI.decide"})
void dispatchCommands(Commands cmds) { /* ... */ }
```

Cycles in the ordering constraints are detected at schedule build time and
will throw — you cannot accidentally deadlock.

## Registering multiple systems at once

Any number of `@System` methods can live on the same class:

```java
public class Movement {
    @System
    void gravity(@Write Mut<Velocity> v) {
        v.set(new Velocity(v.get().dx(), v.get().dy() - 9.8f));
    }

    @System(after = "Movement.gravity")
    void integrate(@Read Velocity v, @Write Mut<Position> p) {
        var pos = p.get();
        p.set(new Position(pos.x() + v.dx(), pos.y() + v.dy()));
    }

    @System(stage = "PostUpdate")
    void clamp(@Write Mut<Position> p) {
        var pos = p.get();
        if (pos.y() < 0) p.set(new Position(pos.x(), 0));
    }
}

var world = World.builder()
    .addSystem(Movement.class)
    .build();
```

All three methods are parsed, wired to a single `Movement` instance, and
scheduled independently. That one `addSystem(Movement.class)` call is all
that it takes.

## Picking good system names

Because `after` / `before` reference systems by string, treat the method
name as part of your API. Rename with care — you will break the ordering
constraints of any system that refers to the old name. For the same
reason, prefer specific method names (`Physics.integrate`) over generic
ones (`Physics.update`).

## What comes next

A system that takes no parameters is fine for setup and teardown, but the
real work happens once you start asking the world for **component data**.
That is queries.

Continue to **[Queries](04-queries.md)**.
