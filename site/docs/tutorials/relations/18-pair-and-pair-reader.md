# `@Pair` and `PairReader`

> The set-oriented dispatch model for relations. A system is called
> **once per entity** that carries at least one pair of a given
> relation type, and the body walks the entity's pairs via a
> `PairReader<T>` service parameter.

## When to reach for it

`@Pair(T.class)` fits systems where the body does entity-level work
that only sometimes needs to fan out into a pair walk:

- "If this predator has any pending `Hunting` pairs, pick the
  closest one and steer toward it."
- "For each garrison, emit a reinforcement command per garrisoned
  unit."
- "Tick down a cooldown on the entity, then bump the per-pair timer
  for every pair."

In all three, the shape is the same: one archetype-filtered dispatch
per entity, zero to many pairs inside. If you're doing **identical**
work for every pair regardless of the owning entity, pick
[`@ForEachPair`](19-for-each-pair.md) instead.

## Minimal example

```java
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.relation.PairReader;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.system.Pair;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;

@Relation
public record Targeting(int power) {}

public record Attacker(int cooldown) {}
public record IncomingDamage(int amount) {}

public static class CombatSystems {

    @System
    @Pair(Targeting.class)
    public void applyDamage(
            @Read Attacker attacker,
            Entity self,
            PairReader<Targeting> reader,
            Commands cmds
    ) {
        if (attacker.cooldown() > 0) return;
        for (var pair : reader.fromSource(self)) {
            int dmg = pair.value().power();
            cmds.add(pair.target(), new IncomingDamage(dmg));
        }
    }
}
```

What's going on here:

1. `@Pair(Targeting.class)` adds the `Targeting` **source marker** to
   `applyDamage`'s required-component set. The archetype filter skips
   every entity that has no outgoing `Targeting` pair — no allocation,
   no dispatch, no body call.
2. `PairReader<Targeting> reader` is resolved once per system as a
   service parameter, exactly like `Commands` or `Res<T>`.
3. `Entity self` is the per-entity binding supplied by the normal
   `@System` machinery — there's nothing relation-specific about it.
4. Inside the body, `reader.fromSource(self)` returns a lazy iterable
   over every `(source, target, payload)` triple originating at
   `self`. It walks the live forward-index slice directly — no
   snapshot copy.

## The `PairReader<T>` API

Defined in `zzuegg.ecs.relation.PairReader`:

```java
public interface PairReader<T extends Record> {

    record Pair<T extends Record>(Entity source, Entity target, T value) {}

    // Every pair whose source is `source`.
    Iterable<Pair<T>> fromSource(Entity source);

    // Every pair whose target is `target` — reverse-index walk.
    Iterable<Pair<T>> withTarget(Entity target);

    // Cheap existence checks — avoid the iterable allocation just to
    // ask "is there at least one?".
    boolean hasSource(Entity source);
    boolean hasTarget(Entity target);

    // Direct lookup for a specific pair.
    Optional<T> get(Entity source, Entity target);

    // Total live pair count across the world (for this type).
    int size();
}
```

Both `fromSource` and `withTarget` return iterables that walk the
store's live maps directly. The implementation
(`StorePairReader`) reuses the walk object as its own iterator so
per-call allocation is one record wrapper plus one walker, both
usually scalar-replaced by the JIT. The only caveat: **don't**
iterate the same returned iterable twice — it's single-use per call.

### `fromSource` vs `withTarget`

`fromSource(self)` walks the **forward index** — what the entity
points at. This is what a predator's pursuit system uses to find
the prey it's hunting.

`withTarget(self)` walks the **reverse index** — what points at the
entity. This is what a prey's awareness system uses to ask "who is
hunting me right now?".

Both are O(k) in the number of pairs on `self`, not O(total pairs).
The slices are stored as flat `long[]` + `Object[]`, so the hot
path is a tight indexed loop.

## Reverse-walking with `role = TARGET`

Here's the trick. If you write a system that cares about entities
**being targeted**, the default `@Pair(T.class)` filter (which
requires the **source** marker) doesn't help — it narrows to the
attackers, not the victims. You'd have to fall back to "run on
everything, check `reader.hasTarget(self)` manually" — which pays a
dispatch per alive entity.

