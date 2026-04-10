# 2026-04-10 — Profile-guided perf pass, Valhalla flat-array probe, SoA experiment

Session log for picking back up later. Captures where we ended and the open
decision.

## TL;DR

Four sequential wins on stock japes (2× cumulative speedup on
`RealisticTickBenchmark`), then a Valhalla deep-dive that identified
`DefaultComponentStorage`'s `Object[]` backing as the root cause of the
scenario regression, tested the JEP 401 EA flat-array opt-in (made things
worse — EA JIT not ready), then a hand-written SoA prototype that closes
the Valhalla scenario regression entirely (37 % faster on that JVM) but
regresses stock JDK 26 by 10 %. **Then a third round of setComponent
perf fixes** (array-indexed chunk lookups, setComponent chunk
consolidation, ClassValue) that landed another 1.7× on RealisticTick
and made `SparseDelta` **2.16× faster than Bevy**. Total session
speedup on setComponent-heavy workloads: **2.83×**. **Decision point
still open: whether to ship a `SoAStorageGenerator`, keep SoA as a
benchmark-only recipe, or extend the tier-1 generator to emit
primitive field loads directly.**

## Commits this session (oldest → newest, all on `main`)

| sha | subject |
|---|---|
| `4614731` | perf(core): profile-guided hot-path optimizations (ArchetypeId cached hash + findMatching generation cache + one-lookup setComponent + Schedule.orderedStages cache) |
| `59e1e90` | bench(iteration): consume reads via Blackhole + refresh every cross-JVM table |
| `0b9aeb0` | perf(core): direct Archetype reference on EntityLocation |
| `6de1fb5` | bench(sparse-delta): stop pre-sizing dirty buffers + refresh all tables |
| `70ba5b2` | perf(storage): opt-in flat-array via jdk.internal.value.ValueClass; A/B against EA JIT |
| `d731a55` | bench: SoA (struct-of-arrays) prototype for RealisticTick — JVM-dependent win |
| `069631a` | docs: session note — profile-guided perf pass, Valhalla probe, SoA experiment |
| `355c177` | perf(core): chunk-level array-indexed lookups + setComponent consolidation |

## Current state (as of `d731a55`)

All tests pass. Benchmarks build against stock JDK 26 and Valhalla EA
JDK 27 (`~/.sdkman/candidates/java/valhalla-ea` — `openjdk 27-jep401ea3`).

### Key numbers (µs/op, lower is better, 10 000 entities)

| benchmark | bevy | japes | japes-v | zay-es | dominion | artemis |
|---|---:|---:|---:|---:|---:|---:|
| iterateSingleComponent 10k | 2.11 | **2.37** | **1.03** | 28.1 | 7.22 | 4.67 |
| iterateSingleComponent 100k | 21.0 | **38.7** | **10.6** | 382 | 81.8 | 164 |
| iterateTwoComponents 10k | 3.35 | **6.12** | **1.93** | 37.4 | 12.4 | 11.5 |
| iterateTwoComponents 100k | 33.3 | **69.9** | **18.7** | 519 | 129 | 230 |
| iterateWithWrite 10k | 6.18 | **58.2** | **52.0** | 1910 | 22.6 | 18.4 |
| NBody simulateOneTick 10k | 8.79 | **62.8** | **57.3** | 444 | 23.9 | 19.2 |
| ParticleScenario tick | 22.4 | **149** | **186** | 1777 | 68.7 | 98.3 |
| SparseDelta tick | 4.01 | **1.86** | **2.60** | 4.60 | 0.37 | 0.26 |
| RealisticTick tick (st) | — | **5.79** | **14.0** | — | 41.7 | 24.7 |
| RealisticTick tick (mt) | — | **10.49** | **19.1** | — | 18.9 | 12.9 |

> Numbers updated after the round-3 fixes in commit `355c177`
> (Chunk array-indexed storagesById / changeTrackersById; setComponent
> folds two chunk lookups into one; ComponentRegistry uses ClassValue).

- **japes SparseDelta at 1.86 µs** is the fastest library change-detection path in the whole comparison — **2.16× faster than Bevy** (4.01).
- **japes RealisticTick st at 5.79 µs** beats Artemis's fastest `mt` configuration (12.9 µs) by 2.2× on the same workload — by total CPU cost japes `st` is 6.7× cheaper than Artemis `mt`.
- **Valhalla iterateTwoComponents 100k at 18.7 µs vs stock's 69.9 µs** — the biggest single cross-JVM number in the README (3.74× real win, Blackhole-proof).

