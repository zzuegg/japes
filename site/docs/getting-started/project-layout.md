---
title: Project layout
---

# Project layout

How the japes repository is organised, which module does what, and which ones you actually depend on.

## Top-level tree

```
japes/
├── ecs-core/                  # The library. This is what you depend on.
├── benchmark/
│   ├── ecs-benchmark/         # japes JMH benchmarks — all the numbers in the Benchmarks section.
│   ├── ecs-benchmark-valhalla/# Same benchmarks with @LooselyConsistentValue value records.
│   ├── ecs-benchmark-zayes/   # Cross-library: Zay-ES reference implementations.
│   ├── ecs-benchmark-dominion/# Cross-library: Dominion.
│   ├── ecs-benchmark-artemis/ # Cross-library: Artemis-odb.
│   └── bevy-benchmark/        # Cross-library: Bevy 0.15 Rust reference.
├── site/                      # This website (MkDocs Material).
├── README.md                  # Short quick-start + links.
├── DEEP_DIVE.md               # Full benchmark analysis.
├── TIER_FALLBACKS.md          # Per-generator skipReason catalog.
└── build.gradle.kts           # Root Gradle config (JDK 26 + --enable-preview).
```

## `ecs-core` — the library

The only module you depend on as a consumer. One Gradle subproject, ~90 Java source files, zero runtime dependencies outside the JDK. Published as `io.github.zzuegg.japes:ecs-core` — see [Installation](installation.md).

### Package structure

```
zzuegg.ecs
├── archetype      — Archetype graph, archetype id, per-archetype chunk lists
├── change         — ChangeTracker with dirty bitmap + per-slot addedTick/changedTick
├── command        — Commands buffer + deferred structural edit processing
├── component      — ComponentId/Info/Registry, Mut<T>, ComponentReader<T>
├── entity         — Entity record (packed index + generation)
├── event          — EventReader/EventWriter + per-tick double buffer
├── executor       — SingleThreaded and MultiThreaded executors
├── query          — ComponentAccess, AccessType, field filters
├── relation       — RelationStore + TargetSlice/SourceSlice + change/removal tracking
├── resource       — Res / ResMut / ResourceStore
├── scheduler      — Stage definitions, ScheduleGraph, topological sort
├── storage        — Chunk + DefaultComponentStorage (reference + flat variants)
├── system         — @System + @Exclusive + @ForEachPair, tier-1 generators, SystemParser
├── util           — LongArrayList (primitive-long growable list)
└── world          — World + WorldBuilder, snapshot, entity location table
```

You only need to `import` things from `zzuegg.ecs.*` — nothing under `zzuegg.ecs.system.Generated*` is user-facing. Package-private classes stay package-private.

## `benchmark/*` — the benchmark modules

Not published. Clone the repo if you want to run them. Each JMH module targets one ECS implementation so cross-library comparisons are self-contained.

| Module | Runs against | What you get |
|---|---|---|
| `benchmark/ecs-benchmark` | Stock japes on JDK 26 | All the headline numbers in the [benchmarks section](../benchmarks/index.md) |
| `benchmark/ecs-benchmark-valhalla` | japes with `value record` components on the Valhalla EA JVM | The [Valhalla investigation](../benchmarks/valhalla.md) |
| `benchmark/ecs-benchmark-zayes` | [Zay-ES](https://github.com/jMonkeyEngine-Contributions/zay-es) | Cross-library reference |
| `benchmark/ecs-benchmark-dominion` | [Dominion](https://github.com/dominion-dev/dominion-ecs-java) | Cross-library reference |
| `benchmark/ecs-benchmark-artemis` | [Artemis-odb](https://github.com/junkdog/artemis-odb) | Cross-library reference |
| `benchmark/bevy-benchmark` | Bevy 0.15 (Rust) | `cargo bench` |

Running them:

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar
```

See the [Benchmarks methodology](../benchmarks/methodology.md) page for the full reproduction commands.

## `site/` — this website

MkDocs Material sources under `site/docs/`, built into `site/build/` (git-ignored). Published to GitHub Pages automatically via `.github/workflows/docs.yml` on every push to `main`.

To preview locally:

```bash
pip install mkdocs-material pymdown-extensions
mkdocs serve
```

Then open `http://localhost:8000`.

## Root docs

Three markdown files live at the repo root alongside this site:

- `README.md` — the GitHub landing page; a shorter version of everything here.
- `DEEP_DIVE.md` — the canonical benchmark result tables. The benchmarks section of this site is split from that file; the two should stay in sync.
- `TIER_FALLBACKS.md` — the per-generator `skipReason` catalog. Served at [Reference / Tier fallbacks](../reference/tier-fallbacks.md) on this site.

These are primary sources. If the site and `README.md` / `DEEP_DIVE.md` ever disagree, the root `.md` is authoritative until the site is refreshed from it.

## What's next

- [Quick start](quick-start.md) — build and run your first world
- [Tutorials](../tutorials/index.md) — 22-chapter walkthrough of every feature
- [Benchmarks](../benchmarks/index.md) — the performance story