`@Pair` has a `role` argument for exactly this:

```java
import zzuegg.ecs.system.Pair.Role;

@System
@Pair(value = Targeting.class, role = Role.TARGET)
public void awareness(
        @Read Health h,
        Entity self,
        PairReader<Targeting> reader
) {
    // Filter has already narrowed to "entities with at least one
    // incoming Targeting pair" via the target marker. The body only
    // runs on prey that are actually being hunted.
    for (var pair : reader.withTarget(self)) {
        // react to `pair.source()`
    }
}
```

`role = TARGET` swaps which archetype marker the annotation adds to
the system's required-component set. The target marker is updated
by `World.setRelation` / `World.removeRelation` in lockstep with
the source marker, so the filter is always accurate.

!!! tip "`@Pair(role = TARGET)` narrows the archetype filter for free"
    Without `role = TARGET`, an awareness system has to run on every
    entity with a `Health` component and then check "am I being
    hunted?" inside the body. With `role = TARGET`, the archetype
    filter already skipped every prey that's not currently the
    target of any `Targeting` pair. Huge win when the targeted
    subset is small — which is the common case for hunts, locks,
    mounts, and "attached to" semantics.

### The `Role` enum in full

```java
public enum Role {
    SOURCE,  // Default. Requires the source marker.
    TARGET,  // Requires the target marker.
    EITHER   // Informational only — no archetype narrowing.
}
```

`EITHER` is for systems that legitimately walk pairs in both
directions (or don't need the filter to prune anything). It's rare.
The default is `SOURCE` so pre-existing `@Pair(T.class)` declarations
keep their original meaning.

## Multiple `@Pair` annotations on one system

`@Pair` is `@Repeatable`. Stack it to require several relation types
or roles at once:

```java
@System
@Pair(Targeting.class)                          // requires source marker
@Pair(value = ShieldedBy.class, role = Role.TARGET)  // requires target marker
public void combatTick(
        @Read Health h,
        Entity self,
        PairReader<Targeting> targeting,
        PairReader<ShieldedBy> shields
) {
    // Runs only on entities that are BOTH targeting something AND
    // being shielded by something. One PairReader per relation type.
}
```

The two markers combine normally via the archetype filter — both
must be present on the entity for the system to fire.

## Cheap existence vs full iteration

If you only need to *know whether* an entity has any pairs, don't
allocate the iterable:

```java
if (reader.hasSource(self)) {
    // yes — at least one outgoing Targeting pair
}
```

`hasSource` is an `O(1)` lookup in the forward-index outer map.
`hasTarget` is the same on the reverse map. Both bypass the walker
entirely.

## Safety: don't mutate the store while iterating

!!! warning "Don't mutate pair storage during iteration"
    Inside a `PairReader` walk — and more generally inside any
    `@Pair`-dispatched body — you must not call `world.setRelation`
    or `world.removeRelation` for the relation type you're
    currently walking. The iterator reads the store's live forward
    and reverse slices without snapshotting, so a mutation mid-walk
    can skip or double-count pairs.

    The safe path is `Commands.setRelation` / `Commands.removeRelation`
    from inside the system body. The command buffer flushes at the
    next stage boundary, after every parallel system in the current
    stage has finished. Your iteration sees a consistent store, and
    your edits apply atomically between stages.

## What you learned

!!! summary "Recap"
    - `@Pair(T.class)` narrows a system's archetype filter to
      entities that carry at least one pair of type `T`.
    - `PairReader<T>` is a world-scoped service parameter; the body
      passes `self` into `fromSource` / `withTarget` for per-entity
      iteration.
    - `role = TARGET` switches the filter to the target marker, so
      reverse-walking systems skip un-targeted entities for free.
    - `hasSource` / `hasTarget` are allocation-free existence
      checks.
    - Mutate via `Commands`, not `World`, from inside a pair walk.

## What's next

!!! tip "Next chapter"
    The tuple-oriented dispatch. `@ForEachPair` binds source and
    target components directly as method parameters and gets a
    bytecode-generated runner. See
    [`@ForEachPair`](19-for-each-pair.md).
