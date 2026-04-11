# Run conditions

A run condition is a named boolean check. Tag a method with `@RunCondition`, reference it from `@RunIf("name")` on any `@System`, and the scheduler will skip that system on ticks where the condition returns `false`. Unlike `@Where`, this gates the *entire system*, not individual rows — and it costs nothing if the condition is cheap.

## The annotations

Two annotations are involved. Both live in `zzuegg.ecs.system`.

```java
@Target(ElementType.METHOD) @Retention(RUNTIME)
public @interface RunCondition {
    String value() default "";      // optional name override; default = method name
}

@Target(ElementType.METHOD) @Retention(RUNTIME)
public @interface RunIf {
    String value();                 // required — names the condition to evaluate
}
```

- `@RunCondition` marks a **no-argument `boolean` method** as a named gate.
- `@RunIf("name")` sits on an `@System` method and requests that the gate be evaluated before the system runs.

## Declaring a condition

Put the condition method on any system class you register with the world. It can be an instance method (the framework creates a single instance via the class's no-arg constructor) or `static`.

```java
import zzuegg.ecs.system.RunCondition;
import zzuegg.ecs.system.RunIf;

class DebugSystems {

    @RunCondition
    boolean debugEnabled() {
        return System.getProperty("game.debug") != null;
    }

    @System
    @RunIf("debugEnabled")
    void dumpFps(Res<FrameStats> stats) {
        System.out.println("fps = " + stats.get().fps());
    }
}
```

When the world is built, it scans every registered system class for methods annotated with `@RunCondition`, creates an instance of the class if needed, and registers a `BooleanSupplier` under the condition's name.

!!! warning "`@RunCondition` must be explicitly applied"

    An older revision of the framework auto-registered any no-arg boolean method. That is no longer the case — you must annotate the method. Helper getters like `isInitialized()` are left alone, and you can't accidentally shadow a real condition with a namesake helper.

The name the framework registers is:

1. The explicit `value()` on the annotation, if non-empty.
2. Otherwise, the method name.

```java
@RunCondition("debug-mode")        // referred to as "debug-mode"
boolean checkDebug() { return ... }

@RunCondition                      // referred to as "checkDebug"
boolean checkDebug() { return ... }
```

## Referencing a condition

`@RunIf("name")` on an `@System` method binds the system to a condition. The string must match a registered name — mismatched names are silently ignored (the system runs unconditionally), so pay attention to typos.

```java
@System
@RunIf("debugEnabled")
void logEntityCount(World world) { ... }
```

At tick time, just before the scheduler invokes the system, the world looks up the condition and calls it:

```java
// simplified from World.executeSystem
if (desc.runIf() != null) {
    var condition = runConditions.get(desc.runIf());
    if (condition != null && !condition.getAsBoolean()) {
        return;                             // skip this system this tick
    }
}
```

Skipped systems still count for the DAG — dependencies still resolve, and parallelism is unaffected. The skip is purely "do not invoke the user method".

## Use case: gate a debug system on a flag

The canonical example. A debug overlay system runs every tick in dev builds, never in release.

```java
class Debug {

    @RunCondition
    boolean showOverlay() {
        return Boolean.getBoolean("game.overlay");
    }

    @System(stage = "Last")
    @RunIf("showOverlay")
    void drawOverlay(Res<FrameStats> stats, ResMut<Canvas> canvas) {
        canvas.get().drawText(10, 10, "fps = " + stats.get().fps());
    }
}
```

No `#ifdef`, no build-time branching — the release build ships the system, and the condition method simply returns `false`.

## Use case: gate on a resource

Conditions are plain Java, so they can read anything accessible from the class. A common pattern is gating on a resource toggle.

```java
public record GameMode(boolean paused, boolean debug) {}

class ModeGates {
    // The condition needs access to the world to read resources, which
    // @RunCondition doesn't give it directly. Hold a reference to the
    // GameMode via a static field, a singleton, or a plain field that
    // some setup system writes.
    static volatile GameMode mode = new GameMode(false, false);

    @RunCondition boolean notPaused() { return !mode.paused(); }
    @RunCondition boolean isDebug()   { return  mode.debug();  }
}

class Gameplay {
    @System @RunIf("notPaused")
    void tickEnemies(@Write Mut<Position> p, @Read Velocity v) { ... }
}
```

The condition method has no service-parameter injection — it's a plain method invoked via reflection. If you need resource access inside a condition, either hold a `volatile` reference updated by a regular system, or flip the dependency: have an `@Exclusive` system in `First` read the resource and write a boolean into a field the conditions consult.

!!! tip "Keep condition methods trivial"

    Conditions are called once per system, per tick — a handful of times per frame is typical. A boolean field read is ideal; a hash-map lookup is fine; anything involving I/O or heavy computation becomes a per-tick cost that the user didn't ask for.

## Use case: cadence gates

Run-conditions make it easy to run a system on a cadence without putting counters inside the system itself.

```java
class Cadences {
    private long lastTick;

    @RunCondition
    boolean everyTenTicks() {
        var now = java.lang.System.nanoTime();
        if (now - lastTick > 10_000_000) { lastTick = now; return true; }
        return false;
    }
}

class Stats {
    @System @RunIf("everyTenTicks")
    void reportStats(Res<FrameStats> stats) { ... }
}
```

Compared with a `Local<int[]>` counter inside the system, a run-condition keeps the cadence logic in one place and reusable — two systems can share `@RunIf("everyTenTicks")` without duplicating the counter.

## Behavior under parallel execution

When the multi-threaded executor runs a wave of systems in parallel, each worker independently evaluates its own `@RunIf`. Conditions must be thread-safe: two parallel systems gated on the same condition will call the boolean supplier from two threads at once. Pure reads of `volatile` fields are fine; stateful conditions that increment a counter need synchronisation, or — cleaner — move the state into the systems themselves via `Local`.

## Interaction with `RemovedComponents` GC

Systems skipped by a `@RunIf` hold a *stale* watermark for any `RemovedComponents<T>` they consume. The world's end-of-tick garbage collection of the removal log walks every plan and keeps the minimum `lastSeenTick()` across plans that consume the component. A system that is often disabled can keep removal-log entries around longer than expected; when it finally runs, it observes every removal that happened while it was asleep, up to the point its watermark advances.

!!! warning "A permanently-disabled system pins removal logs"

    If a `@RunIf` condition returns `false` forever, the world will still keep removal-log entries alive for every component type that system consumes — they can never be garbage-collected below that plan's watermark. Disable systems by removing them from the world build, not by letting a run condition always return `false`.

## Troubleshooting

- **System never runs.** Double-check the condition name in `@RunIf` matches the `@RunCondition` name. Typos are not errors — the lookup returns `null` and the system runs unconditionally, so the bug is *worse* than silent.
- **Condition throws.** The framework catches exceptions from the condition and treats them as `false`. The system is skipped and the exception is swallowed — log inside the condition if you suspect this.
- **Condition not found when it should be.** Did you add the class containing the `@RunCondition` to the world via `addSystem`? Conditions are registered only for classes actually registered.

## What's next

You've reached the end of the Advanced track. From here:

- **Relations** — read the Relations section for `@ForEachPair`, `PairReader`, target/source iteration, and the Flecs-style relation DAG.
- **Reference** — the [tier fallbacks](../../reference/tier-fallbacks.md) page explains why a system ran on tier-2 instead of tier-1.
- Related basics: [Resources](../basics/05-resources.md), [Commands](../basics/08-commands.md).