### The four perf fixes in the first pass (cumulative 2×)

Profile of the pre-optimization `RealisticTickBenchmark` showed:
- **18 % of CPU** in `AbstractSet.hashCode` → `TreeSet.iterator` → `HasherSpecializer.hasher0`
- Reached via `archetypeGraph.get(location.archetypeId())` inside `World.setComponent`
- Called ~300×/tick on this workload

Fixes applied in order:

1. **Cached `ArchetypeId.hashCode`** — converted the record to a final class with a lazy `Integer cachedHash`, plus a cached-hash short-circuit in `equals()` so distinct archetypes colliding in a HashMap skip the full `TreeSet.equals` traversal. Single biggest win of the session.
   - `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeId.java`
2. **Generation-based `findMatching` cache on `SystemExecutionPlan`** — `ArchetypeGraph` bumps a `long generation` whenever `getOrCreate()` materialises a new archetype; each plan remembers its last `findMatching` result along with the generation at which it was cached. `World.executeSystem` compares two longs instead of hashing a `Set<ComponentId>` on every tick.
   - `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeGraph.java`
   - `ecs-core/src/main/java/zzuegg/ecs/system/SystemExecutionPlan.java`
3. **One-lookup `setComponent`** — added `ComponentRegistry.getOrRegisterInfo(type)` that returns the whole `ComponentInfo` in one HashMap lookup, so `World.setComponent` no longer pays for both `getOrRegister(class)` and `info(class)` on the same key.
   - `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java`
4. **Cached `Schedule.orderedStages()`** — was doing `List.copyOf(entrySet())` on every `World.tick()`. Noise-level but removes an allocation.
   - `ecs-core/src/main/java/zzuegg/ecs/scheduler/Schedule.java`

Then a second follow-up:

5. **Direct `Archetype` reference on `EntityLocation`** — replaced `EntityLocation(ArchetypeId, int, int)` with `EntityLocation(Archetype, int, int)` so `setComponent`/`getComponent`/`addComponent`/`removeComponent`/`despawn` skip the `archetypeGraph.get(archetypeId)` lookup entirely. Another 18 % on top.
   - `ecs-core/src/main/java/zzuegg/ecs/archetype/EntityLocation.java`
   - `ecs-core/src/main/java/zzuegg/ecs/archetype/Archetype.java`
   - `ecs-core/src/main/java/zzuegg/ecs/world/World.java`

