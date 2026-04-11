# Worked Example: Predator and Prey

> A complete relations walkthrough assembling everything from
> this section — payload record, cleanup policy, `Commands`
> acquisition, `@ForEachPair` pursuit, an `@Exclusive` catch
> scan via `forEachPairLong`, and a scoring observer using
> `RemovedRelations<Hunting>`. The same shape ships as
> `PredatorPreyForEachPairBenchmark` in the benchmark suite.

## The scenario

A fixed population of predators and prey roam a 2D arena.

- **Prey** wander. (In this example they start with zero velocity,
  but the rules are the same if they do.)
- **Predators** without a current hunt pick a random prey and
  commit to it via a `Hunting` relation.
- **Predators** with a current hunt steer toward the prey every
  tick.
- If a predator gets within `catchDistance` of its prey, the prey
  is despawned. `Hunting` is `RELEASE_TARGET`, so the predator
  survives the catch and can acquire a new hunt next tick.
- A **scoring** system reacts to each dropped `Hunting` pair by
  bumping a counter.
- A **respawn** system keeps prey at a baseline population.

Every moving piece in this scenario was introduced earlier in the
section. Here it comes together.

## The records

```java
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;

public record Position(float x, float y) {}
public record Velocity(float dx, float dy) {}
public record Predator(int speed) {}
public record Prey(int alert) {}

// Default policy is RELEASE_TARGET — spelled out here for clarity.
@Relation(onTargetDespawn = CleanupPolicy.RELEASE_TARGET)
public record Hunting(int ticksLeft) {}
```

Plus three resources:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public static final class PreyRoster {
    public final List<Entity> alive = new ArrayList<>();
}

public static final class Config {
    public float catchDistance = 0.5f;
    public float arenaSize = 20.0f;
    public Random rng = new Random(1234);
}

public static final class Counters {
    public long pursuitCalls;
    public long catches;
}
```

`PreyRoster` is a simple live-set so the acquisition system can
pick a target in `O(1)`. `Config` carries tunables. `Counters` is
the sink for pursuit calls and catches; the scoring system bumps
`catches` every time a `Hunting` pair is released.

## System 1: movement

Ordinary `@System`, no relations. Apply velocity to position.

```java
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.Write;

@System
public void movement(@Read Velocity v, @Write Mut<Position> p) {
    var cur = p.get();
    p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
}
```

## System 2: acquire a hunt

Every predator without an active `Hunting` pair picks a random
prey from the roster and commits to it. The `@Without(Hunting.class)`
filter narrows the archetype match to predators that don't
currently carry the source marker, so predators that already have
a hunt skip dispatch entirely.

```java
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.Without;
import zzuegg.ecs.world.World;

@System(after = "movement")
@Without(Hunting.class)
public void acquireHunt(
        @Read Predator pred,
        Entity self,
        Res<PreyRoster> roster,
        Res<Config> config,
        World world,
        Commands cmds
) {
    var preyList = roster.get().alive;
    if (preyList.isEmpty()) return;
    var target = preyList.get(config.get().rng.nextInt(preyList.size()));
    if (!world.isAlive(target)) return;
    cmds.setRelation(self, target, new Hunting(3));
}
```

Key points:

- The relation is created through `Commands.setRelation`. Every
  other system running in the current stage is already iterating
  the pair store; a direct `world.setRelation` would be unsafe.
  `Commands` defers the write to the next stage boundary.
- `@Without(Hunting.class)` references the **source marker**
  indirectly via the relation class, so the filter reflects the
  pair-carrier set accurately. Once this system commits a
  `Hunting`, the predator gains the source marker at flush time
  and drops out of the `acquireHunt` archetype match.

## System 3: per-pair pursuit with `@ForEachPair`

The hot loop. One call per live `Hunting` pair. Source-side
`Position` + `Velocity`, target-side `Position`, and the
`Hunting` payload itself.

```java
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.ForEachPair;
import zzuegg.ecs.system.FromTarget;

