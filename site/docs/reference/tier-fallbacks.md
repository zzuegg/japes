# Tier-1 vs tier-2 dispatch

> **"Why did my system drop to tier-2?"** — this is the lookup table.
> Every shape that the bytecode generator rejects is listed below with
> the `skipReason` source and a concrete fix. If you land on tier-2 and
> care about the difference, start by ctrl-F-ing the message you saw in
> the plan dump or the benchmark output.

japes ships two dispatch strategies for every system shape:

- **Tier-1** — a per-system hidden class generated via the
  `java.lang.classfile` API. The user method is called through a
  direct `invokevirtual` (or `invokestatic` for static methods),
  every argument is a constant-reference field read or a local, and
  the JIT inlines the user body into the generated loop the same
  way it inlines any hand-written Java.
- **Tier-2** — the reflective fallback. A `SystemInvoker` holds a
  bound `MethodHandle` and dispatches via
  `MethodHandle.asSpreader(Object[].class, …).invoke(args)`. A
  `SystemExecutionPlan` caches per-chunk storage + tracker refs so
  the fill/invoke/flush loop still pays zero allocation, but the
  call itself goes through the `MethodHandle` spreader.

Both tiers produce identical behaviour. They differ only in
dispatch cost — tier-1 is ~2× faster on steady-state pair iteration
and per-entity loops. Which tier runs for a given system is chosen
automatically at plan-build time by each generator's
`skipReason(desc)` method. If the generator returns `null`, the
system runs tier-1; otherwise it falls back to tier-2.

This document catalogs every shape that currently drops a system
to tier-2, across the three tier-1 generators.

## Where each generator lives

| Generator | System shape | Runtime shape | Source |
|---|---|---|---|
| `GeneratedChunkProcessor` | Per-entity `@System` with component params | `ChunkProcessor.process(chunk, tick)` | `ecs-core/.../system/GeneratedChunkProcessor.java` |
| `GeneratedPairIterationProcessor` | `@ForEachPair(T.class)` per-pair walker | `PairIterationRunner.run(tick)` | `ecs-core/.../system/GeneratedPairIterationProcessor.java` |
| `GeneratedExclusiveProcessor` | `@Exclusive` service-only system | `ExclusiveRunner.run()` | `ecs-core/.../system/GeneratedExclusiveProcessor.java` |

And the tier-2 counterparts:

| Tier-1 | Tier-2 fallback |
|---|---|
| `GeneratedChunkProcessor` | `SystemExecutionPlan.processChunk(chunk, invoker, tick)` |
| `GeneratedPairIterationProcessor` | `PairIterationProcessor.run()` |
| `GeneratedExclusiveProcessor` | `SystemInvoker.invoke(plan.args())` inside `World.executeSystem` |

## `GeneratedChunkProcessor` — per-entity `@System`

The per-entity chunk processor handles the vast majority of systems
— anything annotated `@System` with at least one component
parameter that isn't `@Exclusive` or `@ForEachPair`. Its
`skipReason` method rejects on four shapes:

```java
if (componentCount < 1)
    return "system has no component parameters";
if (componentCount > 4)
    return "system has " + componentCount + " component parameters (tier-1 limit is 4)";
if (params.length > 8)
    return "system has " + params.length + " total parameters (tier-1 limit is 8)";
if (!desc.whereFilters().isEmpty())
    return "system uses @Where filters";
```

!!! note "`@Filter(Removed)` is NOT in this list"

    `@Filter(Removed)` systems never reach `GeneratedChunkProcessor` — they're routed to a completely separate dispatch path (`GeneratedRemovedFilterProcessor`) that walks the removal log instead of archetype chunks. See [below](#filterremoved--its-own-tier-1-path) for details.

### 1. No component params

A `@System` with no `@Read` / `@Write` parameters (only services)
doesn't match the per-entity iteration model — the scheduler
has nothing to loop over. Systems in this shape are expected to
declare `@Exclusive` and take the `GeneratedExclusiveProcessor`
path instead. The zero-component branch in `World.executeSystem`
then invokes them once per tick via the pre-resolved service args.

**Fix:** mark the system `@Exclusive`, or add a component param if
per-entity iteration is actually what you want.

### 2. More than 4 component params

The bytecode generator pre-allocates slot indices for up to 4
component value locals in its emission buffer. Going higher is
possible but requires extending the generator's slot-layout table
and pre-initialisation loop. This cap is documented in the
generator header as "not fundamental — just bytecode-emission
complexity."

**Fix:** split the system into two systems on disjoint component
subsets, or live with tier-2 (still quite fast — just no
`invokevirtual` inlining).

### 3. More than 8 total params

`params.length > 8` is a harder cap on the generator's local-slot
layout — it accounts for component values, `Mut<T>` instances,
tracker refs, service locals, entity locals, and the `chunk` / `tick`
locals. Eight is a balance between covering realistic shapes and
keeping the generator simple.

