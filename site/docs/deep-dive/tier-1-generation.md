# Tier-1 bytecode generation

**What you'll learn:** how japes eliminates reflective-dispatch
overhead by emitting a hidden class per system at plan-build time.
We'll cover the `java.lang.classfile` API, the hidden-class lifecycle,
why every argument is hoisted to a local before the loop starts, and
why dispatch to the user method is a plain `invokevirtual`. If you
came here from `@Filter(Changed)` and expected to find reflection:
there is none. The per-entity loop the JIT sees is the same shape as
any hand-written Java loop.

## The dispatch problem tier-1 solves

Every system in japes — `@System`, `@ForEachPair`, `@Exclusive` — is
a plain Java method on some user-owned class. At some point the
scheduler has to call that method, many times per tick, from inside
a loop the scheduler owns. The naive way to do that is reflection:

```java
Method m = ...;
Object[] args = new Object[paramCount];
for (int slot = 0; slot < chunk.count(); slot++) {
    fillArgs(args, chunk, slot);    // Load each component into args[i]
    m.invoke(userInstance, args);   // Reflective dispatch
    flushMuts(args);                // Write back any Mut<T> values
}
```

That works and japes still ships it as a tier-2 fallback
(`SystemExecutionPlan.processChunk`, `SystemInvoker`). What it costs
is per-invocation overhead the JIT cannot inline through: the
`Method.invoke` path goes through `NativeMethodAccessorImpl`,
`Object[]` boxing of value parameters, and a spreader `MethodHandle`
that defeats the per-call-site inliner for the user body.
Measurably: ~1.3 ns / entity on the simple iteration micros, plus
allocation for any boxed primitive. On a 10 000 entity chunk that is
13 µs of pure dispatch before the user body runs at all.

Tier-1 replaces the loop itself. Instead of a generic dispatcher, the
plan-build step emits a **hidden class** whose `process(Chunk, long)`
method is a tight loop over the chunk, with every argument loaded in
straight-line bytecode, and a `invokevirtual` to the user method at
the bottom. The JIT sees that `invokevirtual` at a single call site
and inlines the user body directly into the generated loop.

## The three generators

japes ships three tier-1 generators, one per system shape. They all
use the same `java.lang.classfile` API and the same hidden-class
trick, but target different runtime interfaces.

| Generator | Handles | Runtime interface | Source |
|---|---|---|---|
| `GeneratedChunkProcessor` | Per-entity `@System` with component params | `ChunkProcessor.process(Chunk, long)` | `ecs-core/.../system/GeneratedChunkProcessor.java` (~753 lines) |
| `GeneratedPairIterationProcessor` | `@ForEachPair(T.class)` per-pair walkers | `PairIterationRunner.run(long)` | `ecs-core/.../system/GeneratedPairIterationProcessor.java` (~796 lines) |
| `GeneratedExclusiveProcessor` | `@Exclusive` service-only systems | `ExclusiveRunner.run()` | `ecs-core/.../system/GeneratedExclusiveProcessor.java` (~177 lines) |

The three generators share one pattern: a private `skipReason(desc)`
returns `null` if the generator can handle the system, or a
human-readable string if something prevents it. If `null`, the
system runs tier-1; otherwise the scheduler falls back to tier-2.
The complete catalogue of fallback reasons lives in
[reference/tier-fallbacks](../reference/tier-fallbacks.md).

## Hidden class per system

A hidden class is a Java class loaded via
`MethodHandles.Lookup.defineHiddenClass` rather than through a
`ClassLoader`. It has no name in any namespace: you cannot
`Class.forName` it, and the JVM will garbage-collect it as soon as no
references remain. This is exactly the lifetime the generator wants —
the class exists for as long as the plan holds its runtime instance,
and is freed when the plan is.

The entry point for chunk processor generation is at
`GeneratedChunkProcessor.java` line ~641:

```java
var systemLookup = MethodHandles.privateLookupIn(
    method.getDeclaringClass(), MethodHandles.lookup());
// ... emit bytes ...
var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
var clazz = hiddenLookup.lookupClass();
var instance = clazz.getDeclaredConstructor().newInstance();
```

Two details matter.

**`privateLookupIn` is taken on the user's system class.** The hidden
class lives in the same package as the system class, because
`defineHiddenClass` insists on it — the hidden class must be a
nestmate of its lookup's origin to be able to call package-private
methods. This is why `generateClassName` (top of the generator)
sanitises the descriptor name and drops the hidden class into the
system class's package:

```java
// The generated class must share a package with the system class so
// the hidden class can be a nestmate and invokevirtual a
// package-private system method.
var genName = generateClassName(
    method.getDeclaringClass().getPackageName(), desc.name());
```

**The instance holds an `inst` field** of type `Object`, set to the
user's `Systems` instance after `defineHiddenClass` returns. The
generated `process(...)` method does `aload 0; getfield inst;
checkcast <SystemsClass>` once at loop entry, then hoists the
resulting reference into a local (`instLocal` in the code) so the
per-iteration dispatch is a plain `aload` of that local followed by
`invokevirtual`.

## What the generated loop does, conceptually

The chunk processor generator is the most general of the three, and
the one most systems go through. Every shape decision in it is
driven by one principle: **keep the inner loop straight-line bytecode,
with every field and service reference already in a local**.

If the generated `process(Chunk chunk, long tick)` method were
written as Java — which it never is — the moral equivalent for a
two-component system (`@Read Position`, `@Write Mut<Velocity>`) with
one service (`Res<Dt>`) would be:

```java
// Pseudo-Java equivalent of the generated bytecode.
// Not a method you'd see in the source tree.
public void process(Chunk chunk, long tick) {
    // --- preamble (once per chunk) ---
    int count = chunk.count();

    // Load raw backing arrays from the chunk's default storages.
    Record[] posArr = ((DefaultComponentStorage) chunk.componentStorage(cids[0])).rawArray();
    Record[] velArr = ((DefaultComponentStorage) chunk.componentStorage(cids[1])).rawArray();
    ChangeTracker velTracker = chunk.changeTracker(cids[1]);

    // One-time Mut<Velocity> setup: tracker + tick are stable for this chunk.
    Mut velMut = muts[1];
    velMut.setContext(velTracker, tick);

    // Hoist instance, services, and mut refs to locals.
    MySystems inst   = (MySystems) this.inst;
    Res<Dt>   dt     = (Res<Dt>)   this.services[2];
    // velMut is already a local from above.

    // --- inner loop ---
    for (int slot = 0; slot < count; slot++) {
        Position pos = (Position) posArr[slot];

        velMut.resetValue((Velocity) velArr[slot], slot);

        inst.update(pos, velMut, dt);   // <- invokevirtual, inlineable

        Record flushed = velMut.flush();
        velArr[slot] = flushed;
    }
}
```

The real emitted bytecode differs only in that it uses explicit
local-variable slots (`firstStorageVar`, `firstTrackerVar`,
`mutLocal[i]`, etc.) and raw `aaload` / `aastore` instructions. Read
the comments on lines ~289–343 of `GeneratedChunkProcessor.java` for
the exact slot layout.

## Why every argument hoists to a local

The generator could load `this.inst`, `this.services[i]`, and
`this.muts[i]` per iteration. It explicitly does not. The comment on
lines ~441–451 of the chunk generator spells out the reason:

> Hoist per-instance field loads out of the loop. Without this, every
> iteration does:
>
>     aload0; getfield inst; checkcast systemClass
>     aload0; getfield services; ldc i; aaload; checkcast P
>     aload0; getfield muts; ldc i; aaload
>
> The JIT's loop-invariant code motion can't hoist these because the
> fields are `public` non-final and the invokevirtual to the user body
> is treated as a potential side effect. Hoisting them here in
> bytecode makes the hot loop load from locals only.