@System
@ForEachPair(Hunting.class)
public void pursuit(
        @Read Position sourcePos,
        @Write Mut<Velocity> sourceVel,
        @FromTarget @Read Position targetPos,
        Hunting hunting,
        ResMut<Counters> counters
) {
    float dx = targetPos.x() - sourcePos.x();
    float dy = targetPos.y() - sourcePos.y();
    float mag = (float) Math.sqrt(dx * dx + dy * dy);
    if (mag > 1e-4f) {
        sourceVel.set(new Velocity(dx / mag * 0.1f, dy / mag * 0.1f));
    }
    counters.get().pursuitCalls++;
}
```

This is the exact shape the tier-1 generator can emit a hidden
class for: 1 source `@Read`, 1 source `@Write`, 1 target `@Read`,
1 payload, 1 service parameter. The generated runner walks the
forward-index arrays directly, calls `Mut.setContext` once per
source (not once per pair), and invokes `pursuit` through
`invokevirtual`. No walker, no `PairReader`, no reflection.

!!! warning "No `@FromTarget @Write`"
    `sourceVel` is a source-side `Mut<Velocity>`, which is fine.
    You can't ask for `@FromTarget @Write Mut<Velocity>` — two
    predators may legitimately share the same prey, and the
    framework rejects the ambiguous write at parse time. If you
    need to apply a target-side effect, emit a `Commands.add(...)`
    and let a separate system apply it.

## System 4: exclusive catch scan

This is where the per-pair model shows its limits. A catch
resolution has to:

1. iterate every live `Hunting` pair,
2. look up **both** entities' positions,
3. compare distance,
4. stash the catches somewhere without mutating the store mid-walk,
5. despawn the prey **after** the scan finishes.

`@Exclusive` turns off parallel dispatch and gives the system
sole access to the world for the duration of its body. Inside,
`store.forEachPairLong` is the raw-long iterator — it hands the
packed entity ids straight to the consumer without allocating an
`Entity` per pair.

```java
import zzuegg.ecs.component.ComponentReader;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.util.LongArrayList;

// Reusable catch-id buffer. One per-system instance is fine.
private final LongArrayList caughtBuffer = new LongArrayList(32);

@System(stage = "PostUpdate")
@Exclusive
public void resolveCatches(
        World world,
        ResMut<PreyRoster> roster,
        Res<Config> config,
        ComponentReader<Position> posReader,
        ResMut<Counters> counters
) {
    var store = world.componentRegistry().relationStore(Hunting.class);
    if (store == null) return;
    float catchDistSq = config.get().catchDistance * config.get().catchDistance;

    caughtBuffer.clear();

    // Raw-long bulk walk over every (predator, prey, Hunting) triple.
    // Zero Entity allocation in the hot loop. Mutations MUST be
    // deferred to after the walk.
    store.forEachPairLong((predatorId, preyId, val) -> {
        var predPos = posReader.getById(predatorId);
        var preyPos = posReader.getById(preyId);
        if (predPos == null || preyPos == null) return;
        float dx = predPos.x() - preyPos.x();
        float dy = predPos.y() - preyPos.y();
        if (dx * dx + dy * dy <= catchDistSq) {
            caughtBuffer.add(preyId);
        }
    });

    int caughtCount = caughtBuffer.size();
    if (caughtCount == 0) return;

    var alive = roster.get().alive;
    var caughtRaw = caughtBuffer.rawArray();
    for (int i = 0; i < caughtCount; i++) {
        var prey = new Entity(caughtRaw[i]);
        if (world.isAlive(prey)) {
            world.despawn(prey);
            alive.remove(prey);
            counters.get().catches++;
        }
    }
}
```

What's happening at the despawn call:

1. `world.despawn(prey)` enters `despawnWithCascade`.
2. The cleanup loop walks every registered relation store.
3. For `Hunting`, the prey is the **target** of one or more
   pairs. The policy is `RELEASE_TARGET`, so each pair is dropped
   (tracker updated, `PairRemovalLog` appended with tick and
   `lastValue`). Each predator that lost its last `Hunting` pair
   has its source marker cleared, which drops it out of the
   pursuit filter and back into the `acquireHunt` filter next
   stage.
4. The prey's own archetype row is freed. Its incoming-pair
   target marker is freed along with it.
5. On the next tick, scoring reads the drained
   `RemovedRelations<Hunting>` and bumps a counter.

!!! warning "Don't mutate during a `forEachPair` walk"
    The `forEachPairLong` callback runs over the live forward
    map. Calling `world.despawn` or `world.removeRelation` from
    inside the lambda would mutate the map the walk is reading.
    Always defer mutations into a local list (like the
    `LongArrayList` above) and apply them after the walk
    returns.

## System 5: scoring via `RemovedRelations<Hunting>`

Every dropped `Hunting` pair — whether the prey was caught, the
pair was manually removed, or a `Commands.removeRelation` flushed
— feeds the per-type `PairRemovalLog`. The scoring system drains
the log on its own tick.

```java
import zzuegg.ecs.relation.RemovedRelations;

@System(stage = "PostUpdate", after = "resolveCatches")
public void scoreHunts(
        RemovedRelations<Hunting> dropped,
        ResMut<Counters> counters
) {
    if (dropped.isEmpty()) return;
    for (var event : dropped) {
        // event.source()    — predator (may still be alive)
        // event.target()    — prey (usually dead at this point)
        // event.lastValue() — Hunting record as of the drop
        counters.get().catches++;
    }
}
```

Running `scoreHunts` with `after = "resolveCatches"` in
`PostUpdate` guarantees it sees the drops from this tick's
catches. Note that we're now double-counting — `resolveCatches`
already bumped `catches` — so in a real game you'd pick one or
the other. The benchmark keeps both counters separate
(`pursuitCalls` and `catches`) for observability.

## System 6: respawn prey

Keep the population at `BASELINE_PREY_COUNT`. `@Exclusive`
because the body spawns new entities; exclusive execution is the
simplest way to make the spawn count deterministic across threads.

```java
public static volatile int BASELINE_PREY_COUNT;