**Fix:** move some services into a wrapper object registered as a
single `Res<T>`, or split the system.

### 4. Uses `@Where` filters

`@Where` evaluates an expression on a `HashMap<Class<?>, Record>`
lookup assembled per entity — the generator doesn't currently emit
this dispatch inline, so any system using `@Where` drops to tier-2.

**Fix:** move the predicate into the method body (`if (...) return;`)
— it's semantically the same and stays on tier-1. The drawback is
that the system runs once per matched entity rather than being
skipped entirely before invocation, which matters for systems that
would otherwise skip 99 % of their matches.

### `@Filter(Removed)` — its own tier-1 path

`@Filter(Removed)` does NOT go through `GeneratedChunkProcessor` at all. The entity that lost a component is no longer in a matching archetype, so chunk iteration can't find it. Instead, `@Filter(Removed)` systems get their own dedicated tier-1 generator: `GeneratedRemovedFilterProcessor`.

The generated `run()` method calls `RemovedFilterHelper.resolve()` (a static helper that walks the removal log, deduplicates per entity, and resolves `@Read` values from the log + live entity into reusable buffers), then iterates with inline `invokevirtual` to the user method. Same architecture as the multi-target Added/Changed path: heavy lifting in plain Java, per-entity call site in generated bytecode.

**This is NOT a tier-2 fallback.** `@Filter(Removed)` is fully tier-1 — including multi-target `target = {A.class, B.class, C.class}` with per-entity deduplication and last-value binding on `@Read` params.

### Service params: never a blocker

A commented-out line right above the skipReason body spells out
the contract:

> Non-component params (Entity / Commands / Res / ResMut /
> EventReader / EventWriter / Local / RemovedComponents) compile
> to a constant-reference field or a chunk.entity(slot) call and
> don't block the fast path.

All of those are service parameters. They go into an `Object[]`
`services` field on the generated class and are hoisted into
locals at the start of `process(chunk, tick)`. Zero dispatch
cost, zero tier-1 rejection. The only way services contribute to
a tier-1 drop is by pushing `params.length` past 8.

## `GeneratedPairIterationProcessor` — `@ForEachPair`

The pair iteration generator is the fast path for `@ForEachPair(T)`
systems — once-per-live-pair dispatch over a `RelationStore`
forward index. Its `skipReason`:

```java
if (desc.pairIterationType() == null) return "not a @ForEachPair system";
if (desc.method() == null)             return "no method";
if (Modifier.isStatic(desc.method().getModifiers()))
                                        return "static system method (tier-2 only)";
if (srcRead  > 4) return "more than 4 @Read source components";
if (srcWrite > 2) return "more than 2 @Write source components";
if (tgtRead  > 2) return "more than 2 @FromTarget @Read components";
return null;
```

### 1. Not a `@ForEachPair` system

The generator only handles `@ForEachPair` — every other shape
(`@Exclusive`, per-entity, etc.) is routed to its own generator.
This isn't really a "fallback reason" — the pair generator
simply doesn't apply.

### 2. Static system method

Instance methods get an `inst` field on the generated class that
holds the `Systems` class instance, and user dispatch is
`invokevirtual`. Static methods would need `invokestatic` without
the `inst` field, plus an alternate emission branch. Not wired up
for the pair generator yet — unlike `GeneratedExclusiveProcessor`,
which handles both.

**Fix:** make the method non-static. The runtime cost of
allocating the `Systems` instance once is zero.

### 3. More than 4 `@Read` source components

Same rationale as the chunk-processor cap: pre-allocated local
slots. Four source-reads covers every realistic pair-walking
system (the canonical pursuit shape has one).

### 4. More than 2 `@Write` source components

Tighter than the read cap because each `@Write` needs both a
storage ref and a `ChangeTracker` ref cached per archetype
transition, plus a `Mut<T>` instance hoisted to a local and
flushed in the post-inner-loop emission. Two writes = four extra
locals + two flush call sites, emitted with straight-line
bytecode.

### 5. More than 2 `@FromTarget @Read` components

Same story for the target-side cache. Target-side storages live
in their own per-target-archetype cache (separate from the
source cache) and each target-read param needs its own cached
storage local. Two keeps the generator's emission tables small.

### Parser-enforced hard rejection: `@FromTarget @Write`

Not a tier-1 *drop*, but worth calling out — `SystemParser`
rejects `@FromTarget @Write` at parse time, for every dispatch
tier:

```java
if (p.isAnnotationPresent(FromTarget.class)
    && p.isAnnotationPresent(Write.class)) {
    throw ... "' uses @FromTarget @Write which is forbidden " ...
}
```

