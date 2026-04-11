# Sparse delta — the canonical change-detection workload

10 000 entities, 100 touched per tick. An observer reacts only to
the entities whose `Health` changed. This is the scenario change
detection is *built for*: per-tick work should scale with the dirty
count, not the total entity count.

!!! tip "What this workload measures"

    - **Population.** 10 000 entities with a `Health` component each.
    - **Mutation.** 100 entities per tick receive
      `world.setComponent(e, new Health(...))` via a rotating cursor
      so different cohorts are touched every tick.
    - **Observation.** A single observer system declared as
      `@Filter(Changed.class) @System void observe(@Read Health h)`
      accumulates a sum of HP values for the tick.

    The right answer scales with **K** (dirty count = 100), not with
    **N** (total = 10 000). Libraries that do a full archetype scan
    don't fit in "right answer" territory; they just get there by
    brute force.

## Results

Numbers are µs/op, lower is better. Copied verbatim from `DEEP_DIVE.md`.

| benchmark | entityCount | bevy | **japes** | zayes | dominion | artemis |
|-----------|------------:|-----:|----------:|------:|---------:|--------:|
| `tick`    |       10000 | 4.01 |  **1.85** |  4.68 |     0.37 |    0.26 |

This is the most interesting row in the whole cross-library table,
so it deserves the most explanation.

## Two camps: library change-detection vs hand-rolled dirty buffers