@System(stage = "PostUpdate", after = "resolveCatches")
@Exclusive
public void respawnPrey(
        World world,
        ResMut<PreyRoster> roster,
        Res<Config> config
) {
    while (roster.get().alive.size() < BASELINE_PREY_COUNT) {
        float x = config.get().rng.nextFloat() * config.get().arenaSize;
        float y = config.get().rng.nextFloat() * config.get().arenaSize;
        var p = world.spawn(
                new Position(x, y),
                new Velocity(0f, 0f),
                new Prey(0)
        );
        roster.get().alive.add(p);
    }
}
```

## Wiring the world

```java
World world = World.builder()
        .addResource(new PreyRoster())
        .addResource(new Config())
        .addResource(new Counters())
        .addSystem(Systems.class)  // the enclosing class holding all six @System methods
        .build();

// Seed predators and prey.
var rng = new Random(7);
for (int i = 0; i < 500; i++) {
    world.spawn(
            new Position(rng.nextFloat() * 2f, rng.nextFloat() * 2f),
            new Velocity(0.05f, 0.05f),
            new Predator(1)
    );
}
var roster = /* resolve roster from world */;
for (int i = 0; i < 2000; i++) {
    var prey = world.spawn(
            new Position(/* ... */),
            new Velocity(0f, 0f),
            new Prey(0)
    );
    roster.alive.add(prey);
}

for (int i = 0; i < 1000; i++) world.tick();
```

## Stage ordering recap

Default stage (`Update`):

1. `movement` — physics integration, no relations touched.
2. `acquireHunt` — predators without a hunt pick one via
   `Commands.setRelation`.
3. `pursuit` — `@ForEachPair(Hunting.class)` body runs once per
   pair. The tier-1 runner is in charge.

Command buffer flush between stages. New `Hunting` pairs become
visible.

`PostUpdate`:

4. `resolveCatches` — exclusive distance check, catches despawned
   immediately (safe — we're exclusive).
5. `respawnPrey` — top up the prey population.
6. `scoreHunts` — observer drains the tick's `Hunting` removals.

## How the pieces fit together

!!! tip "Picking the right tool per system"
    This scenario uses all three dispatch styles for a reason:

    - **`@ForEachPair`** for `pursuit` because every pair does
      identical work and the tier-1 runner makes the hot loop
      essentially a straight-line store walk.
    - **`@Exclusive` + `forEachPairLong`** for `resolveCatches`
      because the body needs to look up arbitrary components
      (`ComponentReader<Position>.getById`) and has to mutate
      the world (despawns) after the walk finishes.
    - **`RemovedRelations`** for `scoreHunts` because it runs
      once per dropped pair, it doesn't care about the source or
      target components at all, and it parallels the
      `RemovedComponents` pattern from plain ECS.

    If you wrote `pursuit` as a `@Pair` + `PairReader` system,
    it would still work — but you'd pay the walker allocation
    and the `reader.fromSource` call per pair, and the tier-1
    path wouldn't apply.

## Benchmark reference

The same six systems (minus some minor accounting) are
implemented in
`benchmark/ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/scenario/PredatorPreyForEachPairBenchmark.java`.
That file is the canonical reference for current API shape and
current throughput numbers; compare against
`PredatorPreyBenchmark` in the same package for the
`@Pair` + `PairReader` variant.

For the full numbers on current hardware see the
[predator-prey benchmark page](../../benchmarks/predator-prey.md).

## What you learned

!!! summary "Recap"
    - A complete relation workflow pulls together the payload
      record, a cleanup policy, command-driven acquisition, a
      tier-1 per-pair system, an exclusive raw-long scan, and a
      `RemovedRelations<T>` observer.
    - `Commands.setRelation` is the safe way to create pairs
      from inside a system body.
    - `store.forEachPairLong` is the fastest full-scan primitive
      for systems that need arbitrary entity lookups during
      iteration; mutations must be deferred until after the
      walk.
    - `RELEASE_TARGET` is the right policy whenever the source
      survives its target dying — which is most gameplay
      relations.

## What's next

!!! tip "You've finished the Relations section"
    Next up, pick whichever deep-dive fits your project:

    - [Change tracking deep dive](../../deep-dive/change-tracking.md) — the
      `PairChangeTracker` that drives `@Added` / `@Changed`
      for pair payloads.
    - [Predator / prey benchmark](../../benchmarks/predator-prey.md) — real
      numbers on the scenario you just built.
    - [Architecture deep dive](../../deep-dive/architecture.md) — how
      the storage, scheduler, and generator interact under the
      hood.
