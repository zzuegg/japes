# Cleanup Policies

> What happens to a relation pair when the entity at one end of it
> despawns — controlled per-type by the `cleanup` argument to the
> `@Relation` annotation. Three choices: release the pair, cascade
> the despawn, or do nothing.

## The problem

Relations are typed edges between entities, so every pair has two
liveness dependencies — one on the source and one on the target.
When either entity dies, we have to decide what the pair means.

- For a `ChildOf(parent)` pair, the natural answer is "the child
  dies with the parent."
- For a `Hunting(prey)` pair, the natural answer is "when the prey
  is caught and despawned, the predator forgets about it and moves
  on."
- For a `LastSeen(entity)` debug pair, the natural answer is
  "leave the record alone; we'll check liveness at read time."

All three are valid. The framework supports them as three named
policies on `zzuegg.ecs.relation.CleanupPolicy`.

## The `CleanupPolicy` enum

```java
package zzuegg.ecs.relation;

public enum CleanupPolicy {
    RELEASE_TARGET,
    CASCADE_SOURCE,
    IGNORE
}
```

And you select one on the payload record:

```java
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;

@Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
public record ChildOf() {}
```

The default — applied to every `@Relation` that doesn't mention a
policy — is `RELEASE_TARGET`.

### `RELEASE_TARGET` (default)

> When the target of a pair is despawned, drop the pair. The source
> entity stays alive. The source observes the drop as a normal pair
> removal (change tracker updated, `RemovedRelations<T>` log entry
> appended) and loses its source marker if it ran out of pairs of
> this type.

This is the right choice when pairs represent *references* — the
source cares about the target, but its own existence is not
coupled to the target's. Hunting, targeting, "last attacker",
"current destination", "mounted on" — all `RELEASE_TARGET`.

Every pair the despawning target participated in on the receiving
side is dropped. The source side of the pair survives; it just
stops pointing at the dead target. If another system is watching
`RemovedRelations<Hunting>`, it will see the removal event on its
next tick and can react to it (score a point, pick a new target,
etc).

### `CASCADE_SOURCE`

> When the target of a pair is despawned, despawn every source
> that was pointing at it. Cascades transitively — sources of
> sources are drained in FIFO order until the cascade queue is
> empty.

Use this for **ownership** relations: the source cannot logically
outlive the target. Parent-child hierarchies, sub-entities of a
composite object, equipment slots tied to a unit.

```java
@Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
public record ChildOf() {}
```

The `World.despawn` path maintains a FIFO queue of entities that
still need to be despawned. When it removes a cascade pair, it
enqueues the source for the next iteration. Because the queue is
drained to exhaustion, every transitive source reaches
`despawnInternal` — even if it itself was the target of a third
entity waiting on a `ChildOf` cascade. The `isAlive` check at the
top of each loop iteration silently skips entities that an earlier
cascade already killed, so you can't double-despawn.

!!! warning "Cycles in `CASCADE_SOURCE`"
    If your relation topology has a cycle — A has a `ChildOf` pair
    pointing at B, and B has a `ChildOf` pair pointing at A — then
    despawning either will cascade to the other. This is *safe*
    (the `isAlive` guard prevents infinite loops and double frees)
    but it may not be what you want. If your domain doesn't have
    a strict parent→child acyclic constraint, consider using
    `RELEASE_TARGET` and deciding liveness at read time.

    The same applies to diamond topologies: if A → B and A → C and
    both B and C are cascade targets of D, despawning D despawns B
    and C, and each of those cascades A exactly once thanks to the
    alive check.

### `IGNORE`

> Do nothing. The pair stays in the store even though its target
> is gone. Reads can return payloads whose target entity is dead.

This is escape hatch / debug territory. Use it when you explicitly
want to keep a record of a dead reference — "this unit used to be
garrisoned in that destroyed building; show the wreckage icon
until we detach it manually." The user is responsible for checking
`world.isAlive(pair.target())` before acting on the target.

## How cleanup runs

The full cleanup state machine lives in `World.despawnWithCascade`
and `World.applyRelationCleanup`. At a high level:

1. `despawn(entity)` entry checks the fast path: if **no** relation
   type is registered, it jumps straight to `despawnInternal` and
   skips all the cleanup plumbing. The common case for component-only
   worlds pays nothing.
2. Otherwise, the entity is pushed onto a cascade queue and the
   loop begins.
