# Query filters: `@With` and `@Without`

`@With(T.class)` and `@Without(T.class)` are method-level annotations that narrow a system's archetype filter **without** binding the component to a parameter. They are the idiomatic way to say "this system only applies to entities that happen to carry a marker component" — the canonical "player-only" or "not-enemy" filter.

## The annotations

Both live in `zzuegg.ecs.system`:

```java
@With(Player.class)                    // restrict to archetypes that include Player
@Without(Dead.class)                   // exclude archetypes that include Dead
@System
void movePlayers(@Write Mut<Position> p, @Read Velocity v) { ... }
```

Each annotation is `@Repeatable`, so you can stack them:

```java
@With(Player.class)
@With(Controller.class)
@Without(Dead.class)
@Without(Stunned.class)
@System
void playerInput(@Read Input in, @Write Mut<Velocity> v) { ... }
```

The value is always a `Class<? extends Record>`. There is no string form — if the type doesn't exist at compile time, the annotation won't either.

## What they do

During system parsing, `@With` values are collected into `withFilters` and `@Without` values into `withoutFilters` on the system descriptor. At archetype-matching time, the schedule walks every archetype and includes it in this system's chunk set only if:

- **Every `@Read` / `@Write` component type** the method declares is present in the archetype (this is the base filter — unchanged by `@With`/`@Without`).
- **Every `@With` type** is present in the archetype.
- **No `@Without` type** is present in the archetype.

The important property: the marker components are *not* parameters of the system. They contribute to archetype matching only. You don't get a `Player` reference passed to the method — you just know that if the system is running on an entity, that entity has one.

```java
public record Player() {}     // zero-field marker record

// Applied to every entity that has Position, Velocity, AND Player —
// but the method signature never mentions Player.
@With(Player.class)
@System
void movePlayers(@Write Mut<Position> p, @Read Velocity v) { ... }
```

!!! tip "Marker components are the idiomatic way to tag entities"

    A record with no fields allocates essentially nothing — it stores a single instance per archetype, not per entity. Use them freely for flags like `Player`, `Enemy`, `Dead`, `Hidden`, `NeedsRespawn`.

## `@With` vs. `@Read` for markers

These two look equivalent but aren't:

```java
// Variant A — binds as parameter, pays a slot in the generated chunk processor.
@System
void moveA(@Write Mut<Position> p, @Read Player marker, @Read Velocity v) { ... }

// Variant B — filter only, no parameter slot.
@With(Player.class)
@System
void moveB(@Write Mut<Position> p, @Read Velocity v) { ... }
```

Both iterate the same entities. Variant B is preferred because:

1. **It does not consume a tier-1 component parameter slot.** The tier-1 bytecode path has a hard limit of 4 component params; spending one of them on a marker you never read is wasteful.
2. **The method signature is cleaner.** Anyone reading the code sees "this is a filter", not "this is data we use".
3. **The DAG builder sees it as a filter, not an access.** Markers added via `@Read` register as component accesses; markers added via `@With` register as filters only.

!!! warning "Marker records must still be registered"

    `@With(Player.class)` makes the component required for matching, but Player must actually be attached to entities via `commands.spawn(new Player(), ...)` or the archetype filter won't match anything. The annotation does not create the component.

## Use case: player-only systems

The most common pattern. Tag player entities with a marker record, then gate every player-specific system on that marker.

```java
public record Player() {}

@System
void spawnPlayer(Commands cmds) {
    cmds.spawn(new Player(), new Position(0, 0), new Velocity(0, 0), new Health(100));
}

@With(Player.class) @System
void readPlayerInput(@Write Mut<Velocity> v, ResMut<InputState> input) { ... }

@With(Player.class) @System
void cameraFollowPlayer(@Read Position p, ResMut<Camera> cam) { ... }

@With(Player.class) @System
void playerInvulnerabilityTick(@Write Mut<Invulnerability> inv) { ... }
```

Every one of these runs exactly over the entities tagged `Player`, and only those.

## Use case: exclude relation markers

Combined with relations, `@Without` is a concise way to skip "already-busy" entities.

```java
public record Hunting(Entity target) {}     // relation marker

@Without(Hunting.class) @System              // → only unoccupied hunters
void lookForTarget(@Read Enemy e, @Write Mut<Target> t) { ... }

@With(Hunting.class) @System                 // → only entities already hunting
void pursueTarget(@Read Position pos, @Read Hunting hunt, @Write Mut<Velocity> v) { ... }
```

The two systems are mutually exclusive on any single entity by construction — `Hunting` is present or it isn't. The DAG builder notices the disjoint-archetype property and, under a multi-threaded executor, runs them in parallel even though they both touch entities with the same base set of components.

## Use case: unlocking parallel writes

This is the most important performance implication of `@With` / `@Without` and where the DAG builder earns its keep. If two systems both write `Position`, the conflict detection normally forces them to run sequentially. But if their filter sets prove their archetype matches are disjoint, the edge is dropped.

```java
@With(Player.class)                @Without(Dead.class)
@System void movePlayer (@Write Mut<Position> p, @Read Velocity v) { ... }

@With(Enemy.class)  @Without(Player.class) @Without(Dead.class)
@System void moveEnemy  (@Write Mut<Position> p, @Read Velocity v) { ... }

@With(Projectile.class)
@System void moveProjectile(@Write Mut<Position> p, @Read Velocity v) { ... }
```

All three write `Position`, but `@With(Player.class)` excludes enemies and projectiles; `@With(Enemy.class)` excludes players and projectiles; `@With(Projectile.class)` has no overlap with the other two. Under `Executors.multiThreaded()`, all three run in parallel.

!!! tip "Prefer many narrow systems over one wide one"

    Splitting a monolithic `moveEverything` into three narrower systems with disjoint `@With` markers costs nothing in single-threaded mode and gains you 3× parallelism in multi-threaded mode. The DAG builder is doing the hard work for free.

## `@Read` `@With` gotcha

It is tempting to write:

```java
@With(Player.class)
@System
void playerHeal(@Write Mut<Health> hp, @Read Player p) { ... }    // redundant
```

The `@Read Player p` parameter is redundant — `@With(Player.class)` already narrowed the query, and the `p` you get in every call is an immutable marker record with no data. Drop the parameter and let the filter speak for itself.

## Interaction with `@Without` and removal tracking

`@Without(Dead.class)` is not a change filter. It gates the archetype at match time, so an entity gains `Dead` mid-tick (via `Commands.add`) only becomes invisible to the system **after** the stage boundary where the command flushes. Within the same stage, ordering relative to the producer of `Dead` matters.

If you need to react to the transition "just became dead", use a `RemovedComponents<Alive>` parameter or an explicit `DeathEvent` — `@Without` is a static filter, not a transition.

## What's next

- [`@Where` field predicates](14-where-filters.md) — filter by component field values, not just presence.
- Related basics: [Queries](../basics/04-queries.md), [Entities](../basics/02-entities.md).
