# Resources

Sometimes a system needs data that is not per-entity: the delta time of the
frame, an RNG, a game config, a running score. In japes that kind of data
lives in a **resource** — a single object stored by class on the world, and
handed to systems via `Res<T>` or `ResMut<T>` parameters.

!!! note "One instance per type"
    Resources are keyed by the **concrete class** of the value you store. A
    world can hold at most one resource of each class. Inserting a second
    one **replaces** the first.

## When to use a resource

Use a resource whenever you have state that:

- is not tied to any particular entity
- is read and/or written by one or more systems
- should not be duplicated across a million entities as a component

Typical examples:

| Use case                    | Example resource                                   |
|-----------------------------|----------------------------------------------------|
| Frame timing                | `record DeltaTime(float seconds) {}`               |
| Deterministic RNG           | `record GameRng(java.util.Random random) {}`      |
| Game configuration          | `record PhysicsConfig(float gravity, float damping) {}` |
| Stats accumulators          | `record FrameStats(long entitiesProcessed) {}`     |
| Singleton external handles  | `record AudioDevice(Mixer mixer) {}`               |
| UI / input state            | `record Input(boolean jump, float axisX) {}`       |

A resource is just a Java object — it does not have to be a record, though
records are a natural fit for immutable-ish config. **Do not use resources
for data that belongs on an entity.** If you catch yourself storing a
`HashMap<Entity, Something>` in a resource, that "Something" is probably a
component.

## Registering a resource

Add the resource to the world builder before you call `build()`:

```java
public record DeltaTime(float seconds) {}
public record PhysicsConfig(float gravity, float damping) {}

var world = World.builder()
    .addResource(new DeltaTime(1f / 60f))
    .addResource(new PhysicsConfig(9.8f, 0.98f))
    .addSystem(Physics.class)
    .build();
```

`WorldBuilder.addResource(Object)` stores the value under its concrete
class. In this example the world now knows about two resources, keyed on
`DeltaTime.class` and `PhysicsConfig.class`.

!!! tip "Registering after build"
    You can also insert or replace a resource at runtime with
    `world.setResource(value)`, or from inside a system with
    `commands.insertResource(value)`. Systems that had resolved a `Res<T>` /
    `ResMut<T>` for the same type keep seeing the live value — the resource
    store updates the entry in place.

## Reading a resource

Declare a `Res<T>` parameter. Inside the method, call `.get()`:

```java
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.component.Mut;

public class Physics {
    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p, Res<DeltaTime> dt) {
        float step = dt.get().seconds();
        var pos = p.get();
        p.set(new Position(
            pos.x() + v.dx() * step,
            pos.y() + v.dy() * step
        ));
    }
}
```

The `Res<T>` wrapper is lightweight — it is a thin view over the stored
entry. `Res.get()` is the only method on it:

```java
public final class Res<T> {
    public T get();
}
```

A system that declares `Res<T>` will fail to build if the world never had a
resource of type `T` registered. The failure happens at build time with a
clear `IllegalArgumentException: Resource not found: ...`, so you catch
missing configuration before the first tick.

## Writing a resource

For mutable access declare a `ResMut<T>`. It adds a `set` method:

```java
public final class ResMut<T> {
    public T get();
    public void set(T value);
}
```

Use it when a system needs to *replace* the resource value — ideal for
immutable records where you construct a new instance and hand it back:

```java
import zzuegg.ecs.resource.ResMut;

public record FrameCount(long count) {}

public class FrameCounter {
    @System
    void tick(ResMut<FrameCount> fc) {
        fc.set(new FrameCount(fc.get().count() + 1));
    }
}
```

Any number of systems can declare a `Res<FrameCount>` (read-only view) and
they will all see the most recently written value. The scheduler treats
`ResMut<T>` as an exclusive write on the resource type, so it won't run two
systems that both mutate the same resource in parallel.

## Sharing mutable state without component bloat

One of the real wins of resources is that they let multiple systems share
mutable state **without inventing a "game state" component** that every
entity awkwardly carries. For example, a weather simulation:

```java
public record Weather(float temperature, float humidity, float wind) {}

public class WeatherTick {
    @System
    void drift(ResMut<Weather> w) {
        var cur = w.get();
        w.set(new Weather(
            cur.temperature() + randomDelta(),
            cur.humidity()    + randomDelta(),
            cur.wind()        + randomDelta()
        ));
    }
}

public class Evaporation {
    @System
    void evaporate(@Read Position p, @Write Mut<MoistureLevel> m, Res<Weather> w) {
        float rate = w.get().temperature() * 0.001f;
        m.set(new MoistureLevel(Math.max(0, m.get().value() - rate)));
    }
}
```

`Weather` is updated by exactly one system (`WeatherTick.drift`) and
observed by many (`Evaporation.evaporate`, plus any others that need it).
No component on any entity had to grow to carry weather — it is exactly one
object, stored once, and each system references it by type.

## Stats accumulators

A classic resource shape is a scalar counter that several systems bump:

```java
public record FrameStats(long entities, long mutations) {
    public FrameStats addEntity()   { return new FrameStats(entities + 1, mutations); }
    public FrameStats addMutation() { return new FrameStats(entities, mutations + 1); }
}

public class Stats {
    @System
    void countEntities(@Read Position p, ResMut<FrameStats> stats) {
        stats.set(stats.get().addEntity());
    }
}
```

Because `ResMut<FrameStats>` is exclusive, the scheduler serialises every
system that writes `FrameStats` against every other. If you need
high-contention accumulators, prefer a dedicated concurrent object inside
the resource rather than a plain record — the resource machinery does not
magically make your state thread-safe, it just makes it discoverable.

## Quick recap

- Resources are keyed by **concrete class**.
- `Res<T>` for read, `ResMut<T>` for read + set.
- Add them with `World.builder().addResource(value)`.
- Use them for world-wide state: config, RNG, singletons, stats.
- Do **not** use them for per-entity data; that is what components are for.

## What's next

So far every system runs against every matching entity, every tick. That is
often wasteful — most entities did not change. The next chapter covers how
to run a system only on entities that were recently added or mutated.

Continue to **[Change Detection](06-change-detection.md)**.
