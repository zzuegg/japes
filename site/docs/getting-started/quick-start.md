---
title: Quick start
---

# Quick start

A complete japes program in under 40 lines. Runnable as-is once you've followed [Installation](installation.md).

```java title="src/main/java/com/example/Main.java"
package com.example;

import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

public class Main {

    // (1) Components are plain records.
    public record Position(float x, float y) {}
    public record Velocity(float dx, float dy) {}

    // (2) Systems are annotated methods on any class.
    public static class Physics {
        @System
        public void integrate(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
        }
    }

    public static void main(String[] args) {
        // (3) Build a world, register the system class.
        var world = World.builder()
            .addSystem(Physics.class)
            .build();

        // (4) Spawn a couple of entities.
        var e1 = world.spawn(new Position(0f, 0f), new Velocity(1f, 0f));
        var e2 = world.spawn(new Position(5f, 5f), new Velocity(0f, 2f));

        // (5) Drive the world forward one tick at a time.
        for (int i = 0; i < 10; i++) {
            world.tick();
        }

        // (6) Read back the final state.
        java.lang.System.out.println(world.getComponent(e1, Position.class));
        java.lang.System.out.println(world.getComponent(e2, Position.class));
    }
}
```

Output:

```
Position[x=10.0, y=0.0]
Position[x=5.0, y=20.0]
```

## What just happened

1. **Components are records.** Nothing else — no base class, no interface, no annotation on the record itself. The record's shape is the schema; archetype keys are derived from the set of component classes on each entity.
2. **Systems are annotated methods.** `@System` marks a method as a system; the per-parameter annotations (`@Read`, `@Write`) tell the scheduler exactly what the method reads and writes. That access set is what the DAG builder uses to figure out which systems can run in parallel without locking.
3. **`World.builder()` + `addSystem(Physics.class)`** parses the class's annotated methods and registers them. The scheduler then generates a tier-1 hidden class per system: your `integrate` method becomes a direct `invokevirtual` target from a per-chunk loop the JIT can inline.
4. **`world.spawn(Record...)`** creates an entity with the given component values. The varargs signature determines the entity's archetype — two entities with `{Position, Velocity}` share the same archetype and live in the same chunk.
5. **`world.tick()`** runs one scheduler pass: every registered system executes in its stage order; disjoint-access systems may run in parallel if you wire up a [multi-threaded executor](../tutorials/advanced/12-multi-threading.md); Commands buffers flush at stage boundaries.
6. **`world.getComponent(entity, Class)`** is the "query from outside a system" escape hatch — fine for tests and debugging, but inside a system you almost always want `@Read` / `@Write` for the tier-1 fast path.

## Running it

```bash
./gradlew run
```

Or, since the example uses `main`, directly:

```bash
java --enable-preview -cp build/classes/java/main com.example.Main
```

## What's next

<div class="japes-card-grid" markdown>

<div class="japes-card" markdown>
### :material-cube-outline: Components in depth
What types can be fields, why records, marker components.
[**Read &rarr;**](../tutorials/basics/01-components.md)
</div>

<div class="japes-card" markdown>
### :material-magnify: Queries
`@Read`, `@Write`, `Mut<C>`, `Entity`, `@With` / `@Without` archetype narrowing.
[**Read &rarr;**](../tutorials/basics/04-queries.md)
</div>

<div class="japes-card" markdown>
### :material-eye-outline: Change detection
`@Filter(Changed.class)` observers, `RemovedComponents<T>`.
[**Read &rarr;**](../tutorials/basics/06-change-detection.md)
</div>

<div class="japes-card" markdown>
### :material-vector-link: Relations
`@Pair`, `@ForEachPair`, `@FromTarget` — japes's flagship feature.
[**Read &rarr;**](../tutorials/relations/17-overview.md)
</div>

</div>
