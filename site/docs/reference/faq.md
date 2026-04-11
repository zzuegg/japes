# FAQ

Short answers. Follow the links for the deep dive.

## Do I need JDK 26?

Yes. japes uses the finalised `java.lang.classfile` API (JEP 484) to
emit per-system hidden classes, and the build targets JDK 26.
Earlier JDKs will not compile the library. See
[Installation](../getting-started/installation.md).

## How do I turn off the bytecode generator for debugging?

Call `useGeneratedProcessors(false)` on `WorldBuilder` before
`build()`. Every system then runs on the tier-2 reflective path,
which is easier to step through and produces shorter stack traces.
Verified in `ecs-core/.../world/WorldBuilder.java`.

## Why are components records?

Records give you value-type semantics for free — immutable fields,
generated `equals` / `hashCode`, predictable layout — which lets the
storage and change tracker treat every write as a whole-record swap.
It also makes Valhalla migration a one-line change once the JDK lands
value classes. See [Components](../tutorials/basics/01-components.md).

## Can I use non-record components?

No. `ComponentRegistry.register` throws if the type is not a record.
Wrap whatever you need in a `record` — it's usually a two-line change.

## Is japes thread-safe?

Systems declared in different stages run serially. Systems inside a
single stage can run in parallel on disjoint component sets —
`Commands` is safe to use from parallel systems and flushed at the end
of the stage. Manual cross-thread writes to components or resources
outside a system are not supported. See
[Multi-threading](../tutorials/advanced/12-multi-threading.md).

## Why is my system running on tier-2?

Cross-reference the `skipReason` string with the
[Tier-1 vs tier-2 dispatch](tier-fallbacks.md) lookup table. Every
entry lists the concrete fix — most are one-liners (split a system,
fold services into a `Res<Bundle>`, move a `@Where` predicate into
the method body).

## What's the chunk size default?

1024 entities per chunk. Change it via
`WorldBuilder.chunkSize(int)` before calling `build()`. Verified in
`ecs-core/.../world/WorldBuilder.java`.

## Why does `@FromTarget @Write` not work?

It's rejected at parse time, not a tier-2 drop. Write conflicts
between pairs sharing a target are ambiguous — if predators A and B
both hunt prey P, neither has well-defined "last writer wins"
semantics. Write onto the source, the payload, or split the system
into a target-side reader followed by a per-entity writer. See
[@ForEachPair and @FromTarget](../tutorials/relations/19-for-each-pair.md).

## How do I enable flat-array storage?

Pass `-Dzzuegg.ecs.useFlatStorage=true` to the JVM. It's an
experimental storage layout in `DefaultComponentStorage` — see
[Benchmarks](../benchmarks/index.md) for the numbers that triggered
the SoA work.

## Is Valhalla required?

No. japes runs on stock JDK 26 and ships a separate
`ecs-benchmark-valhalla` module for comparison against Valhalla
early-access builds. See [Valhalla EA benchmarks](../benchmarks/valhalla.md).

## How do I depend on japes?

Grab the coordinate off [Installation](../getting-started/installation.md).
Gradle, Maven, and source-build instructions are all there.

## How do I report a bug?

Open an issue at
[github.com/zzuegg/japes/issues](https://github.com/zzuegg/japes/issues).
Include the JDK version, a minimal failing system signature, and —
for performance problems — the `skipReason` from the plan dump plus
a JMH run you can reproduce locally.

## Where do I find the complete parameter list?

The [system parameters cheat sheet](cheat-sheet.md). Every annotation
and service type is listed, with links back to the tutorial chapter
that explains each in depth.
