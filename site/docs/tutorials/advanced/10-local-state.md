# Local system state

`Local<T>` is a mutable, per-system-parameter slot that lives across ticks. It gives a system a private scratch space that no other system can see — the ECS equivalent of a `static` variable, but attached to a single `@System` method and owned by the world.

## The `Local<T>` service parameter

Declare a `Local<T>` parameter anywhere among your system method's arguments. The framework allocates one on the first tick, stores it on the world, and passes the **same instance** back on every subsequent tick.

```java
import zzuegg.ecs.system.Local;

class PhysicsSystems {

    @System
    void integrate(@Write Mut<Position> p, @Read Velocity v, Local<Vec3> scratch) {
        var tmp = scratch.get();
        if (tmp == null) tmp = new Vec3();   // lazy init on tick 1

        tmp.set(v.dx(), v.dy(), v.dz());
        tmp.scale(deltaTime);

        var cur = p.get();
        p.set(new Position(cur.x() + tmp.x(), cur.y() + tmp.y(), cur.z() + tmp.z()));

        // tmp lives on the Local, so we don't store it back explicitly —
        // we mutated the object in place. For value types, call scratch.set(...).
    }
}
```

`Local` exposes two methods and nothing else:

```java
public final class Local<T> {
    public T get();
    public void set(T value);
}
```

No generics gymnastics, no type token — the parameter's declared type arg is a hint to *you*; the framework does not inspect it. You may put any object reference in there.

## How locals are keyed

Internally the world stores locals in a single map, keyed by a string derived from the system's qualified name and the zero-based parameter index:

```
key = "<ClassName>.<methodName>:<paramIndex>"
```

So `PhysicsSystems.integrate`'s third parameter resolves to the key `PhysicsSystems.integrate:2`. This has two consequences:

1. Two methods in different classes get independent locals even if their signatures are identical.
2. Two `Local<T>` parameters on the *same* method get independent locals — they are distinguished by position.

```java
@System
void counters(Local<int[]> ticks, Local<int[]> spawns) {
    // Two separate slots: PhysicsSystems.counters:0 and PhysicsSystems.counters:1.
    var t = ticks.get();  if (t == null) ticks.set(t = new int[1]);
    var s = spawns.get(); if (s == null) spawns.set(s = new int[1]);
    t[0]++;
    if (t[0] % 60 == 0) s[0]++;
}
```

!!! warning "Don't alias locals across systems"

    There is no public API to reach another system's `Local`. If two systems need to share mutable state, put it in a `Res`/`ResMut` — locals are *private*.

## Lazy initialization pattern

The first tick after the world is built, every `Local<T>` is `null`. The idiomatic pattern is a null-check at the top of the method:

```java
@System
void moveEnemies(@Write Mut<Position> p, Local<Random> rng) {
    var r = rng.get();
    if (r == null) {
        r = new Random(42L);
        rng.set(r);
    }
    // ... use r
}
```

Because the allocation happens exactly once per system, this is effectively free. If `T` is costly to construct (a large buffer, a compiled pattern, a pooled resource), lazy-init keeps the world-build path fast and ensures the cost is amortised only on systems that actually run.

## Use case: reusable scratch buffers

A system that builds a list or map every tick will hammer the allocator unless you reuse. `Local<ArrayList<...>>` is the cleanest fix because it survives the tick but is invisible to anything else.

```java
@System
void partitionEntities(@Read Position pos, Local<ArrayList<Entity>> hot, Entity self) {
    var list = hot.get();
    if (list == null) { list = new ArrayList<>(); hot.set(list); }
    else list.clear();  // reuse, don't reallocate

    if (Math.abs(pos.x()) > 1000f) list.add(self);
    // ... consume list before the method returns
}
```

!!! tip "Clear, don't shrink"

    `ArrayList.clear()` retains the backing array, so the next tick reuses the same allocation. Over a few ticks the list size stabilises at the high-water mark and the scratch buffer becomes zero-allocation in steady state.

## Use case: tick-over-tick hysteresis

Games often need to detect *transitions* — "health dropped below 20% this tick". That requires comparing this tick's value against last tick's, which is exactly what a `Local` is for.

```java
@System
void lowHealthAlerts(@Read Health hp, Local<Float> previousHp, EventWriter<LowHealth> out) {
    var prev = previousHp.get();
    float now = hp.hp();

    if (prev != null && prev >= 20f && now < 20f) {
        out.send(new LowHealth(now));
    }
    previousHp.set(now);
}
```

The alert fires exactly once — the frame we crossed the threshold. No extra component, no resource, no component in the main store tracking "was-low-last-frame".

## Use case: per-system counters and throttles

Run-condition style gates can also be built with a local.

```java
@System
void periodicCleanup(Local<int[]> counter, Commands commands) {
    var c = counter.get();
    if (c == null) { c = new int[1]; counter.set(c); }
    c[0]++;
    if (c[0] % 600 == 0) {   // every 10 seconds at 60 Hz
        commands.insertResource(new NeedsGc());
    }
}
```

This is cheaper than a full `@RunIf` because the world doesn't have to invoke a run-condition callback — the system runs every tick and exits early on its own counter.

!!! tip "Prefer primitives in a one-element array"

    `Local<int[]>` is the fastest counter you can build because the array is allocated once, the `int[0]` read compiles down to a single load, and there is no autoboxing. For a counter that needs to survive the tick, this is strictly better than `Local<Integer>`.

## Thread-safety

`Local<T>` itself has no synchronization. The framework gives each system its own locals, and the scheduler never runs the same system instance twice in parallel within a single stage — so one system's local is always single-threaded. If your `T` is mutable (an `ArrayList`, a cached `byte[]`, a `Random`), it is safe by construction; if your system is one of several parallel instances (not currently supported — each `@System` method is one logical node), you'd need to synchronise yourself.

!!! warning "Locals persist across a `World.rebuildSchedule()`"

    When a new system is added at runtime, the schedule is re-parsed but the `locals` map on the world is **not** cleared. If you rename a system method, the old local key becomes orphaned and the new key starts from scratch — there is no migration.

## What's next

- [Stages and ordering](11-stages-and-ordering.md) — how systems compose into a deterministic schedule.
- Related basics: [Resources](../basics/05-resources.md), [Commands](../basics/08-commands.md).
