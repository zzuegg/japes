---
title: Tutorials
---

# Tutorials

22 chapters covering every feature japes exposes. Each chapter builds on the previous one; by the end you've touched every annotation and service parameter the library ships. The chapters are self-contained — you can skip around — but the basics section should be read roughly in order if you're new to ECS.

<div class="japes-card-grid" markdown>

<div class="japes-card" markdown>
### :material-numeric-1-circle: Basics
Components, entities, systems, queries, resources, change detection, `RemovedComponents`, `Commands`.
</div>

<div class="japes-card" markdown>
### :material-numeric-2-circle: Advanced
Events, `Local<T>`, stages and ordering, multi-threading, `@With` / `@Without`, `@Where`, `@Exclusive`, run conditions.
</div>

<div class="japes-card" markdown>
### :material-numeric-3-circle: Relations
`@Relation`, `@Pair` + `PairReader`, `@ForEachPair` + `@FromTarget`, cleanup policies, `RemovedRelations`, a worked predator/prey example.
</div>

</div>

## Basics

| # | Chapter | What you'll learn |
|--:|---|---|
| 1 | [Components](basics/01-components.md) | Why components are records, field rules, marker components |
| 2 | [Entities](basics/02-entities.md) | `spawn` / `despawn`, `Entity` handles, archetype formation |
| 3 | [Systems](basics/03-systems.md) | `@System` methods, class registration, single-instance rule |
| 4 | [Queries — `@Read` and `@Write`](basics/04-queries.md) | The hot loop API, `Mut<C>`, `Entity` parameters |
| 5 | [Resources](basics/05-resources.md) | `Res<R>`, `ResMut<R>`, per-world singletons |
| 6 | [Change detection](basics/06-change-detection.md) | `@Filter(Added)` / `@Filter(Changed)`, dirty lists |
| 7 | [`RemovedComponents`](basics/07-removed-components.md) | Reacting to deletes with per-reader watermarks |
| 8 | [`Commands` — deferred edits](basics/08-commands.md) | Spawn/despawn from inside parallel systems |

## Advanced

| # | Chapter | What you'll learn |
|--:|---|---|
|  9 | [Events](advanced/09-events.md) | `EventReader<E>` / `EventWriter<E>`, double buffering |
| 10 | [`Local<T>`](advanced/10-local-state.md) | Per-system persistent mutable state |
| 11 | [Stages and ordering](advanced/11-stages-and-ordering.md) | Default stages, `before` / `after`, `@SystemSet` |
| 12 | [Multi-threading](advanced/12-multi-threading.md) | `Executors.multiThreaded()`, disjoint-access DAG parallelism |
| 13 | [Query filters — `@With` / `@Without`](advanced/13-query-filters.md) | Narrow archetype matches without binding |
| 14 | [Field filters — `@Where`](advanced/14-where-filters.md) | Per-entity value predicates (and the tier-2 fallback) |
| 15 | [`@Exclusive` systems](advanced/15-exclusive-systems.md) | Single-threaded bulk edits, the tier-1 exclusive runner |
| 16 | [Run conditions](advanced/16-run-conditions.md) | `@RunCondition` / `@RunIf` for gating systems |

## Relations

| # | Chapter | What you'll learn |
|--:|---|---|
| 17 | [Overview](relations/17-overview.md) | What relations are, forward + reverse indices, `@Relation` |
| 18 | [`@Pair` and `PairReader`](relations/18-pair-and-pair-reader.md) | Set-oriented dispatch, `fromSource` / `withTarget` |
| 19 | [`@ForEachPair` and `@FromTarget`](relations/19-for-each-pair.md) | Tuple-oriented dispatch, tier-1 bytecode generation |
| 20 | [Cleanup policies](relations/20-cleanup-policies.md) | `RELEASE_TARGET` vs `CASCADE_SOURCE` on despawn |
| 21 | [`RemovedRelations`](relations/21-removed-relations.md) | Reacting to dropped pairs |
| 22 | [Predator / prey example](relations/22-predator-prey.md) | Everything end-to-end on a real workload |

## If you're in a hurry

- **Just want to write a physics loop?** Read chapters 1–4 and you're done.
- **Need change detection?** Add chapters 6–7.
- **Need cross-system coordination?** Add chapter 8 (`Commands`) and chapter 11 (stages).
- **Running multiple cores?** Chapter 12 (multi-threading) is stand-alone.
- **Building a graph of related entities (parent/child, hunter/prey, ally/enemy)?** Jump straight to the relations section — chapters 17–22.
