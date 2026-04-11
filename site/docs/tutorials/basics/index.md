---
title: Basics
---

# Tutorial basics

Eight chapters covering the minimum you need to build something non-trivial. Read them roughly in order — each chapter assumes the vocabulary introduced in the previous one.

| # | Chapter | Topic |
|--:|---|---|
| 1 | [Components](01-components.md) | Why components are records, field rules, markers |
| 2 | [Entities](02-entities.md) | `spawn` / `despawn`, `Entity` handles, archetype formation |
| 3 | [Systems](03-systems.md) | `@System` methods, class registration |
| 4 | [Queries — `@Read` and `@Write`](04-queries.md) | The hot loop API, `Mut<C>` |
| 5 | [Resources](05-resources.md) | `Res<R>` / `ResMut<R>` |
| 6 | [Change detection](06-change-detection.md) | `@Filter(Added/Changed)` |
| 7 | [`RemovedComponents`](07-removed-components.md) | Per-reader watermark drain |
| 8 | [`Commands` — deferred edits](08-commands.md) | Spawn/despawn from inside parallel systems |

When you're done, move on to the [advanced section](../advanced/index.md) or jump straight to [relations](../relations/index.md) if that's what you came for.
