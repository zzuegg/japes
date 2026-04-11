---
title: Advanced
---

# Tutorial advanced

Eight chapters on the features that make japes' scheduler hum — events, per-system state, stage ordering, multi-threading, archetype narrowing, and the escape hatches (`@Where`, `@Exclusive`, `@RunCondition`) for when the common path isn't enough.

| # | Chapter | Topic |
|--:|---|---|
|  9 | [Events](09-events.md) | `EventReader<E>` / `EventWriter<E>` |
| 10 | [`Local<T>`](10-local-state.md) | Per-system persistent mutable state |
| 11 | [Stages and ordering](11-stages-and-ordering.md) | `before` / `after`, default stage names |
| 12 | [Multi-threading](12-multi-threading.md) | `Executors.multiThreaded()`, disjoint-access parallelism |
| 13 | [Query filters — `@With` / `@Without`](13-query-filters.md) | Archetype narrowing without binding |
| 14 | [Field filters — `@Where`](14-where-filters.md) | Per-entity value predicates |
| 15 | [`@Exclusive` systems](15-exclusive-systems.md) | Single-threaded bulk edits |
| 16 | [Run conditions](16-run-conditions.md) | `@RunCondition` / `@RunIf` for gating |

Chapters are mostly independent; pick what you need. The only hard dependency is that [multi-threading](12-multi-threading.md) assumes you understand [Commands](../basics/08-commands.md) from the basics section.