This is tier-1's single biggest win over "hand-write the loop in
Java." A Java author cannot reach into bytecode to mark loop-invariant
field reads, and the JIT's own LICM is too conservative when a
`invokevirtual` is in the loop body: it cannot prove the user body
doesn't rewrite the public fields. The generator can.

Every generator does the same trick for its own shape:

- `GeneratedChunkProcessor` hoists `inst`, each `services[i]`, each
  `muts[i]`, and the chunk's raw `entityArray()`.
- `GeneratedPairIterationProcessor` hoists all of the above *plus* the
  relation store's `forwardKeysArray()` / `forwardValuesArray()` and a
  per-source archetype cache tuple.
- `GeneratedExclusiveProcessor` unrolls the argument loading (one
  `aload args; ldc i; aaload; checkcast P` per parameter) before a
  single `invokevirtual` — no loop, just straight-line args-fill.

## Direct `invokevirtual` to the user method

The final instruction in the inner loop is an
`invokevirtual` (or `invokestatic` for static methods in the exclusive
generator) at a **single call site** that refers directly to the
user method by its concrete descriptor, not through a
`MethodHandle`:

```java
cb.invokevirtual(systemClassDesc, method.getName(), systemMethodDesc);
```

Because the generated class is a nestmate of the system class, the
JVM lets this target a package-private method. Because the class is
hidden, the call site exists exactly once — the JIT's profile for
that site is unambiguous, and the inliner will fold the user method
body into the generated loop with no megamorphic / polymorphic
penalty. The same method on the same system will typically end up as
a single compiled code blob shared between the user body and the
iteration scaffolding, at which point the whole thing is fused with
the chunk's raw component arrays into one tight inner loop.

That is as close as a JVM can get to an imperative C iteration over a
flat array.

## `@Write Mut<T>` and per-chunk `setContext`

Write parameters deserve a dedicated note. When the user declares
`@Write Mut<Velocity> vel`, the framework wants three things:

1. The user body receives a working `Mut<Velocity>` bound to the
   current entity's storage slot.
2. When the body calls `vel.set(new Velocity(...))`, that allocation
   is captured and tagged for write-back.
3. When the body returns, any tagged write is flushed to the storage
   array *and* the `ChangeTracker` for `Velocity` is marked dirty at
   the right slot with the current tick.

The naive way to implement this is one `Mut<T>.reset(value, slot,
tracker, tick)` call per entity per write param. The tracker and
tick are stable across every slot in the chunk, which is wasteful.
The generator splits `Mut.reset` into two calls:

- `setContext(tracker, tick)` — called **once per chunk**, hoisted
  into the preamble. Wires the stable tracker + tick.
- `resetValue(value, slot)` — called **once per slot** in the inner
  loop. Updates just the varying pieces.

Look at lines ~382–394 of `GeneratedChunkProcessor.java`:

```java
// Per-chunk Mut setup: tracker and tick are stable across every
// entity in this chunk, so push them into each Mut once here
// instead of passing them as args to reset() per entity.
```

The pair-iteration generator does the same thing at the per-source
level rather than per-chunk, because the source is the outer loop:
`setContext` runs once per source, `resetValue` once per source's
pair.

## Flat `Record[]` from `DefaultComponentStorage.rawArray()`

One more detail that shows up in benchmarks:
`DefaultComponentStorage` exposes its internal `Record[]` backing
array via `rawArray()`, and when the world uses the default storage
factory, the generator loads that array directly:

```java
cb.invokevirtual(chunkDesc, "componentStorage",
    MethodTypeDesc.of(storageDesc, compIdDesc));
if (useDefaultStorageFactory) {
    cb.checkcast(defaultStorageDesc);
    cb.invokevirtual(defaultStorageDesc, "rawArray", rawArrayDesc);
}
cb.astore(firstStorageVar + i);
```