Cumulative result (after fixes 1–5):
- `RealisticTickBenchmark.tick st`: 16.4 → **8.07 µs/op** (2.03×)
- `SparseDeltaBenchmark.tick`: 5.22 → **2.56 µs/op** (2.04×)
- `ParticleScenarioBenchmark.tick`: ~161 → 157 µs/op (1.03×)
- `NBodyBenchmark` / `IterationBenchmark`: within noise (tier-1 direct
  array path doesn't hit the fixed slow path)

### Round three — chunk-level lookups (commit `355c177`)

After the session was supposed to end, another profile pass chased
`setComponent`'s remaining cost. Three more fixes + a failed
experiment:

6. **`Chunk.storagesById` / `changeTrackersById` — flat `Object[]`
   indexed by `ComponentId.id()`**. Replaced both
   `HashMap<ComponentId, ...>` lookups in the setComponent hot path
   (`storages.get(id)` and `changeTrackers.get(id)`) with a single
   array load. Sized to maxGlobalComponentId + 1 per chunk, slots for
   absent components stay null. Biggest win of this round.
   - `ecs-core/src/main/java/zzuegg/ecs/storage/Chunk.java`

7. **`World.setComponent` folds two chunk lookups into one**. The
   previous shape did `archetype.set(compId, location, component)`
   (which did its own `chunks.get(chunkIndex)`) and then a *second*
   `archetype.chunks().get(chunkIndex)` for the tracker update.
   Consolidated: grab the chunk once at the top, use it for both
   the store and the `markChanged`.
   - `ecs-core/src/main/java/zzuegg/ecs/world/World.java`

8. **`ComponentRegistry.getOrRegisterInfo` via `ClassValue`**.
   `ClassValue.get` has a HotSpot JIT intrinsic that folds to a
   direct field load on the `Class` object; previously this path
   went through `HashMap<Class<?>, ComponentInfo>.get`. Measurement
   was noise but the pattern is cleaner.
   - `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java`

**Reverted experiment — chunk reference on EntityLocation.** Tried
`EntityLocation(Archetype, Chunk, int slotIndex)` to replace the
chained lookup `archetype.chunks().get(chunkIndex)` with a single
field load. **Regressed RealisticTick by 9%** (5.80 → 6.30 µs/op).
Hypothesis: the archetype is stable across the benchmark's
setComponent loop, so the JIT was CSE-ing `archetype.chunks().get(0)`
*out of the loop* and effectively hoisting the chunk lookup to
once-per-loop. With a per-entity chunk field the reference varies
and prevents the hoist. A comment in `EntityLocation.java` now
records the finding so nobody re-tries it.

**Cumulative after round 3:**
- `RealisticTickBenchmark.tick st`: 16.4 → **5.79 µs/op** (**2.83×**)
- `RealisticTickBenchmark.tick mt`: 21.5 → **10.49 µs/op** (2.05×)
- `SparseDeltaBenchmark.tick`: 5.22 → **1.86 µs/op** (**2.81×**)
  — **2.16× faster than Bevy**
- `ParticleScenarioBenchmark.tick`: ~161 → ~149 µs/op (1.08×)

Profile confirmation: the setComponent call chain is now fully
inlined into the benchmark's tick method frame (42% of stack
samples, indivisible). All framework-level hot spots are under 2%.
The remaining cost is the raw work of 300 sparse mutations + 3
observer sweeps + scheduler dispatch.

## Iteration read Blackhole fix

User caught that `IterationBenchmark.iterateSingleComponent` /
`iterateTwoComponents` had empty system bodies (`void iterate(@Read
Position p) {}`), letting the JIT escape-analyse the load to dead code
and delete the whole loop. The previously-reported "20–80× Valhalla
speedups" on read-only micro benchmarks were pure DCE.

Fix: static `Blackhole` fields on the read systems, assigned from the
`@Benchmark` method, called via `bh.consume(pos)`.

**Real Valhalla numbers that came out:**

| iteration | japes | japes-v | real Δ |
|---|---:|---:|---:|
| single 10k | 2.37 | **1.03** | **2.30×** |
| single 100k | 38.7 | **10.6** | **3.66×** |
| two 10k | 6.12 | **1.93** | **3.17×** |
| two 100k | 69.9 | **18.7** | **3.74×** |

This is the biggest cross-JVM win in the README. It comes from the
**reference-array** path (value records stored in `Object[]` with
escape analysis + scalar replacement), not the flat-array path —
see the Valhalla section below.

Files:
- `benchmark/ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/IterationBenchmark.java`
- `benchmark/ecs-benchmark-valhalla/src/jmh/java/zzuegg/ecs/bench/valhalla/micro/IterationBenchmarkValhalla.java`

## Valhalla / JEP 401 EA investigation

### The flat-array opt-in does exist

Found in `jdk.internal.value.ValueClass` (qualified-exported to
`jdk.unsupported`). Four array allocators:

```java
public static native Object[] newNullRestrictedAtomicArray(Class<?>, int, Object);
public static native Object[] newNullRestrictedNonAtomicArray(Class<?>, int, Object);  // most flat
public static native Object[] newNullableAtomicArray(Class<?>, int);
public static native Object[] newReferenceArray(Class<?>, int);                         // current default
public static native boolean isFlatArray(Object);
```

Required JVM flags:
```
--add-exports java.base/jdk.internal.value=ALL-UNNAMED
--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED
```

### The opt-in has a second half nobody advertises

Calling `newNullRestrictedNonAtomicArray(Position.class, n, proto)` on a
plain `value record` returns `isNullRestrictedArray=true` but
`isFlatArray=false`. The VM accepts the call but doesn't flatten.

The missing piece: **`@jdk.internal.vm.annotation.LooselyConsistentValue`**
on the value class itself. Adding it makes the same allocator return
`isFlatArray=true`.

