---
title: "Optimization log: multi-target @Filter"
---

# Optimization log: multi-target @Filter

*How we went from 9 system registrations to 5 without losing tier-1 speed — and the bugs we found along the way.*

This page is a chronological log of the multi-target `@Filter` feature, from the benchmark that motivated it to the final tier-1 bytecode emission with zero-allocation helpers. Every number is a JMH measurement on the same hardware; every intermediate state was a real commit.

!!! info "The benchmark"

    `UnifiedDeltaBenchmark` — one logical observer watching three component types (State, Health, Mana) for added / changed / removed events. Per tick: 10% mutations per component (30% total), 1% spawn, 1% despawn, 2% component strip-and-restore. The Zay-ES counterpart does the same work with one `EntitySet.applyChanges()` call.

## Round 0 — the problem

The benchmark branch (`claude/add-japes-zayes-benchmark-7QzM2`) exposed a design asymmetry: Zay-ES tracks added / changed / removed entities across N component types from **one** `EntitySet`. japes needed **nine** separate system registrations — 3 `@Filter(Added)` + 3 `@Filter(Changed)` + 3 `RemovedComponents<T>`, one per component type per delta category.

Each system has its own execution-plan slot, watermark, dirty-list walk, and scheduler dispatch. At 10k entities the per-tick work is small and the fixed scheduling overhead of 9 dispatches dominates.

| | japes (9 systems) | Zay-ES (1 EntitySet) |
|---|---:|---:|
| **10k** | 293 µs (3.41 ops/ms) | 234 µs (4.27 ops/ms) |
| **100k** | 3,623 µs (0.276 ops/ms) | 5,051 µs (0.198 ops/ms) |

japes already won at 100k (dirty-list walks scale with dirty count, not total N) but lost at 10k due to scheduler overhead.

## Round 1 — multi-target `@Filter` annotation

**Change**: widen `@Filter.target()` from `Class<? extends Record>` to `Class<? extends Record>[]`:

```java
// Before: 3 separate systems
@Filter(value = Changed.class, target = State.class)
void s1(@Read State s) { ... }
@Filter(value = Changed.class, target = Health.class)
void s2(@Read Health h) { ... }
@Filter(value = Changed.class, target = Mana.class)
void s3(@Read Mana m) { ... }

// After: 1 system
@Filter(value = Changed.class, target = {State.class, Health.class, Mana.class})
void observe(@Read State s, @Read Health h, @Read Mana m) { ... }
```

Semantics: OR within one `@Filter` annotation (fires if ANY target changed), AND across multiple `@Filter` annotations (same as before).