Rationale: write conflicts between two pairs sharing a target are
ambiguous in v1. If predators A and B both hunt prey P and both
write to P's component in the same tick, which write wins? The
API doesn't define a policy, so the parser forbids the shape
rather than letting it non-deterministically overwrite. Users
who need this should either move the write off the target
(write it onto the source or the payload) or split the system
into a tier-1 target-side reader followed by a per-entity writer.

### Service params: catch-all bucket, never a blocker

The param classifier has seven buckets and service params are
the fall-through:

```java
if (p.isAnnotationPresent(Read.class))       kinds[i] = ...READ;
else if (p.isAnnotationPresent(Write.class)) kinds[i] = SOURCE_WRITE;
else if (pt == Entity.class)                 kinds[i] = ...ENTITY;
else if (desc.pairValueParamSlot() == i)     kinds[i] = PAYLOAD;
else                                          kinds[i] = SERVICE;
```

Any parameter type that isn't `@Read`, `@Write`, `Entity`, or the
relation payload type is classified `SERVICE`. No specific
service type causes tier-1 to bail: `Commands`, `Res<T>`,
`ResMut<T>`, `EventReader<E>`, `EventWriter<E>`, `Local<T>`,
`ComponentReader<T>`, `RemovedComponents<T>`,
`RemovedRelations<T>`, `PairReader<T>`, `World` — all supported.

## `GeneratedExclusiveProcessor` — `@Exclusive`

The narrowest generator. `@Exclusive` systems run once per tick
with a fully pre-resolved `Object[]` args array. The generator
emits a `run()` that unboxes the array, casts each slot to the
declared parameter type, and calls the user method via direct
`invokevirtual` (instance) or `invokestatic` (static).

```java
if (!desc.isExclusive()) return "not an @Exclusive system";
if (desc.method() == null) return "no method";
return null;
```

That's the entire fallback list.

- **No component caps.** `@Exclusive` systems shouldn't have
  component params (they'd be classified as non-exclusive by the
  parser), and the generator doesn't emit component-iteration
  bytecode at all.
- **No service caps.** Unlike the chunk generator's
  `params.length > 8` cap, the exclusive generator iterates
  `method.getParameters()` with an unbounded loop and emits
  `aload(1) + ldc(i) + aaload + checkcast(paramType)` per slot.
- **Static methods handled natively.** The exclusive generator
  branches on `Modifier.isStatic` at emission time and picks
  `invokestatic` instead of `invokevirtual`, so static `@Exclusive`
  systems stay on tier-1.

The only way an `@Exclusive` system lands on tier-2 today is if the
generator throws during `classfile.build` (a bug, not a shape
limitation) — the `tryGenerate` wrapper catches and rethrows with
the system name.

## Quick reference table

| Cause | Generator | Tier-2 fallback? | Fix |
|---|---|---|---|
| `>4 @Read` component params | chunk / pair | yes | split the system |
| `>2 @Write` component params | chunk / pair | yes | split the system |
| `>8` total params | chunk | yes | fold services into a single `Res<Bundle>` |
| `>2 @FromTarget @Read` | pair | yes | split the system |
| Static method | pair | yes | make it non-static |
| Uses `@Where` | chunk | yes | move predicate into method body |
| Uses `@Filter(Removed)` | own path (`GeneratedRemovedFilterProcessor`) | **no — tier-1 supported** | — |
| Uses `@Filter(Added/Changed)` — single target | chunk | no (tier-1 supports) | — |
| Uses `@Filter(Added/Changed)` — multi-target | chunk | no (tier-1 supports via `MultiFilterHelper`) | — |
| No component params on non-exclusive | chunk | yes | mark `@Exclusive` |
| `@FromTarget @Write` | parser (all tiers) | **rejected at parse time** | write to source or split system |
| Any service type | — | **never a blocker** | — |

## Practical cost of the fallback

Tier-2 is not slow in absolute terms. It pays for:

- `MethodHandle.asSpreader(Object[].class, N).invoke(args)` — ~20–40 ns
  on modern JVMs after warmup, one per invocation.
- Per-param `Object` box on the spreader entry (avoidable with
  `invokeExact`, but the spreader path uses a generic signature).
- One `Object[]` `args` array that's re-filled per entity/pair
  (the plan reuses it — zero allocation, just stores).

For the 500 × 2000 predator/prey workload that's worth somewhere
between 2 and 6 µs per tick depending on how hot the system is —
noticeable, but nowhere near the raw dispatch savings you'd see
against a fully reflective `Method.invoke` path.

If you hit a tier-2 fallback and care about the win, check the
reason at the top of this doc's matching section first. Most of
them have a one-line fix. The bytecode-emission caps are the only
category that genuinely requires a generator extension, and none
of them are fundamental — widening a local-slot table and adding a
few lines to the emission preamble would lift each cap. The caps
exist because every realistic system shape the [benchmarks](../benchmarks/index.md)
cross-reference comfortably fits inside them.