Also found `@jdk.internal.vm.annotation.NullRestricted` (field-level, for
flat embedding of value types inside enclosing classes). Not used here.

Probe that verified this:
`/tmp/flat-probe/FlatProbe.java` (regenerable — the code lives in the
commit message of `70ba5b2` if needed).

### But enabling the flat path *regresses* performance on EA JDK 27

A/B on the same JVM, same benchmark jar, same JMH flags, only the
`-Dzzuegg.ecs.useFlatStorage` system property changing:

| benchmark | flat OFF | flat ON | Δ |
|---|---:|---:|---:|
| iterateTwoComponents 10k | 1.79 | 6.18 | **3.4× slower** |
| iterateTwoComponents 100k | 18.4 | 64.3 | **3.5× slower** |
| RealisticTick st | 14.0 | 16.3 | 16 % slower |
| SparseDelta | 2.57 | 2.49 | noise |

The EA JIT hasn't emitted optimised get/set code for flat null-restricted
arrays yet. All of the real Valhalla wins reported in the README come
from the **reference-array fallback** — where escape analysis and scalar
replacement on value records already work on the mature code path.

### `DefaultComponentStorage` already knows how to do both

The storage lives at `ecs-core/src/main/java/zzuegg/ecs/storage/DefaultComponentStorage.java`
and has:
- reflective probe of `jdk.internal.value.ValueClass` via `Class.forName`
- `canonicalZeroInstance(type)` that builds the null-restricted prototype
  via the record's canonical constructor
- `flat` / `zeroPrototype` fields so `swapRemove` avoids writing null
  into a null-restricted array
- gated by `-Dzzuegg.ecs.useFlatStorage=true` — **off by default**
- gated by `-Dzzuegg.ecs.debugFlat=true` — prints per-storage flat/non-flat state at init

When the Valhalla JIT's flat-array path matures, flipping the default
is a one-line change.

### Root cause of the remaining Valhalla scenario regression

GC profile of `RealisticTickBenchmarkValhalla` (without SoA):

- Stock japes: 6 085 B/op, 15.3 µs/op
- Valhalla japes: **13 349 B/op**, 23.0 µs/op (2.2× the allocation, 50 % slower)

Stack sampling put `DefaultComponentStorage.get` / `set` at **18 % of
CPU under Valhalla, invisible under stock**. The culprit is that value
records cross the erased `Record` parameter of `World.setComponent` in
the scenario hot path, which forces the JVM to box the value into a
heap wrapper on the way in, and the JIT can't un-box it at the array
store because the call graph is too deep to inline through.

Fundamentally: the `ComponentStorage<T>` interface erases `T` to
`Record`, and the `World.setComponent(Entity, Record)` API boundary
takes an erased `Record`. Under stock Java this is fine — the reference
flows from caller to storage as a single heap reference. Under
Valhalla with value records, it forces a box at the API boundary that
the current EA JIT can't fuse out.

## SoA prototype — the experiment at the end of the session

User asked "can't we just directly access the storage without any help
from JIT?", pointing out that the whole value-type story exists to
avoid boxing that we could avoid ourselves by laying out primitives in
user space.

### What I built

Hand-wrote per-record SoA storages (one primitive array per record
component) for `RealisticTickBenchmark`'s four component types on both
stock and Valhalla. Each storage:
- On `set(slot, value)`: reads the record's accessor methods, stores
  primitives. Escape analysis kills the driver's `new Position(...)`
  once `set()` is inlined.
- On `get(slot)`: constructs `new Position(x[i], y[i], z[i])` — relies
  on scalar replacement in tight loops, allocates a real wrapper when
  the record escapes to a system method via the tier-1 invoke path.

Files:
- `benchmark/ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/scenario/RealisticTickBenchmarkSoA.java`
- `benchmark/ecs-benchmark-valhalla/src/jmh/java/zzuegg/ecs/bench/valhalla/scenario/RealisticTickBenchmarkValhallaSoA.java`

Both have a `@Param("storage")` of `default | soa` and one of `executor`
= `st` so they can be A/B'd on one JVM invocation.

Each benchmark owns its own `ComponentStorage.Factory` inner class that
dispatches on record type:

```java
public static final class SoAFactory implements ComponentStorage.Factory {
    @Override
    public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
        if (type == Position.class) return (ComponentStorage<T>) new PositionSoA(capacity);
        if (type == Velocity.class) return (ComponentStorage<T>) new VelocitySoA(capacity);
        if (type == Health.class)   return (ComponentStorage<T>) new HealthSoA(capacity);
        if (type == Mana.class)     return (ComponentStorage<T>) new ManaSoA(capacity);
        return ComponentStorage.create(type, capacity);
    }
}
```

And each per-type storage looks like:

```java
public static final class PositionSoA implements ComponentStorage<Position> {
    private final float[] x, y, z;
    PositionSoA(int capacity) { x = new float[capacity]; y = new float[capacity]; z = new float[capacity]; }
    @Override public Position get(int i) { return new Position(x[i], y[i], z[i]); }
    @Override public void set(int i, Position v) { x[i] = v.x(); y[i] = v.y(); z[i] = v.z(); }
    @Override public void swapRemove(int i, int count) {
        int last = count - 1;
        if (i < last) { x[i] = x[last]; y[i] = y[last]; z[i] = z[last]; }
    }
    // capacity / type / copyInto trivial
}
```

Using a custom `storageFactory` forces `useDefaultStorageFactory = false`,
which disables tier-1's raw-array fast path. The observer systems fall
back to `storage.get(slot)` via the interface — monomorphic here, so
the JIT devirtualises it cleanly.

### Results (10 000 entities, 1 % turnover, 100 dirty/comp/tick)

| JVM | storage | µs/op | alloc/op |
|---|---|---:|---:|
| Valhalla EA (jep401ea3) | default | 14.25 | 13.3 KB |
| Valhalla EA (jep401ea3) | **soa** | **9.01** | 16.5 KB |
| JDK 26 (stock) | default | **8.27** | 6.0 KB |
| JDK 26 (stock) | soa | 9.07 | 11.7 KB |

**Asymmetric finding:**
- **Valhalla: SoA is 37 % faster** and closes the scenario regression.
  Valhalla-SoA at 9.01 µs/op is within 12 % of stock-default at 8.07 µs/op.
- **Stock JDK 26: SoA is 10 % slower.** The existing `Object[]` storage
  already stores the caller's `new Position(...)` as a single live
  reference; SoA decomposes it into primitive stores AND allocates a
  fresh wrapper on every `get()` — net more allocation, not less, and
  the mature reference JVM's escape analysis on the default path wins.

The interesting surprise: **SoA allocates *more* per op on both JVMs
even though it removes the driver's allocation from storage.** The extra
comes from `get()`'s synthesized wrappers escaping into observer system
method bodies through the tier-1 invoke path. The JIT isn't
scalar-replacing them across the virtual call. On Valhalla the win
happens anyway because the baseline is worse; on stock the baseline is
already optimal and SoA's extra allocation drags it down.

### What this tells us

1. SoA-for-everything is **not a universal drop-in** replacement for
   `DefaultComponentStorage`. It's a targeted fix for the Valhalla EA
   scenario regression, specifically.
2. The ceiling on a SoA approach is "how aggressively does the JIT
   scalar-replace the synthesized wrapper record that flows through
   the tier-1 call chain into the system method?" On stock the
   answer is "aggressively enough that SoA's extra allocation hurts."
   On Valhalla the answer is "the alternative path is so much worse
   that SoA's extra allocation doesn't matter."
3. The right long-term fix is probably in the **tier-1 generator**: have
   the generated processor load primitive fields directly from the SoA
   storage and splat them into the system method's parameter slots
   without ever materialising a wrapper at all. That would make SoA a
   win on stock too. See "Option 3" below.

## Open decision — three options, increasing scope

### Option 1 — leave it as benchmarks only  (current state — commit `d731a55`)

No library changes. The two `RealisticTickBenchmarkSoA` /
`RealisticTickBenchmarkValhallaSoA` files document the technique. Users
running on Valhalla who hit the scenario regression can hand-roll SoA
storage for their own records. The README's "Does Valhalla help?"
section can be updated to point at the benchmark files as a reference
implementation of the workaround.

Cost: zero.
Benefit: the finding is captured and reproducible; nobody ever gets
SoA automatically.

### Option 2 — ship a `SoAStorageGenerator` using the classfile API

