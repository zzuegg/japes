# Components

Components are the raw data of an entity. In japes every component is a Java
`record` — nothing more, nothing less. Pick a shape, declare a record, and the
framework takes care of the rest.

!!! tip "The one rule"
    **Every component must be a `record`.** Classes, enums, and sealed types are
    rejected at registration time with an `IllegalArgumentException`. If it is
    not a `record`, it is not a component.

## Your first component

A component is a plain Java 16+ record. Fields hold whatever data you want an
entity to carry:

```java
package game;

public record Position(float x, float y) {}
public record Velocity(float dx, float dy) {}
public record Health(int current, int max) {}
```

That is the entire component declaration. You never subclass a base type, you
never implement a marker interface, you never register a schema. Spawn an
entity with any of these records and japes will register them on first use.

## Allowed field types

A component record can hold any Java type in its fields — primitives, boxed
numbers, `String`, other records, collections, whatever you like. But the
storage layer optimises heavily for the shape you chose:

| Field shape                                   | What happens internally |
|-----------------------------------------------|--------------------------|
| All primitives (`int`, `float`, `double`, ...) | Flat storage: the record is unboxed into a null-restricted array, one slot per entity. |
| Any non-primitive field (`String`, nested records, arrays, ...) | Reference storage: the archetype keeps an `Object[]` of component instances. |

Both are correct, but the flat path is dramatically friendlier to the CPU
cache. When performance matters, prefer numeric primitives:

```java
// Flat — one float[] x 2 slot per chunk, tight cache locality
public record Transform(float x, float y, float rotation) {}

// Still valid, but Object[] — the JIT cannot pack reference fields
public record Nameplate(String text, int fontSize) {}
```

!!! note
    You never ask for flat storage. The `DefaultComponentStorage` detects a
    record whose components are all primitives and switches to an unboxed
    layout automatically. The only way to opt out of it is to add a reference
    field.

## Marker components

A "marker" is a component with no fields — an empty record. Markers carry no
data; they exist purely so queries can filter entities that have (or lack) a
tag:

```java
public record Player() {}
public record Enemy() {}
public record Frozen() {}
public record NeedsRespawn() {}
```

You attach a marker by spawning it like any other component:

```java
world.spawn(new Position(0, 0), new Velocity(1, 0), new Player());
```

Later, a system can select for entities that carry the marker using
`@With` / `@Without` — but that lives in the queries chapter. For now, just
know that an empty record is a first-class component.

## Component identity is internal

Under the hood every component class is assigned a small integer called a
`ComponentId`. That id is what archetypes, storages, and change trackers key
off. You will see the type in stack traces and in the source, but there is no
user-facing API that hands you one. You never:

- allocate a `ComponentId` yourself
- store a `ComponentId` in a field
- pass a `ComponentId` to `spawn` / `getComponent` / a system parameter

Everything is keyed by `Class<? extends Record>` at the surface, and the
framework does the id lookup on your behalf. If the tutorial or a Javadoc ever
mentions a `ComponentId`, treat it as implementation trivia.

## Archetypes form automatically

When you spawn an entity with `(Position, Velocity)`, japes looks up the
archetype for *that exact set of components* and stores the record data in
columnar form. Another spawn with `(Position, Velocity, Health)` lands in a
different archetype. You do not create archetypes, you do not register
them — they appear on demand from the set of components you hand to `spawn`.

```java
var a = world.spawn(new Position(0, 0), new Velocity(1, 0));
var b = world.spawn(new Position(5, 5), new Velocity(0, 1));
var c = world.spawn(new Position(9, 9), new Velocity(0, 0), new Health(100, 100));
// a and b share the (Position, Velocity) archetype
// c lives in (Position, Velocity, Health)
```

Adding or removing a component at runtime moves the entity between
archetypes. That is a deliberate (slightly expensive) operation — the
[Entities chapter](02-entities.md) covers it in detail.

## Two quick rules of thumb

1. **One record = one meaning.** Do not jam ten concepts into one mega record
   so every system has to touch it. Split them. Archetype moves are cheaper
   than the contention of a God component.
2. **Small records, many entities.** japes is fastest when each component is a
   handful of primitives and you have thousands or millions of entities.

## A mistake you will probably make once

You declare a component as a `class` by habit, try to spawn it, and watch
the world blow up at registration time:

```java
public class Position { float x, y; }          // WRONG — not a record

var world = World.builder().build();
world.spawn(new Position());
// IllegalArgumentException: Components must be records: game.Position
```

Fix: change `class` to `record` and give it a canonical constructor. The
error is loud and early — it is not something you can ship by accident —
but it is worth recognising so you do not spend ten minutes debugging.

```java
public record Position(float x, float y) {}    // RIGHT

world.spawn(new Position(0, 0));                // happy path
```

Keep that rule in the back of your head and the rest of the tutorial will
flow easily: **if you can imagine it as data, it is a record; if it is a
record, it is a component**.

## A teaser for relations

You can also model edges between entities — "parented to", "targeting",
"owes gold to". These are a separate feature called **relations** and use a
different annotation (`@Relation`) on top of a record. They are covered in a
later chapter; for now, just know that a plain record plus the `@Relation`
marker turns the record into a first-class edge type. See the
[relations section](../relations/index.md) once you are comfortable with the
basics.

## What's next

Now that you can declare components, the next step is spawning entities and
letting archetypes form around them.

Continue to **[Entities](02-entities.md)**.
