# Valhalla investigation

**What you'll learn:** what JEP 401 (value records) actually delivers
for a real-world ECS workload today, where the wins land, where the
regressions land, what the explicit flat-array opt-in does and why
it's a regression on the current EA JIT, and what `@LooselyConsistentValue`
is doing in the storage layer. This page is the narrative; the
numbers live in [benchmarks/valhalla](../benchmarks/valhalla.md).

## The premise

Every component in japes is a Java `record`, which means the backing
`ComponentStorage` is a reference array. Reading a `Position` is a
pointer chase (array index → heap record → field load), and writing
one allocates a fresh heap record. JEP 401 promises flat layout for
`value record`: the same backing array becomes a primitive-backed
region, loads become indexed struct reads, and stores are plain
array writes with no allocation.

That's the premise. The question is: how much of that promise
actually shows up on the EA JIT today?

The `ecs-benchmark-valhalla` Gradle module ports every japes
benchmark to `value record` components and runs them on an EA build
of JDK 27 with JEP 401 preview. Same Java source, same
`--enable-preview`, same JMH settings, same tier-1 generator — only
the component declarations and the runtime JVM differ.

## Where the wins are

The read-heavy iteration micros. Numbers from
[benchmarks/valhalla](../benchmarks/valhalla.md):

| Benchmark | Stock | Valhalla EA | Ratio |
|---|---:|---:|---:|
| `iterateSingleComponent` 100k | 37.5 µs | 9.31 µs | **4.03× faster** |
| `iterateTwoComponents` 100k | 67.3 µs | 20.0 µs | **3.37× faster** |
| `iterateSingleComponent` 10k | 2.36 µs | 1.06 µs | 2.23× faster |
| `iterateTwoComponents` 10k | 4.22 µs | 1.85 µs | 2.28× faster |

This is the JEP 401 flat-array layout paying off exactly where it
should: sequential dense iteration over a primitive-backed column.
The tier-1 generator's tight chunk loop inlines cleanly on top of
it, and the JIT can scalar-replace the short-lived `Position`
values because — thanks to the value-record declaration — every
instance is provably interchangeable with every other bit-pattern-
equal instance, which is the license the escape analyser needs to
fold them into registers.

!!! warning "DCE trap"
    An earlier revision of the japes iteration benchmarks had empty
    system bodies — `void iterate(@Read Position p) {}`. Under
    Valhalla that allowed the JIT to prove the loaded record was
    unused and delete the whole loop, producing bogus "20–80×"
    speedups. Every japes read system now consumes its input
    through a JMH `Blackhole` — `bh.consume(pos)` — which is opaque
    to the JIT and preserves the loads. The numbers above are real
    numbers. If you see a Valhalla benchmark claiming
    order-of-magnitude iteration speedups that isn't using
    `Blackhole`, double-check that it's not being DCE'd.

## Where the wins are modest

The write-heavy integration loops:

| Benchmark | Stock | Valhalla EA | Ratio |
|---|---:|---:|---:|
| `iterateWithWrite` 100k | 576 µs | 536 µs | 1.07× |
| `NBody simulateOneTick` 10k | 62.5 µs | 57.3 µs | 1.09× |
| `NBody simulateTenTicks` 10k | 625 µs | 577 µs | 1.08× |

~7–10 %. Real, repeatable, but modest. These workloads write back
a new `Position` per entity per tick. Under Valhalla, a value
record `Position` *should* scalar-replace cleanly through the
tier-1 inner loop, turning the allocation into register stores.
The EA JIT does some of that work but hits a wall at the
`World.setComponent` / `ComponentStorage.set(slot, value)` boundary
— those APIs declare their value parameter as `Record`, and the
JVM is forced to box the value record into a heap wrapper crossing
the erased boundary even though the storage layer is value-aware.

The write tax is discussed in more depth on the
[write-path tax page](write-path-tax.md). The short version: the
API shape leaves a boxing opportunity that Valhalla alone cannot
close.

## Where it regresses

Scenario benchmarks that exercise `setComponent` heavily still sit
on the regression side:

| Benchmark | Stock | Valhalla EA | Delta |
|---|---:|---:|---:|
| `ParticleScenario` 10k | 161 µs | 180 µs | 12 % slower |
| `SparseDelta` 10k | 1.85 µs | 1.96 µs | 6 % slower |
| `RealisticTick st` 10k | 5.76 µs | 11.9 µs | 52 % slower |
| `RealisticTick mt` 10k | 10.3 µs | 17.8 µs | 42 % slower |

Two rounds of PR-review fixes have narrowed these gaps
substantially. GC profiling still shows Valhalla allocating **~2×**
more per op on the scenario benchmarks than stock — the residual
comes from value records crossing the erased `Record` boundary of
`World.setComponent`, which forces heap-wrapper boxing.
`RealisticTick`, which has three observers and three mutation
sites, amplifies the tax three ways.

`SparseDelta` at 6 % slower is narrow: the bottleneck is
change-tracker bookkeeping, not component reads, so there's nothing
for Valhalla to flatten and the regression is just the boxing cost
on 300 writes per tick.

## The explicit flat-array opt-in

JEP 401 EA exposes an experimental flat-array allocator,
`jdk.internal.value.ValueClass.newNullRestrictedNonAtomicArray(Class, int, Object)`,
plus a class-level `@jdk.internal.vm.annotation.LooselyConsistentValue`
annotation. `DefaultComponentStorage` wires both into its static
initialiser (`ecs-core/.../storage/DefaultComponentStorage.java`
lines ~27–60), gated behind `-Dzzuegg.ecs.useFlatStorage=true`:

```java
if (Boolean.getBoolean("zzuegg.ecs.useFlatStorage")) {
    try {
        var cls = Class.forName("jdk.internal.value.ValueClass");
        var lookup = MethodHandles.lookup();
        newFlat = lookup.findStatic(cls, "newNullRestrictedNonAtomicArray",
            MethodType.methodType(Object[].class, Class.class, int.class, Object.class));
        isCompat = lookup.findStatic(cls, "isValueObjectCompatible",
            MethodType.methodType(boolean.class, Class.class));
    } catch (Exception ignored) { /* stock JDK fallback */ }
}
```

When the opt-in is active, the storage constructor probes the
declared record type with `isValueObjectCompatible`, builds a
canonical zero-initialised prototype (see
`canonicalZeroInstance` at line ~118 — it walks the record
components, constructs a primitive-zero for each, and reflects a
constructor call), and asks the JVM for a null-restricted flat
array. In-process verification confirms the resulting array reports
`ValueClass.isFlatArray(arr) == true`.

### It is currently a regression

Same A/B harness, same JVM, only the opt-in toggled:

| Benchmark | Flat OFF | Flat ON | Delta |
|---|---:|---:|---:|
| `iterateTwoComponents` 10k | 1.79 µs | 6.18 µs | **3.4× slower** |
| `iterateTwoComponents` 100k | 18.4 µs | 64.3 µs | **3.5× slower** |
| `RealisticTick st` | 14.0 µs | 16.3 µs | 16 % slower |
| `SparseDelta` | 2.57 µs | 2.49 µs | noise |

The flat-array path exists, and it is structurally correct — the
storage constructor verifies `isFlatArray` is true, and the
`swapRemove` path handles the null-restricted contract by writing
the canonical zero prototype into vacated slots instead of `null`
(a null-restricted array rejects null writes, see
`DefaultComponentStorage.swapRemove` lines ~162–178):

```java
if (flat) {
    // Null-restricted arrays reject null writes. Overwrite the
    // now-vacant slot with the canonical zero value so its contents
    // are deterministic ...
    data[lastIndex] = zeroPrototype;
} else {
    data[lastIndex] = null;
}
```

What's missing is the JIT optimisation on the access path. The
EA JIT has not yet emitted fast-path code for flat-array `aaload` /
`aastore` equivalents — the access goes through a slower code path
than the reference-array fallback, which the JIT has had longer to
optimise. Every real Valhalla win above comes from the
**reference-array** path, where the JIT scalar-replaces short-lived
value record instances through escape analysis and the backing
layout's flat-ness is irrelevant.

The opt-in is correct code. It will become the right default once
the Valhalla JIT's flat-array path catches up with its reference-
array path. Until then, it stays gated behind the system property
and the default is reference arrays.