Auto-generate a specialised `ComponentStorage<T>` per record at world
build time. Opt-in via `World.builder().useSoAStorage()`.

- Scope: ~300–500 lines modelled on `GeneratedChunkProcessor` (which
  already uses `java.lang.classfile`).
- For each record component, emit a private field (primitive array for
  primitive components, `Object[]` for reference components).
- Emit `get(int)` that loads from each array and calls the record's
  canonical constructor.
- Emit `set(int, T)` that reads each record accessor and stores into
  the matching array.
- Emit `swapRemove`, `capacity`, `type`, `copyInto`.
- Fall back to `DefaultComponentStorage` for records with non-primitive
  components until we have a reason not to.

Cost: ~2–3 hours of careful codegen + testing.
Benefit: Valhalla users can toggle on SoA without per-record
boilerplate. The ~10 % stock regression remains, so SoA would be a
user-opt-in policy, not a default.

**Risk to think about before starting:** the `useDefaultStorageFactory
= false` flag in `WorldBuilder` currently disables the tier-1 raw-array
fast path any time a custom factory is installed. That means SoA would
lose the tier-1 win on reads. Two ways out: (a) add a sibling flag
`hasSoAStorage` so the tier-1 generator knows to emit interface calls
for reads but still inline through them, or (b) fold SoA awareness into
the tier-1 generator itself, which is option 3.

### Option 3 — SoA + tier-1 generator integration

The tier-1 generator currently loads the component storage, casts it to
`DefaultComponentStorage`, fetches `rawArray()`, and emits direct
`aaload` into the system method parameter slot.

For SoA storage, the equivalent would be to emit multiple primitive
field loads and call the record's canonical constructor *directly in
the generated bytecode*, placing the result on the stack as the
parameter. This bypasses `storage.get(slot)` entirely, never
materialises a wrapper except inside the inlined system method, and
lets the JIT's escape analysis work on plain bytecode rather than
crossing an interface call.

- Scope: option 2's codegen, plus ~300 additional lines in
  `GeneratedChunkProcessor` to detect SoA storages and emit field-wise
  loads.
- Would likely make SoA a win on stock JDK 26 too.
- Biggest blast radius — touches the tier-1 generator, which is already
  carrying most of the library's perf story.

Cost: ~a day of careful work plus benchmark churn.
Benefit: SoA becomes a plausible default, not a Valhalla-only opt-in.
`setComponent` allocations go to zero. Valhalla regression fixed on the
way without waiting for the EA JIT.

## My recommendation (at end of session)

Ship option 1 as-is. It's the current state, the README already tells
the Valhalla story honestly (reference-array fallback is the fast path,
flat-array opt-in is a regression), and the two SoA benchmark files
prove out the technique for any user who hits the regression in
production code.

Option 2 is the smallest step that turns the technique into a library
feature, and it's worth doing once we have a concrete use case that
justifies the "Valhalla users get a policy flag" framing. Until then
it's speculative library surface area.

Option 3 is ambitious enough that it deserves its own design doc before
we touch the tier-1 generator. The right trigger is "we have a user
reporting the setComponent allocation cost as their bottleneck on
stock japes" — at that point the ~day of work is obviously worth it.

## How to reproduce / pick this back up

```bash
# Stock JDK 26 full sweep (reproduces the main README tables):
./gradlew :benchmark:ecs-benchmark:jmhJar
java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  -rf json -rff /tmp/japes.json

# Valhalla EA full sweep:
./gradlew :benchmark:ecs-benchmark-valhalla:jmhJar
~/.sdkman/candidates/java/valhalla-ea/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar \
  -rf json -rff /tmp/valhalla.json

# SoA A/B on Valhalla (the "37% win" row):
~/.sdkman/candidates/java/valhalla-ea/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar \
  "RealisticTickBenchmarkValhallaSoA"

# SoA A/B on stock (the "10% regression" row):
java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-jmh.jar \
  "RealisticTickBenchmarkSoA"

# Flat-array opt-in A/B (demonstrates EA JIT regression):
~/.sdkman/candidates/java/valhalla-ea/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dzzuegg.ecs.useFlatStorage=true \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar \
  "RealisticTickBenchmarkValhalla"

# Debug: confirm which storages are flat
~/.sdkman/candidates/java/valhalla-ea/bin/java --enable-preview \
  --add-exports java.base/jdk.internal.value=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -Dzzuegg.ecs.useFlatStorage=true \
  -Dzzuegg.ecs.debugFlat=true \
  -jar benchmark/ecs-benchmark-valhalla/build/libs/ecs-benchmark-valhalla-jmh.jar \
  -wi 0 -i 1 -f 1 -r 1 \
  "RealisticTickBenchmarkValhalla" 2>&1 | grep "\[ecs\]"
```

