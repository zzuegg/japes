---
title: Relations
---

# Relations

japes ships first-class entity-to-entity relationships. Annotate a record with `@Relation`, wire pairs via `world.setRelation(src, tgt, payload)`, and query them through either of two dispatch shapes. The library maintains forward + reverse indices automatically and tears them down on despawn with a configurable cleanup policy.

This is the most advanced section of the tutorials — expect ~6 chapters of material that build on everything in the [basics](../index.md#basics) and [advanced](../index.md#advanced) sections.

## Chapter list

| # | Chapter | Topic |
|--:|---|---|
| 17 | [Overview](17-overview.md) | `@Relation`, forward + reverse indices, the decision table |
| 18 | [`@Pair` and `PairReader`](18-pair-and-pair-reader.md) | Set-oriented dispatch, `fromSource` / `withTarget` |
| 19 | [`@ForEachPair` and `@FromTarget`](19-for-each-pair.md) | Tuple-oriented dispatch, tier-1 bytecode generation |
| 20 | [Cleanup policies](20-cleanup-policies.md) | `RELEASE_TARGET` vs `CASCADE_SOURCE` on despawn |
| 21 | [`RemovedRelations`](21-removed-relations.md) | Reacting to dropped pairs with per-reader watermarks |
| 22 | [Predator / prey example](22-predator-prey.md) | Everything end-to-end on a real workload |

## When to use relations

- **Parent / child hierarchies.** A `ChildOf` relation with `CASCADE_SOURCE` cleanup gets you Flecs-style scene graphs for free.
- **Hunter / prey, ally / enemy, owner / owned.** N:M relationships where you need both "who does X target?" and "who targets X?" in O(1).
- **Faction affiliation, squad membership, build trees.** Anywhere a component field of type `Entity` would be a smell — you're one step away from needing a reverse index and forgetting to maintain it on despawn.

## When **not** to use relations

- **Pure tagging** — a marker component is simpler and free. A `record Enemy() {}` on an entity is all you need to say "this is an enemy."
- **1:1 hard-owned data** — store it as a component field. A `record Inventory(List<Item> items)` is fine; you don't need a relation between the entity and each item.
- **Scale concerns on a single source.** `TargetSlice` is a flat array tuned for 1–10 pairs per source. Past ~50 pairs per source, the O(n) lookup starts to matter. If you have predators with hundreds of simultaneous targets, the decision table in [chapter 17](17-overview.md) covers your options.