With `rawArray`, per-entity component access is a literal
`aaload` / `aastore` against a `Record[]` local. Without it, every
access goes through `invokeinterface` on the `ComponentStorage<T>`
interface. The difference is small on a single read but amplifies
through the inner loop, because `invokeinterface` forces the JIT to
look up the vtable even though the single call site makes the target
obvious.

!!! tip "Default on by default"
    The `useDefaultStorageFactory` flag is on by default for
    world-level builds. You lose the raw-array fast path only if you
    plug in a custom `ComponentStorage.Factory` — which the library
    supports, but which has never shown up in a realistic user.
    Opting out is a conscious choice, not an accident.

## Where tier-1 bails out

The complete list of bail-outs is in
[reference/tier-fallbacks](../reference/tier-fallbacks.md). At a
glance, the chunk processor requires:

- Between 1 and 4 component parameters
- At most 8 total parameters
- No `@Where` field filters (move the predicate into the method body)
- No `@Filter(Removed)` (use `RemovedComponents<T>` instead — a
  service parameter, which is tier-1 compatible)

The pair-iteration generator has similar caps: at most 4 source
reads, at most 2 source writes, at most 2 target reads, and the
system method must be non-static. The exclusive generator has no
parameter caps — it handles static and instance methods and any
service parameter count.

None of these caps are fundamental. They're emission-complexity
choices: the bytecode generator pre-allocates local-variable slot
tables at compile time and chose numbers that cover every realistic
system shape the benchmark suite crosses. Widening any of them is a
mechanical edit in the emission code, not a design change.

!!! warning "Services never cause a bail-out"
    A common misreading of the tier-1 caps is "don't take too many
    services." Services (`Res`, `ResMut`, `Commands`, `Entity`,
    `RemovedComponents<T>`, `PairReader`, `EventReader`, `Local<T>`,
    `World`, etc.) never cause a tier-1 bail by themselves. The only
    way services contribute is by pushing `params.length` past 8 in
    the chunk processor. If you need 20 services, put them behind a
    single `Res<Bundle>` record.

## Falling back: tier-2

When the generator returns `null` from `tryGenerate`, the plan falls
through to a tier-2 implementation:

| Tier-1 | Tier-2 |
|---|---|
| `GeneratedChunkProcessor` | `SystemExecutionPlan.processChunk` + `SystemInvoker` |
| `GeneratedPairIterationProcessor` | `PairIterationProcessor` |
| `GeneratedExclusiveProcessor` | `SystemInvoker.invoke` in `World.executeSystem` |

Tier-2 is not slow in absolute terms. It pays for a
`MethodHandle.asSpreader(Object[])` dispatch (~20–40 ns per call
after warmup), plus one `Object[]` argument array per iteration that
the plan reuses so there is no allocation on the hot path. The plan
still caches per-chunk storage and tracker references in
`SystemExecutionPlan.prepareChunk` so the fill/invoke/flush inner
body is allocation-free. What it does not get is JIT inlining of the
user body into the iteration loop.

On the 500 × 2000 predator/prey cell, the delta between tier-1 and
tier-2 is somewhere between 2 and 6 µs per tick depending on how hot
the system is. On a tight iteration micro over 100k entities it's
proportionally larger.

## Related

- [Reference — Tier-1 vs tier-2 dispatch](../reference/tier-fallbacks.md)
  — the full catalogue of shapes that land on tier-2 and how to lift
  each one
- [Architecture](architecture.md) — the chunk layout the generated
  loop reads from
- [Change tracking](change-tracking.md) — how `@Filter(Added/Changed)`
  emits its own sparse path inside the same generator
- [Optimisation journey](optimization-journey.md) — how tier-1
  `@ForEachPair` and tier-1 `@Exclusive` came to exist
- [Tutorial — Systems](../tutorials/basics/03-systems.md) — the
  user-facing API for writing the methods this page makes fast