**japes / Zay-ES / Bevy** implement the workload the way the API
advertises: the driver calls `world.setComponent(e, new Health(...))`
(or Zay-ES's `data.setComponent(...)`), which the library records in
a per-tick dirty tracker; the observer system is scheduled
automatically and walks the library's dirty view. The user writes
zero bookkeeping code and the contract "every mutation is observed"
is enforced globally. At **1.85 µs/op japes is the fastest of the
library-change-detection group by a wide margin** (Bevy is 4.01,
Zay-ES is 4.68) after three rounds of profile-guided fixes (cached
`ArchetypeId.hashCode`, generation-keyed `findMatching` cache,
one-lookup `setComponent`, direct `Archetype` reference on
`EntityLocation`, array-indexed chunk lookups keyed by
`ComponentId.id()`, `setComponent` chunk consolidation) plus the
concurrent `ArchetypeGraph` cache and the `ChangeTracker.swapRemove`
dirty-bit fix from the code-review PR. **japes is 2.17× faster than
Bevy** on this workload.

**Dominion / Artemis** have no change detection. The honest
implementation is the pattern a performance-conscious user would
hand-write: mutate the component's field in place *and* push the
entity handle onto a caller-maintained dirty buffer
(`ArrayList<Entity>` for Dominion, `IntBag` for Artemis, both
default-constructed). The "observer" is just a second loop over
that buffer.

!!! note "Earlier revisions cheated on buffer sizing"

    Earlier revisions of these two benchmarks pre-sized the dirty
    buffer to exactly the per-tick batch count
    (`new Entity[BATCH]`, `new IntBag(BATCH)`), which a real
    game-code author couldn't know in advance. They now use
    default-capacity growing containers; the numbers only moved by
    ~5% because after the first tick the backing array has
    stabilised at its steady-state size and subsequent ticks pay
    amortised-constant append cost, but the shape of the code is
    now realistic.

That hand-written path turns out to be **still faster** on this
microbenchmark — Artemis is ~13× faster than japes, Dominion ~8×.
The reason is unsurprising once you unpack it:

- Dominion / Artemis do a `hp -= 1` int write and append an entity
  reference. No allocation, no tick-counter comparison, no atomic
  state update, no scheduler.
- japes / Bevy / Zay-ES pay for change-tracking bookkeeping at every
  `setComponent` call *and* at the observer side when walking the
  dirty view. In japes this is one indexed bitmap update + one
  dirty-slot list append per call, plus scheduler overhead on the
  observer side — and it amortises poorly across *just 100* entities
  with nothing else running.

## What you're trading for that ~13× microbenchmark gap

The hand-rolled pattern is only cheap because the microbenchmark has
exactly one mutation site, one observer, and one component. At
real-codebase scale the costs show up:

- **Correctness is a contract the compiler can't check.** Every
  place in your code that mutates `Health` has to remember to append
  to the dirty buffer. Add a new system a year later, forget the
  append, silently drop events. The library maintains the invariant
  globally — you cannot forget.
- **It doesn't compose across observers.** N observers × M mutation
  sites = N×M append calls you have to keep in sync. The library
  indexes this once, centrally. Adding a new observer in japes is
  one annotation; in manual land it's "find every mutation site and
  add another append."
- **Dedup costs perf or correctness.** Mutate the same entity twice
  in a tick and the naive list sees it twice. Either the observer
  does duplicate work, or you add a `Set<Entity>` on every append —
  which kills the perf advantage that made the manual path attractive
  in the first place. japes uses a per-tracker bitmap for O(1) dedup.
- **Frame-boundary coordination is your problem.** With multiple
  observers you have to agree on who clears the buffer and when.
  The library handles it at tick boundaries.
- **`Added`/`Removed` need their own plumbing.** japes ships
  `@Filter(Added.class)` and `RemovedComponents<T>` which work
  together with `Changed`. In manual land each is a separate buffer
  appended to from every `create`/`destroy`/`remove` site.
- **You lose filter composition.**
  `@Filter(Changed, Health) @Without(Dead) @With(Player)` is one
  annotation combination in japes that the scheduler resolves
  statically. In manual land you iterate the dirty list and
  re-check `!dead.has(e) && player.has(e)` per entry.
- **No free parallelism.** japes's scheduler runs disjoint observers
  in parallel for free from the declared access metadata — see
  the [realistic-tick benchmark](realistic-tick.md). A manual dirty
  list has no access metadata so the scheduler can't help; if you
  want multi-core you wire up `ExecutorService` yourself.
- **No tick history.** Bevy's change detection is tick-indexed — a
  system running every 3 ticks can ask "did this change since I
  last ran?" correctly. A bare dirty buffer is tick-local and
  forgets.
- **Debuggability.** Library change tracking knows the tick, the
  system that wrote, and the slot. An `Entity[]` has none of that.
- **The perf win shrinks with population.** At 100 dirty out of
  10 000 the fixed library overhead dominates and manual wins
  ~20×. Push dirty past ~5% of total and the constant-factor
  overhead amortises away — it becomes "iterate an array either
  way."

On **this microbenchmark** — one observer, one mutation site,
ultra-sparse dirty set — the hand-rolled path wins by a mile and
that's what the numbers show. On a [**realistic multi-observer
tick**](realistic-tick.md) the library path wins by a mile in the
other direction, because it does the work that no single
microbenchmark measures.

!!! note "Fairness disclosure"

    The japes benchmark body includes full `world.tick()` overhead
    (event swap, stage traversal, dirty-list pruning) while the
    Artemis / Dominion counterparts hand-roll a tight loop without
    any of it. Material at only 100 dirty entities. The disclosure
    simply makes the comparison honest — the japes number is not
    artificially inflated by tick overhead subtraction, and it still
    comes in at 1.85 µs/op.

## Valhalla delta

| benchmark          | case | **japes** | **japes-v** | Δ            |
|--------------------|-----:|----------:|------------:|-------------:|
| `SparseDelta tick` |  10k |      1.85 |        1.96 | 0.94× slower |

Within noise. The bottleneck is change-tracker bookkeeping, not
component reads, so there's nothing for Valhalla to flatten.

## Reproducing

```bash
./gradlew :benchmark:ecs-benchmark:jmhJar

java --enable-preview \
  -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "SparseDeltaBenchmark" \
  -p entityCount=10000
```
