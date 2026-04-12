# Deep dive

The [benchmarks section](../benchmarks/index.md) owns the numbers. This
section owns the reasoning: **why** japes is built the way it is, what
the optimisation journey taught us, and how to think about the system
when you start hitting edges the tutorials don't cover.

Everything here is cross-referenced to the actual source files under
`ecs-core/src/main/java/zzuegg/ecs/`. Every claim about a data layout,
a fast-path short-circuit, or a fallback condition is verifiable
against the current code. If you spot a mismatch, the code wins — the
docs are describing it, not specifying it.

## What to read first

<div class="grid cards" markdown>

-   __[Architecture](architecture.md)__

    ---

    Archetype + chunk storage, the `EntityLocation` table, why moves
    between archetypes are swap-removes on parallel `Object[]` columns.
    Start here if you have never read an archetype ECS internals doc
    before.

-   __[Tier-1 bytecode generation](tier-1-generation.md)__

    ---

    How a `ChunkProcessor` is emitted as a hidden class via
    `java.lang.classfile`, why every argument hoists to a local, and
    why `invokevirtual` is the dispatch primitive. Links to the
    [tier fallback catalog](../reference/tier-fallbacks.md).

-   __[Change tracking](change-tracking.md)__

    ---

    Per-component `ChangeTracker`, dirty bitmap + dirty list, strict
    `>` comparisons against `lastSeenTick`, why tick-0 is the
    untracked sentinel. The core of why `@Filter(Changed)` scales
    with the dirty count, not the entity count.

-   __[Relations](relations.md)__

    ---

    Non-fragmenting side-table storage, `Long2ObjectOpenMap` primitive
    keys, flat `TargetSlice` / `SourceSlice` inner maps, archetype
    marker components, cleanup policies, `PairChangeTracker`. Why
    relations do not fragment archetypes.

-   __[Write-path tax](write-path-tax.md)__

    ---

    Why `@Write Mut<Position>` + `record Position` is fundamentally
    more expensive than `pos.x += vel.dx` on a mutable POJO, what you
    buy for the cost, and what Valhalla *could* fix.

-   __[Valhalla investigation](valhalla-investigation.md)__

    ---

    JEP 401 value records: ~2–4× faster reads at 100k, ~10% on
    writes, the explicit flat-array opt-in, and why it stays off by
    default.

-   __[Optimisation journey](optimization-journey.md)__

    ---

    How the 500×2000 predator/prey cell went from 167 µs at PR-landing
    to 31.4 µs today. Round by round: what each fix saved, what the
    profile showed, and which ideas did not help.

</div>

## What this section is *not*

It is not a tutorial, and it is not a reference for the user-facing
API. For the hands-on walkthrough, read the
[tutorials](../tutorials/index.md). For a dry list of annotations and
parameter types, read the
[cheat sheet](../reference/cheat-sheet.md). For throughput and
latency numbers, read the [benchmarks](../benchmarks/index.md).

The pages below exist because performance work on japes has produced
a coherent body of *why*-level knowledge — design trade-offs made,
rabbit holes explored, optimisations that worked, and a few that
didn't — that is neither tutorial material nor benchmark material.
This is the place for it.

## Reading order

The pages are written to stand alone. You can jump straight to
[relations](relations.md) or [valhalla-investigation](valhalla-investigation.md)
if that's what you came for. But if you are reading top-to-bottom for
the first time, the natural order is:

1. [Architecture](architecture.md) — what the storage layer looks like
2. [Tier-1 generation](tier-1-generation.md) — how systems dispatch onto it
3. [Change tracking](change-tracking.md) — what the tick-based filter machinery does
4. [Relations](relations.md) — the non-fragmenting pair feature
5. [Write-path tax](write-path-tax.md) — the API trade-off all the
   write-heavy benchmarks are measuring
6. [Valhalla investigation](valhalla-investigation.md) — what JEP 401
   gives us today
7. [Optimisation journey](optimization-journey.md) — the war story

## Related

- [Benchmarks — Methodology](../benchmarks/methodology.md) — how the
  numbers were measured
- [Reference — Tier-1 vs tier-2 dispatch](../reference/tier-fallbacks.md)
  — exact catalogue of what falls back to reflective tier-2
- [Getting started](../getting-started/index.md) — installation and
  first-world walkthrough