3. For each dequeued entity, `applyRelationCleanup` walks every
   registered `RelationStore` and:
    - drops every **outgoing** pair (the entity is the source),
      clearing target markers on each former target that just lost
      its last incoming pair,
    - drops every **incoming** pair (the entity is the target),
      respecting the store's `onTargetDespawn` policy:
        - `IGNORE` → skip the store entirely,
        - `RELEASE_TARGET` → drop the pair and, if the source just
          lost its last outgoing pair, clear its source marker,
        - `CASCADE_SOURCE` → drop the pair and enqueue the source
          for despawn.
4. `despawnInternal` frees the entity's archetype row; the markers
   it owned are dropped along with everything else in that row.
5. The loop moves to the next queued entity.

The cascade queue and the `isAlive` dedup make the process both
cycle-safe and deterministic: every entity reachable from the root
via `CASCADE_SOURCE` edges is despawned exactly once.

## Worked example: parent-child with cascade

```java
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.world.World;

@Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
public record ChildOf() {}

World world = World.builder().build();

Entity root = world.spawn(new Name("root"));
Entity nodeA = world.spawn(new Name("A"));
Entity nodeB = world.spawn(new Name("B"));
Entity leaf = world.spawn(new Name("leaf"));

world.setRelation(nodeA, root,  new ChildOf());  // A is child of root
world.setRelation(nodeB, root,  new ChildOf());  // B is child of root
world.setRelation(leaf,  nodeA, new ChildOf());  // leaf is child of A

// Tree:
//
//       root
//       /  \
//      A    B
//      |
//     leaf

world.despawn(root);

// After despawn(root):
//   * root is dead
//   * A is dead    (cascade via A -> root)
//   * leaf is dead (cascade via leaf -> A, picked up in the 2nd loop)
//   * B is dead    (cascade via B -> root)
//
// Every ChildOf pair is gone from the store because its source
// was despawned in the same cascade pass.
```

The ordering isn't guaranteed between siblings — the FIFO queue
drains in insertion order, which depends on the order
`applyRelationCleanup` visits reverse-index slices. Don't write
game code that relies on sibling order during cascade; if you
need ordered teardown, emit commands from a pre-despawn observer
instead.

## Worked example: hunting with release

```java
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;

@Relation(onTargetDespawn = CleanupPolicy.RELEASE_TARGET)
public record Hunting(int ticksLeft) {}

// ... later, during catch resolution:

Entity predator = /* ... */;
Entity prey = /* ... */;

world.setRelation(predator, prey, new Hunting(3));

// The catch system decides this prey is caught:
world.despawn(prey);

// After despawn(prey):
//   * prey is dead
//   * predator is STILL ALIVE — Hunting is RELEASE_TARGET.
//   * The Hunting pair is gone from the store.
//   * predator's source marker for Hunting is cleared iff this was
//     its only outgoing Hunting pair — the archetype is updated.
//   * A RemovedRelations<Hunting> entry is appended for the next
//     scoring system to drain. See the next chapter.
```

The predator can now acquire a new hunt on its next tick. Nothing
in the predator's own state is coupled to the prey's continued
existence — which is exactly the `RELEASE_TARGET` contract.

## Choosing between them

A short decision tree:

1. **Does the source have any meaningful existence without the
   target?** If no — `CASCADE_SOURCE`. If yes — continue.
2. **Does the source want to observe the drop?** If yes —
   `RELEASE_TARGET` and subscribe a `RemovedRelations<T>` observer.
   If no — `RELEASE_TARGET` anyway; the drop is silent when nobody
   is watching.
3. **Is the dead-target record itself the value you care about
   (history, audit log)?** Only then — `IGNORE`. And even then,
   prefer a dedicated audit component.

## What you learned

!!! summary "Recap"
    - `CleanupPolicy` picks what happens to incoming pairs when an
      entity is despawned.
    - `RELEASE_TARGET` (default) drops the pair and keeps the
      source alive, observed via `RemovedRelations<T>`.
    - `CASCADE_SOURCE` despawns the source transitively — perfect
      for ownership relations like `ChildOf`.
    - `IGNORE` leaves the pair in place; user code must check
      liveness on read.
    - `World.despawn` drains cleanup FIFO with an `isAlive`
      guard, so cycles and diamonds are safe.

## What's next

!!! tip "Next chapter"
    `RELEASE_TARGET` is only useful if something watches for the
    drops. Next: [`RemovedRelations<T>`](21-removed-relations.md),
    the per-consumer watermarked observer that parallels
    `RemovedComponents<T>` for pair drops.