!!! info "`@LooselyConsistentValue` on component records"
    The Valhalla benchmark module declares its components with
    `@LooselyConsistentValue` — a JEP 401 annotation that relaxes
    atomic-publication semantics for the value class. For a
    single-writer component this is safe: we never observe a torn
    read because nothing concurrently writes the same slot. The
    annotation grants the JIT license to keep the value in flat
    form across potentially-unsafe publication points, which is
    what lets the layout actually be flat on the reference-array
    path. Without the annotation, JEP 401 falls back to the default
    atomic publication model and the flat layout license is
    forfeited.

## The predator/prey cell under Valhalla

The relations benchmark (`PredatorPreyForEachPairBenchmarkValhalla`,
in `ecs-benchmark-valhalla`) ports the workload to
`@LooselyConsistentValue value record Position`, `Velocity`,
`Predator`, `Prey`. The `Hunting` relation payload stays a plain
`record` because it lives in `TargetSlice.values`, an `Object[]`
inside the relation store — there is nothing to flatten on the
payload side. Same scheduler, same `@ForEachPair` dispatch, same
tier-1 generator.

Two things jumped out of the measurement (full table on the
[benchmarks/valhalla](../benchmarks/valhalla.md) page):

**Value-record + reference-array storage is essentially a tie with
stock.** Declaring `Position` / `Velocity` as value records while
keeping the backing storage a plain reference array costs between
0 and 13 % across every cell — inside the JMH error bars at most
cells. The pursuit inner body is so tight (two reads, one write,
one payload read, one `invokevirtual`) that the tier-1 generator
already lets the JIT scalar-replace short-lived `Position` /
`Velocity` instances on stock JDK 26. There is nothing left for
value semantics to recover on this specific workload.

**Flat-array storage is a 1.4×–3.7× regression** at every grid
cell. The absolute overhead scales with predator count, not with
prey count — fingerprinting the regression as per-pair component
access cost. Roughly **+13 ns per access** above the reference-
array fast path.

## Honest summary

- **Reads: ~3× faster at 100k on Valhalla EA.** The biggest single
  gain in the whole benchmark suite. Real, repeatable, not a DCE
  artefact.
- **Writes: ~7–10 % faster on dense integration.** Real but modest.
  Blocked from being bigger by erased-parameter boxing at the
  `World.setComponent` boundary.
- **Scenario benchmarks with heavy `setComponent` traffic: still
  a net regression**, though narrowing over each PR round. The
  residual is the same boxing issue.
- **Explicit flat-array opt-in: currently a regression**, because
  the EA JIT's flat-array access path is unoptimised. Kept as
  opt-in code for the future.

## Where to look in the source

- `DefaultComponentStorage.java` lines ~27–60 — the static
  initialiser that probes `jdk.internal.value.ValueClass` and
  resolves the flat-array `MethodHandle`s if the opt-in is active.
- `DefaultComponentStorage.java` lines ~72–110 — the constructor
  that optionally builds a canonical zero prototype and allocates
  a null-restricted flat array.
- `DefaultComponentStorage.java` lines ~118–137 — the zero-
  prototype builder. Rejects record types with non-primitive
  components (can't synthesise a default for them).
- `DefaultComponentStorage.java` lines ~162–178 — `swapRemove`
  branching on `flat` to avoid writing `null` into a null-
  restricted array.

The probe and allocator paths are stock JDK safe: on a non-Valhalla
JVM, the `Class.forName` throws and the `MH_NEW_FLAT_ARRAY` handles
stay `null`, and the storage constructor falls through to
`Array.newInstance`. The opt-in is pure EA-only code; the
production path is unchanged on stock JDKs.

## Related

- [Benchmarks — Valhalla](../benchmarks/valhalla.md) — full tables
  for every row referenced above
- [Write-path tax](write-path-tax.md) — why writes win less than
  reads and where the residual cost is
- [Architecture](architecture.md) — `DefaultComponentStorage`'s
  role in the storage layer
- [Tier-1 bytecode generation](tier-1-generation.md) — what the
  generator emits on top of the backing array (whether flat or
  reference)
- [Optimisation journey](optimization-journey.md) — where the
  stock numbers above came from