## Files touched this session

Core perf fixes:
- `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeId.java`
- `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeGraph.java`
- `ecs-core/src/main/java/zzuegg/ecs/archetype/EntityLocation.java`
- `ecs-core/src/main/java/zzuegg/ecs/archetype/Archetype.java`
- `ecs-core/src/main/java/zzuegg/ecs/world/World.java`
- `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java`
- `ecs-core/src/main/java/zzuegg/ecs/scheduler/Schedule.java`
- `ecs-core/src/main/java/zzuegg/ecs/system/SystemExecutionPlan.java`
- `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeTest.java` (call-site updates)

Valhalla opt-in (off by default):
- `ecs-core/src/main/java/zzuegg/ecs/storage/DefaultComponentStorage.java` — reflective probe of `jdk.internal.value.ValueClass`, `canonicalZeroInstance` prototype builder, `flat` / `zeroPrototype` fields, `swapRemove` null-restricted fix, `-Dzzuegg.ecs.useFlatStorage` + `-Dzzuegg.ecs.debugFlat` gates
- `benchmark/ecs-benchmark-valhalla/build.gradle.kts` — `--add-exports` flags for compile + JMH
- `benchmark/ecs-benchmark-valhalla/src/jmh/java/zzuegg/ecs/bench/valhalla/*/*Valhalla.java` — `@LooselyConsistentValue` on every `value record`

Benchmark Blackhole fix:
- `benchmark/ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/IterationBenchmark.java`
- `benchmark/ecs-benchmark-valhalla/src/jmh/java/zzuegg/ecs/bench/valhalla/micro/IterationBenchmarkValhalla.java`

SparseDelta buffer honesty fix:
- `benchmark/ecs-benchmark-dominion/src/jmh/java/zzuegg/ecs/bench/dominion/DominionSparseDeltaBenchmark.java`
- `benchmark/ecs-benchmark-artemis/src/jmh/java/zzuegg/ecs/bench/artemis/ArtemisSparseDeltaBenchmark.java`

SoA prototype (this session's final experiment):
- `benchmark/ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/scenario/RealisticTickBenchmarkSoA.java`
- `benchmark/ecs-benchmark-valhalla/src/jmh/java/zzuegg/ecs/bench/valhalla/scenario/RealisticTickBenchmarkValhallaSoA.java`

README:
- `README.md` — every results table refreshed, "Does Valhalla help?"
  section rewritten around the real reads win + the counter-intuitive
  flat-array-opt-in regression.

## Things NOT in the repo but worth remembering

- **`/tmp/flat-probe/FlatProbe.java`** — standalone probe used to verify
  `@LooselyConsistentValue` was the missing piece for actual flattening.
  Can be regenerated from the body of commit `70ba5b2` if needed.
- **`/tmp/jmh-results/*.json`** — raw JMH JSON dumps from every sweep
  this session. Gone on next `/tmp` wipe.
- **`/tmp/jmh-results/extract.py`** — Python script that aggregates the
  JSON dumps into the README tables. Uses a dedup-by-newest-file trick
  so the same benchmark run through multiple sweeps resolves to the
  latest measurement.

If you want to preserve any of these for next session, move them under
`docs/notes/` — they're small enough.

## Where to resume

Pick one of the three options above. If option 2 or 3, start with a
design stub in `docs/notes/` and work out:

1. What triggers SoA? Explicit opt-in (`.useSoAStorage()`), auto-detect
   (all-primitive record → SoA, else default), or per-component policy?
2. How does tier-1 know whether a storage is SoA? An interface marker
   (`FlatPrimitiveStorage`), an instance check, or a separate generator
   path per storage kind?
3. How to test: unit tests for the generator, plus rerun the full
   cross-JVM sweep to confirm no regressions on stock.

Everything else is in the commits listed at the top of this document.
