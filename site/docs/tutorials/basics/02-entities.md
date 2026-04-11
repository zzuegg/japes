# Entities

An entity is an identity — a handle that points at a row of component data
inside a world. This chapter covers creating them, destroying them, checking
whether one is still alive, and the (rarely-needed) escape hatch for reading
a component outside a system.

!!! note "Entities are values, not objects"
    `Entity` is a `record` wrapping a single `long`. You can safely store it
    in fields, pass it to other threads, or stick it in a `HashMap` key. You
    cannot use it to mutate anything directly — every mutation goes through
    the `World`.

## The `Entity` record

Internally, an `Entity` is a packed 64-bit id. The high 32 bits are the
**index** (its slot in the allocator's free list) and the low 32 bits are a
**generation** counter that increments every time the index is reused:

```java
package zzuegg.ecs.entity;

public record Entity(long id) {
    public static Entity of(int index, int generation) { ... }
    public int index() { ... }
    public int generation() { ... }
}
```

You rarely care about the encoding, but the generation is what makes a dead
entity handle fail fast: if you despawn entity `(index=7, gen=3)` and a new
entity is later allocated at index `7`, the new one carries `gen=4`. Your
stale handle still encodes `gen=3`, so `world.isAlive(...)` correctly
reports it as dead.

## Spawning an entity

Spawning is one call. Pass the components you want the entity to start with,
in any order, and you get back an `Entity` handle:

```java
var world = World.builder().build();

var player = world.spawn(
    new Position(0, 0),
    new Velocity(0, 0),
    new Health(100, 100),
    new Player()
);
```

The signature is `world.spawn(Record... components)`. Every component you
pass **defines the entity's archetype** — the exact set of component classes
determines which archetype (and which chunk) the entity lands in. Spawning a
second entity with the same set of component types reuses that archetype:

```java { .java .annotate }
var a = world.spawn(new Position(0, 0), new Velocity(1, 0));   // (1)
var b = world.spawn(new Position(5, 5), new Velocity(0, 1));   // (2)
var c = world.spawn(new Position(9, 9), new Velocity(0, 0),    // (3)
                    new Health(100, 100));
```

1. Creates archetype `{Position, Velocity}` and inserts `a` at slot 0.
2. Reuses the same archetype — `b` lands at slot 1 in the same chunk.
3. A different component set, so a fresh archetype `{Position, Velocity, Health}`
   is created and `c` is inserted there.

The first time a component class is seen it is registered automatically; you
do not need to pre-declare anything before spawning.

## Despawning

Despawning takes an `Entity` handle and removes it from its archetype. Any
other entities that were occupying later slots in the same chunk are
swap-removed up to fill the gap — the allocator recycles the index and bumps
its generation counter so stale handles stop resolving.

```java
world.despawn(player);
assert !world.isAlive(player); // the stale handle no longer resolves
```

If you call `despawn` on an entity that has already been despawned the
framework throws `IllegalArgumentException`. Inside a system, or anywhere
else where you are not sure whether the handle is still valid, guard with
`isAlive`:

```java
if (world.isAlive(target)) {
    world.despawn(target);
}
```

!!! tip "Don't despawn from inside a parallel system"
    Mutating the world from inside a running system has obvious threading
    problems. Use the `Commands` service parameter to enqueue a
    `despawn` that runs at the next stage boundary — see the
    [Commands chapter](08-commands.md) for the full story.

## Checking liveness

`world.isAlive(entity)` is cheap — it is an `O(1)` generation comparison:

```java
var npc = world.spawn(new Position(10, 0), new Health(30, 30));

// ... later ...
if (world.isAlive(npc)) {
    // still around
}
```

Use it as a guard on long-lived handles that you cached. Inside a system,
the scheduler will only hand you live entities to begin with, so you almost
never need to call it there.

## Reading a component directly

`world.getComponent(entity, Class)` returns the entity's current value for
that component:

```java
var hp = world.getComponent(player, Health.class);
System.out.println("Player HP: " + hp.current() + "/" + hp.max());
```

**Systems are the normal path for reading components.** `getComponent` exists
as an escape hatch — think "main method setup", "debug printout",
"end-of-run reporting". Inside your game logic you almost never want it:

- It does a full archetype/chunk lookup every call. A system sees the
  component as a plain parameter, which the tier-1 code generator turns into
  a direct array access.
- It throws `IllegalArgumentException` if the entity is dead or does not
  have the requested component.
- It bypasses the scheduler's access plan, which is how japes decides which
  systems can run in parallel. A system that takes `@Read Health` participates
  in that plan; a raw `world.getComponent(..., Health.class)` does not.

So: prefer systems. If you catch yourself sprinkling `getComponent` through
gameplay code, that is a signal to move the logic into a system.

```java
// Fine for setup and tests
public static void main(String[] args) {
    var world = World.builder().build();
    var e = world.spawn(new Position(1, 2), new Health(10, 10));
    var h = world.getComponent(e, Health.class);
    assert h.current() == 10;
}
```

## How archetypes form from the spawn signature

Because the **set of component classes** you pass to `spawn` is what selects
the archetype, the order does not matter:

```java
// These two land in the SAME archetype
world.spawn(new Position(0, 0), new Velocity(1, 0));
world.spawn(new Velocity(1, 0), new Position(0, 0));
```

But adding or removing even one component class produces a *different*
archetype, which means a different chunk with a different storage layout.
This is the fundamental trade the ECS is making: iteration is blazingly
fast because every entity in a chunk has exactly the same components in
exactly the same order, but moving an entity between archetypes costs a
copy.

The takeaway: **design your components so the "normal" archetype for a
type of entity is stable**. Avoid flip-flopping a marker component on and
off every frame; if you need per-frame state, put it in a dedicated
component field instead.

## What's next

You can now spawn, tag, despawn, and inspect entities. The next step is
teaching the world what to *do* with them — systems.

Continue to **[Systems](03-systems.md)**.