The implementation touched `SystemDescriptor.FilterDescriptor` (now holds `List<Class<? extends Record>> targets`), `SystemExecutionPlan` (2D `ChangeTracker[][]` cache, `BitSet`-based dirty-list union in the sparse path, `checkFilterGroup` with OR semantics), and `World.buildExecutionPlan` (resolves all target ComponentIds, prunes all targets' dirty lists).

**Two bugs found during TDD:**

1. **The single-target fast path skipped the primary filter's `isChangedSince` check.** The refactored code called `checkRemainingFilters(slot)` (starting at index 1), but the old code checked ALL filters including index 0. Stale dirty-list entries from the priming tick passed through unchecked. Fixed: always call `checkAllFilterGroups(slot)`.

2. **`BytecodeChunkProcessor` doesn't understand `@Filter` at all.** When `GeneratedChunkProcessor` bailed on multi-target, the fallback went to `BytecodeChunkProcessor` which did a full-scan over every entity, ignoring the filter entirely. Both entities got visited regardless of dirty state. Fixed: skip chunk-processor generation entirely for multi-target filter systems so dispatch falls to `plan.processChunk` (which handles the dirty-list union).

**Result — tier-2 only:**

| | 9-system | 5-system (tier-2) | Zay-ES |
|---|---:|---:|---:|
| **10k** | 3.41 ops/ms | **2.83 ops/ms** | 4.27 ops/ms |
| **100k** | 0.276 ops/ms | **0.235 ops/ms** | 0.198 ops/ms |

**20% regression at 10k.** The tier-2 `plan.processChunk` path with `BitSet` union is slower than the tier-1 `GeneratedChunkProcessor` single-target path that the 9-system version uses. Fewer dispatches doesn't help when each dispatch is slower.

## Round 2 — tier-1 bytecode generation for multi-target

**Change**: lift the `skipReason` bail in `GeneratedChunkProcessor`. The generated `process(chunk, tick)` method now calls a static `MultiFilterHelper.unionDirtySlots(...)` to get a deduplicated `int[]` of matching slots, then iterates that array with the same inline per-slot component-load + `invokevirtual` user-method-call that single-target tier-1 uses.

The helper is plain Java (not emitted bytecode) — the union logic stays debuggable while the hot per-slot body stays in the generated hidden class for JIT inlining.

First version allocated a `BitSet` + `int[]` per chunk call:

| | 9-system | 5-sys tier-2 | **5-sys tier-1 v1** | Zay-ES |
|---|---:|---:|---:|---:|
| **10k** | 3.41 | 2.83 | **3.38** | 4.27 |
| **100k** | 0.276 | 0.235 | **0.259** | 0.198 |

Tier-2 regression fully recovered. But still 5-15% behind the 9-system approach at 100k due to the `BitSet` + `int[]` allocation overhead per chunk.

## Round 3 — zero-allocation helper

**Change**: replace the `BitSet` with a reusable `long[]` bitmap field on the generated class, the result `int[]` with a reusable `int[]` field, and the `ChangeTracker[]` with a reusable field. All three are allocated once at plan-build time and cleared per chunk (`Arrays.fill(bitmap, 0L)`). The helper signature changes to accept these buffers and return a match count instead of an array.

Additional optimization: the OR check tries the **owning tracker first** (the one whose dirty list contained the slot) before checking others. In the common case (most entries are fresh), the first check succeeds and the inner loop exits immediately.

| | 9-system | 5-sys tier-2 | 5-sys tier-1 v1 | **5-sys tier-1 v2** | Zay-ES |
|---|---:|---:|---:|---:|---:|
| **10k** | 3.41 | 2.83 | 3.38 | **3.62** | 4.27 |
| **100k** | 0.276 | 0.235 | 0.259 | **0.263** | 0.198 |

**6% faster than 9 separate systems at 10k** (3.62 vs 3.41). The dispatch savings from 5 vs 9 systems now exceed the helper overhead. At 100k the gap is ~5% (0.263 vs 0.276) — the helper's bitmap-clear + union walk adds a small per-chunk cost that's visible at high entity counts.

## Final comparison

Converting to µs/op for readability:

| Entities | 9 systems (single-target) | **5 systems (multi-target)** | Zay-ES | japes vs Zay-ES |
|---:|---:|---:|---:|---|
| **10k** | 293 µs | **276 µs** | 234 µs | 1.18× slower |
| **100k** | 3,623 µs | **3,802 µs** | 5,051 µs | **1.33× faster** |

The multi-target `@Filter` feature:

- **Cuts boilerplate 44%** — 5 system registrations instead of 9 for the same logical observer
- **Is faster than the workaround at 10k** — fewer dispatches win when per-dispatch cost is minimal
- **Is within 5% at 100k** — the bitmap-union helper adds a small per-chunk overhead that's visible at scale but not load-bearing
- **Beats Zay-ES at 100k by 1.33×** — japes's dirty-list walks scale with dirty count (30% of N), while Zay-ES's `applyChanges()` scans the full EntitySet membership

The 10k gap to Zay-ES (1.18×) is the remaining cost of 5 system dispatches + scheduler overhead vs Zay-ES's single `applyChanges()` call. Closing it further requires the [Tier 3 unified observer](../tutorials/relations/17-overview.md) design discussed in the API planning sessions.

## What we learned

1. **Tier-1 is non-negotiable for filter systems.** The tier-2 `plan.processChunk` path is 20% slower than tier-1 even for the same logical work, because the `SystemInvoker.invoke` reflective call + the per-slot `fillComponentArgs` overhead dominates at small entity counts. Any new filter feature that drops to tier-2 will regress the benchmark.

2. **Per-chunk allocation matters at scale.** The v1 helper allocated a `BitSet` + `int[]` per chunk. At 100k entities with ~100 chunks, that's 200 allocations per tick. The zero-allocation v2 recovered ~2% just by reusing buffers.

3. **The `BytecodeChunkProcessor` / `DirectProcessor` fallback silently ignores `@Filter`.** This was a latent bug — any system that fell off the tier-1 `GeneratedChunkProcessor` path AND had `@Filter` annotations would full-scan every entity. The multi-target work exposed it; the fix was to skip the chunk-processor generator entirely when `plan.processChunk` is needed for filter handling.

4. **Dirty-list pruning must cover all targets.** The World end-of-tick prune used `f.targetId()` (first target only) to decide which component to prune. Multi-target filters left the other targets' dirty lists unpruned, causing stale entries to accumulate across ticks and inflate the union iteration set. Fixed by looping over all `f.targetIds()`.

## Round 4 — flat topological order in the executor

Profiling after round 3 showed ~13% of CPU in scheduler infrastructure: `ScheduleGraph.complete()` HashMap lookups + `TreeMap.getFirstEntry` from `ArchetypeId` iteration during archetype graph lookups.

**Change**: `ScheduleGraph.flatOrder()` computes a topological sort once via Kahn's algorithm and caches the result as a flat `SystemNode[]`. The `SingleThreadedExecutor` now iterates this array instead of running the `readySystems()` / `complete()` DAG loop per tick. Eliminates per-tick `ArrayList` allocation, `HashMap.getOrDefault` lookups, and `boolean[]` scans.

**Result**: within noise on both benchmarks (3.44 vs 3.62 ops/ms at 10k — the error bars overlap). The scheduler overhead was only ~5 µs per tick for 5-system workloads. The win is structural: zero per-tick allocation in the executor, guaranteed O(1) execution order lookup regardless of DAG complexity, and cleaner code. Bigger workloads with more systems will see a larger absolute win.

## Round 5 — `@Filter(Removed)` with last-value binding

The final piece: `@Filter(Removed, target = {S, H, M})` completes the symmetric API. Instead of 3 separate `RemovedComponents<T>` systems, one `@Filter(Removed)` observer fires once per entity that lost any target component, with `@Read` params bound to the last-known values (from the removal log for removed components, from the live entity for still-present ones).

This is a fundamentally different dispatch path — the entity that lost a component is no longer in a matching archetype, so normal chunk iteration can't find it. `RemovedFilterProcessor` walks the removal log directly.

**Bug found during implementation**: the removal log GC check (`consumedRemovedComponents` on the plan) wasn't set for `@Filter(Removed)` systems, so the log grew unbounded. Without the fix, the benchmark ran at **0.73 ops/ms** (5× regression). With the fix: **3.34 ops/ms**.

| | 9-system | 5-system | **3-system** | Zay-ES |
|---|---:|---:|---:|---:|
| **10k** | 3.41 | 3.62 | **3.34** | 4.27 |
| **100k** | 0.276 | 0.263 | **0.257** | 0.198 |

The 3-system version is ~8% slower than 5-system at 10k because `RemovedFilterProcessor` is tier-2 (reflective, `LinkedHashMap` dedup, `SystemInvoker.invoke` per entity). Tier-1 generation for this path would close the gap.

## Related

- [Unified delta benchmark](../benchmarks/unified-delta.md)
- [Tier fallbacks reference](../reference/tier-fallbacks.md)
- [Optimization journey (relations)](optimization-journey.md) — the earlier 167→32 µs story
