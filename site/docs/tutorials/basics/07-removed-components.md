# Removed Components

When a component disappears — because the entity was despawned, or because
somebody called `removeComponent` — there is no longer any slot to iterate
over. The component is *gone*. A query cannot find it because the entity
no longer carries it. japes solves this with a dedicated system parameter:
`RemovedComponents<T>`.

!!! note "You get the last value back"
    Because every component is a `record`, and records are immutable, the
    runtime safely retains a reference to the component's value at the
    exact moment of removal. When you iterate `RemovedComponents<Health>`,
    each entry gives you the entity id **and** the `Health` record as it
    looked the instant before it was taken away.

## The service parameter

Declare a parameter of type `RemovedComponents<T>` where `T` is the
component record you want to observe. The scheduler fills it once per
system invocation with a view over every removal the system has not yet
seen:

```java
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.RemovedComponents;

public class DeathWatcher {
    @System
    void watch(RemovedComponents<Health> removed) {
        for (var r : removed) {
            var entity = r.entity();
            var lastValue = r.value();
            java.lang.System.out.println(
                entity + " lost Health (was " + lastValue.current()
                    + "/" + lastValue.max() + ")");
        }
    }
}
```

`RemovedComponents<T>` extends `Iterable<Removal<T>>`, so enhanced-for works.
If you want an eager `List`, call `asList()`. To check whether there is
anything to do, use `isEmpty()`:

```java
@System
void reportIfAny(RemovedComponents<Health> removed) {
    if (!removed.isEmpty()) {
        reporter.record(removed.asList());
    }
}
```

Each element is a `RemovedComponents.Removal<T>` record with two fields:

```java
public record Removal<T extends Record>(Entity entity, T value) {}
```

## Which removals does it track?

The tracker covers **every path that drops a component**:

- `world.removeComponent(entity, X.class)` — explicit removal
- `world.despawn(entity)` — every component the entity had appears in the
  corresponding `RemovedComponents` log
- `commands.remove(entity, X.class)` / `commands.despawn(entity)` —
  enqueued from inside a parallel system and flushed at the end of the
  stage

In each case the world's `RemovalLog` stores the pair `(entity, lastValue)`
tagged with the tick at which the removal happened.

## Per-consumer watermarks

The removal log is **shared** across every system that observes the same
component type. Each system gets its own independent cursor — called a
watermark — so:

- Two systems that both read `RemovedComponents<Health>` each see every
  removal exactly once.
- A system that runs every frame sees removals up to the previous frame,
  then advances.
- A system gated behind a run condition (only runs every 10 frames) picks
  up every removal since *its* last run — nothing gets silently dropped.

The watermark advances when the system returns from its body. Concretely,
each iteration of `RemovedComponents<T>` calls `log.snapshot(targetId,
plan.lastSeenTick())` to pull the window newer than the system's
`lastSeenTick`. After the system body returns, the plan records the current
tick and the next call sees a fresh, shifted window.

!!! tip "Iterating twice inside one system call is fine"
    The iterator produced by `RemovedComponents<T>` is a single-pass
    snapshot of the window — you can iterate it twice in one invocation if
    you like (e.g. once to count, once to react), both loops see the same
    data. The watermark only advances between system *invocations*.

## The removal log garbage-collects itself

`RemovalLog` is append-only during a tick. At the end of each stage, the
world runs a cleanup pass that computes the minimum watermark across every
plan that consumed a given component type, and drops log entries that are
`<=` that watermark. If you have a `RemovedComponents<Health>` reader that
always runs every frame, the `Health` removal log stays short no matter how
many millions of removals happen — each frame's removals are consumed and
discarded.

Systems that never declare a `RemovedComponents<X>` parameter do not open
a "slot" in the log at all. If nobody observes `Position` removals, the
removal log for `Position` is never populated to begin with — the tracker
notices there are zero consumers and `ChangeTracker.fullyUntracked` skips
the bookkeeping entirely.

## Why not `@Filter(Removed.class)`?

`@Filter(value = Removed.class, target = X.class)` exists in the API. It
reads naturally — "run this system on entities that lost their X" — and it
works. But it is not the normal path, for two reasons:

1. **There is no slot to iterate.** The fast tier-1 sparse iteration loop
   visits a *chunk* and then a *slot* inside that chunk; a removed
   component has neither. `@Filter(Removed)` therefore drops out of the
   generated tier-1 path and runs on a slower tier-2 fallback.
2. **You cannot read other components of that entity.** The entity is
   gone — its `Position`, `Velocity`, everything else has been cleaned up.
   So the useful shape is always "give me the (entity, lastValue) pair",
   which is exactly what `RemovedComponents<T>` already returns.

As a rule of thumb: if you want to react to a removal, declare
`RemovedComponents<T>`. Save `@Filter(Removed)` for the rare case where you
want the system to run at all only when a removal happened.

## Worked example: reacting to lost health

Let's tie it all together. Suppose the gameplay rule is "when an entity
loses its `Health` component (for any reason), play a death sound and
increment a kill counter". No component, no entity — just a reaction.

```java
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.RemovedComponents;

public record KillCount(int total) {}

public class DeathFx {

    @System
    void onDeath(RemovedComponents<Health> removed, ResMut<KillCount> kills) {
        if (removed.isEmpty()) return;

        int count = 0;
        for (var r : removed) {
            audio.play("death.ogg");
            logger.info(r.entity() + " died at HP "
                + r.value().current() + "/" + r.value().max());
            count++;
        }
        kills.set(new KillCount(kills.get().total() + count));
    }
}
```

Wire it up normally:

```java
var world = World.builder()
    .addResource(new KillCount(0))
    .addSystem(DeathFx.class)
    .build();
```

Now every source of removal — `world.despawn`, `world.removeComponent`,
`commands.remove`, `commands.despawn` — flows into `DeathFx.onDeath` with
the last-known `Health` value. The watermark advances automatically when
the method returns, so you never double-count.

## Takeaways

- Use `RemovedComponents<T>` to react to component removals. It is the
  idiomatic, fast path.
- Each consumer has its own watermark — every observer sees every removal
  exactly once.
- Records are immutable, so the last value is safely retained until every
  observer has processed it, and the log GCs itself at end-of-stage.
- `@Filter(Removed.class)` exists but drops to tier-2 and is the exception,
  not the rule.

## What's next

So far every mutation you have seen happens immediately on the world from
inside the system body. That is fine for single-threaded tests, but it is
not safe if the scheduler runs your systems in parallel. The `Commands`
service parameter lets a system *enqueue* structural changes that apply at
the next stage boundary — next chapter.

Continue to **[Commands](08-commands.md)**.
