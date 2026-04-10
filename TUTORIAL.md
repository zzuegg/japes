# japes tutorial

A step-by-step walkthrough of the japes API. Each section builds on
the previous one; by the end you have a worked example of every major
feature. For the quick-start version see the
[README](README.md); for the performance story see
[DEEP_DIVE.md](DEEP_DIVE.md).

## Contents

| # | Section | What you'll learn |
|--:|---|---|
|  1 | [Setting up](#1-setting-up) | Gradle dependency, JDK 26 preview, first `main` |
|  2 | [Components](#2-components) | Why components are records, field types, defaults |
|  3 | [Spawning entities](#3-spawning-entities) | `world.spawn(...)`, archetypes, entity handles |
|  4 | [Your first system](#4-your-first-system) | `@System`, `@Read`, `@Write Mut<C>`, the tick loop |
|  5 | [Resources](#5-resources) | `Res<R>`, `ResMut<R>`, per-world singletons |
|  6 | [Change detection](#6-change-detection) | `@Filter(Changed)`, `@Filter(Added)`, dirty-tracked observers |
|  7 | [Removed components](#7-removed-components) | `RemovedComponents<T>` for reacting to deletes |
|  8 | [Deferred edits with `Commands`](#8-deferred-edits-with-commands) | Spawning / despawning from inside parallel systems |
|  9 | [The `Entity` param](#9-the-entity-param) | Asking the scheduler for the current entity handle |
| 10 | [Events](#10-events) | `EventReader<E>` / `EventWriter<E>` for one-to-many messaging |
| 11 | [Local state](#11-local-state) | `Local<T>` for per-system mutable state |
| 12 | [Stages and ordering](#12-stages-and-ordering) | `@System(stage = "PostUpdate")`, `after` / `before` |
| 13 | [Multi-threading](#13-multi-threading) | `Executors.multiThreaded()`, disjoint-access parallelism |
| 14 | [Query filters](#14-query-filters) | `@With` / `@Without` to narrow an archetype match |
| 15 | [Testing](#15-testing) | Building a world in a unit test, asserting on components |
| 16 | [Cheat sheet](#16-cheat-sheet) | All system parameter types, one row each |

---

## 1. Setting up

japes needs **JDK 26** with `--enable-preview` (it uses the
`java.lang.classfile` API for its tier-1 code generator).

Add the core module to your Gradle build:

```kotlin
dependencies {
    implementation(project(":ecs-core"))
    // … or, once published, a Maven coordinate.
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
```

Your first `main`:

```java
package com.example;

import zzuegg.ecs.world.World;

public class Main {
    public static void main(String[] args) {
        var world = World.builder().build();
        System.out.println("world built: " + world.entityCount() + " entities");
    }
}
```

That's a valid japes program — an empty world with no systems, no
entities, no resources. Everything else in this tutorial is building
up from here.

## 2. Components

A **component** is any Java `record`. No superclass, no interface, no
annotations required on the record itself.

```java
public record Position(float x, float y, float z) {}
public record Velocity(float dx, float dy, float dz) {}
public record Health(int hp) {}
public record Name(String value) {}
```

Why records? Three reasons:

1. **Immutable by default.** Writes go through `Mut<C>.set(new C(...))`,
   which gives clean change-detection semantics — every mutation is a
   visible event.
2. **No inheritance means no archetype surprises.** Two records with
   the same component set live in the same archetype. Extending a
   common base class is a source of subtle bugs in other ECS
   libraries.
3. **Pattern matching and deconstruction work.** `var Position(var x,
   var y, var z) = pos;` in your system bodies, and `record`s play
   nicely with `toString()` / `equals()` / `hashCode()` for debugging.

Field types can be anything — primitives, references, nested records.
Primitives are the common case because they're what dense archetype
storage is good at.

**Tip:** keep components small and focused. An ECS reaches its best
performance when you can filter queries by just the components that
matter, so prefer `record Health(int hp) {}` over a god-struct
`GameCharacter`.

## 3. Spawning entities

```java
var world = World.builder().build();

var bob = world.spawn(
    new Position(0, 0, 0),
    new Velocity(1, 0, 0),
    new Name("Bob"));
```

`world.spawn(...)` takes any number of component records and:

1. Allocates a fresh `Entity` handle (an int index + generation).
2. Finds (or creates) the archetype whose component set exactly
   matches the passed components.
3. Appends the entity + components to the archetype's current chunk.
4. Returns the `Entity` handle so you can pass it to other APIs.

The returned `Entity` is stable across ticks and archetype moves —
it's a logical handle, not a direct pointer into storage. If the
entity gets moved to a different archetype (via
`world.addComponent(e, new Dead())` for example), the same handle
keeps working.

To despawn:

```java
world.despawn(bob);
```

Or to remove just one component:

```java
world.removeComponent(bob, Velocity.class);
```

Both are safe from outside the scheduler. From **inside** a running
system, use [`Commands`](#8-deferred-edits-with-commands) instead so
the mutation defers to the stage boundary.

## 4. Your first system

A **system** is a plain method annotated with `@System`. Parameters
describe what it reads, what it writes, and what services it wants:

```java
public class PhysicsSystems {

    @System
    void integrate(@Read Velocity v, @Write Mut<Position> p) {
        var cur = p.get();
        p.set(new Position(
            cur.x() + v.dx(),
            cur.y() + v.dy(),
            cur.z() + v.dz()));
    }
}
```

Then register the class with the world builder:

```java
var world = World.builder()
    .addSystem(PhysicsSystems.class)
    .build();

world.spawn(new Position(0, 0, 0), new Velocity(1, 2, 3));
world.tick();   // runs `integrate` on every matching entity
world.tick();
```

What the scheduler does behind the scenes:

- Inspects `PhysicsSystems.integrate` and sees: one `@Read Velocity`,
  one `@Write Mut<Position>`.
- Registers the system as requiring archetypes that contain **both**
  `Position` and `Velocity`.
- On each `world.tick()`, iterates the matching archetypes and calls
  `integrate(v, p)` once per entity.
- Under the tier-1 bytecode generator, this becomes a single hidden
  class with a tight per-chunk loop calling your `integrate` method
  via direct `invokevirtual` — no reflection, no method handles in
  the hot path.

**Read** and **Write** parameter shapes:

| Param | Meaning |
|---|---|
| `@Read C c` | Read-only view of component `C` for this entity. Can be used directly — no wrapper. |
| `@Write Mut<C> c` | Write-capable handle. Call `c.get()` to read, `c.set(new C(...))` to replace. The `set` is what fires change detection. |

You can mix them freely and in any order:

```java
@System
void mySystem(
    @Read Position p,
    @Read Velocity v,
    @Write Mut<Health> h
) { ... }
```

## 5. Resources

A **resource** is a singleton shared by every system in the world —
like `DeltaTime`, a `Random`, or a game configuration. Resources are
also just records (or plain classes) and come in two flavours:

```java
public record DeltaTime(float dt) {}
public record Gravity(float g) {}

public class IntegrateSystem {

    @System
    void integrate(
        @Read Velocity v,
        @Write Mut<Position> p,
        Res<DeltaTime> dt,          // read-only
        ResMut<Stats> stats         // read/write — see below
    ) {
        var d = dt.get().dt();
        var cur = p.get();
        p.set(new Position(
            cur.x() + v.dx() * d,
            cur.y() + v.dy() * d,
            cur.z() + v.dz() * d));
    }
}

var world = World.builder()
    .addResource(new DeltaTime(1.0f / 60))
    .addResource(new Stats(0, 0))
    .addSystem(IntegrateSystem.class)
    .build();
```

`Res<R>` gives you read-only access to the resource via `.get()`.
`ResMut<R>` also lets you replace the current value via `.set(new R(...))`
— useful for mutable aggregates like a per-tick stats record.

The scheduler uses the read/write distinction for parallelism: two
systems both taking `Res<DeltaTime>` are disjoint (both read-only);
one taking `ResMut<Stats>` and another taking `Res<Stats>` are not,
and the DAG builder will order them sequentially.

## 6. Change detection

Change detection is the single biggest ergonomic win japes gives you
over hand-rolled dirty-list bookkeeping. The library automatically
tracks which entities had which components mutated on which tick, and
exposes that as a query filter:

```java
public class HealthUI {

    @System
    @Filter(value = Changed.class, target = Health.class)
    void onHealthChanged(@Read Health h, Entity self) {
        System.out.println("Entity " + self + " has new HP: " + h.hp());
    }
}
```

The system runs **once per entity whose `Health` changed this tick**.
Not once per entity in the archetype. If 10 000 entities have
`Health` and only 100 had their HP mutated this tick, the observer
sees exactly 100 calls. Cost scales with the dirty count, not the
total.

What counts as a "change"? Any of:

- `world.setComponent(entity, new Health(...))` from outside a system
- `health.set(new Health(...))` where `health` is a `Mut<Health>`
  parameter inside a system
- A freshly-spawned entity (its components count as "added this tick")
- An entity that just moved archetype via `addComponent` — the new
  component is Added, the existing ones keep their tick stamps

There's a separate filter for Added-but-not-yet-Changed:

```java
@System
@Filter(value = Added.class, target = Player.class)
void welcomeNewPlayer(@Read Player p, Entity self) {
    // Runs once per entity, the first tick after Player was added.
}
```

And the parameter's type is just `@Read C` — the filter picks which
rows are visited, the read parameter is the value at those rows.

**Under the hood:** each archetype chunk keeps a `ChangeTracker` with
one tick-stamp per slot per tracked component, plus an (optional)
dense dirty-slot list. An observer with a `@Filter(Changed)`
annotation walks only the dirty-slot list, not the full chunk.
Tracking is opt-in per component: a component with no observer pays
zero overhead on mutation.

## 7. Removed components

`@Filter(Removed, target = C.class)` isn't a thing because by the
time the observer runs, the entity (or component) is gone — there's
nothing to read. Instead, removed components become a **subscription
you drain**:

```java
public class DeathTracker {

    @System
    void onDead(RemovedComponents<Health> dead, ResMut<Stats> stats) {
        long n = 0;
        for (var entry : dead) {
            Entity e = entry.entity();
            Health lastValue = entry.value();   // the Health just before it died
            // ... react to the death
            n++;
        }
        stats.set(new Stats(stats.get().deaths() + n, stats.get().alive()));
    }
}
```

`RemovedComponents<T>` is an iterable of `(Entity, T lastValue)`
pairs. You see every entity whose `Health` was removed — either
because the entity was despawned, or because someone called
`world.removeComponent(e, Health.class)`.

Each subscriber sees each event **exactly once**: after the system
runs, its watermark advances so the next tick it only sees new
events. Multiple systems can subscribe to `RemovedComponents<Health>`
independently.

## 8. Deferred edits with `Commands`

From inside a scheduled system, you can't call `world.despawn(e)` or
`world.addComponent(e, ...)` directly — those mutate the archetype
graph, and doing it mid-iteration would invalidate the very chunks
the scheduler is walking. Use a `Commands` parameter instead:

```java
public class Reaper {

    @System
    void reap(@Read Health h, Entity self, Commands cmds) {
        if (h.hp() <= 0) {
            cmds.despawn(self);
        }
    }
}
```

`Commands` buffers spawn / despawn / add / remove calls and flushes
them at the **stage boundary** (between `Update` and `PostUpdate`,
for example). The flush runs in a single-threaded pass so it can
safely touch the archetype graph.

`Commands` is independent per system — no contention between
parallel systems that both want to despawn entities. All per-system
buffers drain into the same flush at the end of the stage.

```java
// A spawner:
@System
void spawnProjectiles(@Read Gun g, Entity self, Commands cmds, Res<DeltaTime> dt) {
    if (g.cooldown() <= 0) {
        cmds.spawn(
            new Position(0, 0, 0),
            new Velocity(0, 0, 100),
            new Lifetime(60));
    }
}
```

## 9. The `Entity` param

Any system method can ask for the current entity handle by taking an
`Entity` parameter:

```java
@System
void debugPositions(@Read Position p, Entity self) {
    System.out.println(self + " is at " + p);
}
```

The scheduler fills in `self` with the entity handle of the currently-
iterated row. Useful for:

- Passing to `Commands.despawn(self)` (see above)
- Logging / diagnostics
- Looking up other components via `world.getComponent(self, Other.class)`
  — though if you need *multiple* components on the same entity, it's
  almost always cheaper to take another `@Read Other` parameter and
  let the scheduler do the archetype match.

## 10. Events

Sometimes you want to broadcast a "thing happened" message from one
system to another — not mutate a component, not touch the archetype
graph, just publish an event. That's what `EventWriter<E>` /
`EventReader<E>` are for:

```java
public record HealthZero(Entity who, int finalHp) {}

public class GameLogic {

    @System
    void damage(@Write Mut<Health> h, Entity self, EventWriter<HealthZero> out) {
        var cur = h.get();
        var next = new Health(cur.hp() - 1);
        h.set(next);
        if (next.hp() <= 0) {
            out.send(new HealthZero(self, next.hp()));
        }
    }
}

public class FxSystems {

    @System
    void playDeathSound(EventReader<HealthZero> deaths) {
        for (var e : deaths.read()) {
            System.out.println("💀 " + e.who());
        }
    }
}

// Register the event type with the world:
var world = World.builder()
    .addEvent(HealthZero.class)
    .addSystem(GameLogic.class)
    .addSystem(FxSystems.class)
    .build();
```

`EventWriter.send(e)` publishes an event to every subscriber;
`EventReader.read()` returns the unread events as an immutable
`List<E>`. Each subscriber has its own watermark so multiple readers
can consume the same event stream independently.

Events are for **one-shot notifications**. If you want persistent
state ("this entity is dead, and stays dead"), use a component
instead.

## 11. Local state

Some systems need per-system mutable state that isn't a component
and isn't a resource. A random number generator, a running average,
a cooldown counter. `Local<T>` is japes's answer:

```java
import java.util.Random;

public class Spawner {

    @System
    void spawnOccasionally(Local<Random> rng, Commands cmds) {
        if (rng.get() == null) {
            rng.set(new Random(42));      // first-call initialisation
        }
        if (rng.get().nextFloat() < 0.01f) {
            cmds.spawn(new Enemy(), new Position(0, 0, 0));
        }
    }
}
```

`Local<T>` is per-system — each system that takes a `Local<Random>`
gets its own independent `Local` instance, initialised to a
null-holding wrapper. Initialise it on first use via
`local.set(...)`, or wrap the lazy-init in a helper if you have lots
of locals. Future calls see the value you stored.

## 12. Stages and ordering

A world tick runs through **stages** in order: `First`, `PreUpdate`,
`Update`, `PostUpdate`, `Last`. Each stage is its own DAG of
systems.

By default a system goes into `Update`. To pick another stage:

```java
public class HealthUI {
    @System(stage = "PostUpdate")
    @Filter(value = Changed.class, target = Health.class)
    void updateUi(@Read Health h) { ... }
}
```

Within a stage, systems run in parallel if their component / resource
access is disjoint (the scheduler builds the DAG from the declared
access). If you need a specific ordering within a stage, use `after`
and `before`:

```java
@System(after = "MoveSystem")
void damageSystem(...) { ... }
```

References can be either fully-qualified (`"com.example.MoveSystem.move"`)
or just the method name if unambiguous.

**When to use stages vs. ordering:**

- Different stages = hard separation (every system in `Update`
  finishes before any system in `PostUpdate` starts). Use this when
  observers need to see the fully-applied state of their mutators
  — typically one stage per "phase" of your tick.
- `after` / `before` inside one stage = fine-grained ordering while
  still allowing parallelism with unrelated systems.

## 13. Multi-threading

Single-threaded is the default:

```java
var world = World.builder()
    .addSystem(PhysicsSystems.class)
    .build();
```

To opt into the DAG-parallel executor:

```java
import zzuegg.ecs.executor.Executors;

var world = World.builder()
    .executor(Executors.multiThreaded())   // uses ForkJoinPool
    .addSystem(MoveSystem.class)
    .addSystem(DamageSystem.class)
    .addSystem(RegenSystem.class)
    .build();
```

Within each stage the scheduler fans out ready systems across the
pool. A system is "ready" once every system it depends on has
finished. Two systems can run concurrently if and only if their
access sets are disjoint:

- `@Read C` on both → OK, run concurrently
- `@Write Mut<C>` on both → conflict, serialised
- `@Write Mut<A>` and `@Write Mut<B>` where A ≠ B → OK
- `ResMut<Stats>` on one and `Res<Stats>` on another → conflict

Parallelism is **free from declared access** — no user locking, no
ad-hoc synchronisation.

`Executors.multiThreaded()` uses a default-sized ForkJoinPool
(`Runtime.getRuntime().availableProcessors()`). You can pass your own
pool via `Executors.multiThreaded(pool)` or pick a fixed thread count
via `Executors.fixed(n)`.

**When NOT to use multi-threading:** when your tick is so small that
ForkJoinPool dispatch overhead exceeds the parallelism benefit. The
`RealisticTickBenchmark` in the DEEP_DIVE shows this: on a workload
where each observer sees only 100 entities, single-threaded wins.
Rule of thumb: if your tick is under ~20 µs, measure before
committing to multi-threaded.

## 14. Query filters

`@Read` / `@Write` select components. To narrow the archetype match
further without actually reading or writing a component, use `@With`
and `@Without` on the **method itself**:

```java
public record Enemy() {}
public record Friendly() {}

public class AiSystems {

    @System
    @With(Enemy.class)          // entity must have Enemy
    @Without(Friendly.class)    // entity must NOT have Friendly
    void hostileAi(@Read Position p, @Write Mut<Velocity> v) {
        // Only runs on entities that are Enemy AND NOT Friendly.
    }
}
```

Both `@With` and `@Without` are `@Repeatable`, so you can stack
multiple constraints:

```java
@System
@With(Enemy.class)
@With(AggroRange.class)
@Without(Dead.class)
@Without(Stunned.class)
void active(@Read Position p, @Write Mut<Target> t) { ... }
```

**Tip:** marker components (empty records) are how you tag groups of
entities without burning a field. `record Dead() {}` adds minimal
storage per entity but lets you write `@With(Dead.class)` in
observers and `@Without(Dead.class)` in hostile-AI systems.

## 15. Testing

A world is cheap to build in a `@Test`:

```java
@Test
void integrateMovesPosition() {
    var world = World.builder()
        .addResource(new DeltaTime(1f))
        .addSystem(PhysicsSystems.class)
        .build();

    var e = world.spawn(new Position(0, 0, 0), new Velocity(1, 2, 3));
    world.tick();

    var pos = world.getComponent(e, Position.class);
    assertEquals(new Position(1, 2, 3), pos);
}
```

`world.getComponent(entity, type)` is the test-friendly assertion
path. It's slower than the hot-path `@Read` parameter in a system
(goes through entity → archetype → chunk → storage), but fine for
test assertions.

Things worth testing:

1. **One tick's worth of mutation**: spawn, tick, read back, assert.
2. **Observer firing behaviour**: assert that a `@Filter(Changed)`
   system ran the expected number of times by having it bump a
   counter resource.
3. **Archetype moves**: assert that `world.addComponent(e, X.class)`
   preserves the entity's other components.
4. **Commands flush semantics**: spawn from inside a system, assert
   that the spawned entity is visible after `world.tick()` returns
   (it should — the stage-boundary flush ran).

`ecs-core/src/test/java/...` has many examples — `PipelineTest`,
`CommandProcessingTest`, `ChangeTrackingAcrossArchetypeMoveTest`
are good reference points.

## 16. Cheat sheet

Every system parameter type, one row each:

**System parameters** (on the method signature):

| Parameter | Lifetime | What it does |
|---|---|---|
| `@Read C c` | per-entity | Read-only view of component `C` |
| `@Write Mut<C> c` | per-entity | Read / write view of component `C`; `c.set(new C(...))` fires change detection |
| `Entity self` | per-entity | The currently-iterated entity handle |
| `Res<R> r` | per-system | Read-only access to the resource `R` |
| `ResMut<R> r` | per-system | Read/write access to the resource `R` |
| `Commands cmds` | per-system | Deferred spawn / despawn / add / remove buffer |
| `RemovedComponents<C> drain` | per-system | Iterable of `(Entity, lastValue)` for every `C` removed since last run |
| `EventReader<E> in` | per-system | Drain unread events of type `E` |
| `EventWriter<E> out` | per-system | Publish events of type `E` to every subscriber |
| `Local<T> state` | per-system | Per-system mutable state — each system gets its own |

**System-level annotations** (on the method itself):

| Annotation | Effect |
|---|---|
| `@System` | Marks the method as a system. Required. |
| `@System(stage = "…")` | Pin this system to a specific stage (`First` / `PreUpdate` / `Update` / `PostUpdate` / `Last` / custom) |
| `@System(after = "…")` | Must run after another system in the same stage |
| `@System(before = "…")` | Must run before another system in the same stage |
| `@With(C.class)` | Narrow archetype match: entity must have `C`. Repeatable. |
| `@Without(C.class)` | Narrow archetype match: entity must not have `C`. Repeatable. |
| `@Filter(value = Added.class, target = C.class)` | Visit only rows where `C` was added this tick |
| `@Filter(value = Changed.class, target = C.class)` | Visit only rows where `C` was written this tick |
| `@Exclusive` | Run exclusively — nothing else runs in parallel with this system |
| `@RunIf("condition")` | Skip the system unless a named boolean supplier returns true |

That's the whole API surface most users will ever touch. For the
scheduling internals, the tier-1 generator, or the change-tracker
implementation, see [DEEP_DIVE.md](DEEP_DIVE.md) and the source in
`ecs-core/src/main/java/zzuegg/ecs/`.
