---
title: "One JIT to rule them all"
---

# One JIT to rule them all

*How a Java ECS went from 9.3x slower than Bevy (Rust) on writes to 3.3x faster -- by restructuring every hot path around escape analysis.*

japes achieved **57.4 us -> 1.9 us** on `iterateWithWrite` with 10,000 entities. A 30.2x speedup. All on stock JDK 26, no `Unsafe`, no Valhalla.

This page explains the ten techniques that made it possible. It is written for ECS authors and performance-oriented Java developers, but the principles apply to any allocation-sensitive Java code.

## Escape analysis fundamentals

Escape analysis (EA) is the JIT's ability to prove that an object never leaves the scope that created it. When an object doesn't escape, the JIT **scalar-replaces** it: the object's fields become CPU registers or stack slots. No heap allocation, no GC pressure, no cache miss. The constructor, field initialization, and garbage collection all disappear.

An object escapes if any of these happen:

- **Stored in a heap field** -- `this.current = value` on a heap-allocated container
- **Stored in an array** -- `data[slot] = record` into an `Object[]` (aastore requires a heap reference)
- **Passed to a non-inlined method** -- the JIT can't see what happens on the other side
- **Returned from a non-inlined method** -- same problem in the other direction

The JIT must inline the **entire call chain** for EA to work. If any link breaks, the object must be heap-allocated at that boundary.

## The ten techniques

### 1. SoA storage enables EA on records

`Object[]` storage prevents EA. An `aastore` instruction requires a heap-resident reference -- the record must be materialized on the heap. Struct-of-arrays (SoA) storage uses one primitive array per record field (`float[] x`, `float[] y`, `float[] z`). Writing a record decomposes into `fastore` instructions that only need primitive values. No heap reference required. EA can prove the record never escapes and scalar-replace it entirely.

```
Object[] (old):  aastore → record must be heap-allocated → EA fails
SoA float[] (new): fastore x 3 → only float values needed → EA eliminates record
```

Verified: the same all-primitive record with SoA storage allocates **792 B/op**. With `Object[]` it allocates **25,742 B/op** -- 32x worse.

### 2. Fresh Mut per entity enables EA on the wrapper

The `Mut<T>` wrapper gives systems mutable access to components. The intuitive approach is to allocate one `Mut` and reuse it across entities. But a reused Mut stored as a heap field costs real memory writes per entity -- and EA cannot scalar-replace it because it lives on the heap.

A fresh `Mut` allocated per entity inside the generated loop is created, passed to the inlined user method, flushed, and discarded within one iteration. It never escapes. EA scalar-replaces it -- the field writes become register assignments.

Counter-intuitively, **more allocation = faster execution**. Reusing the Mut causes a 4x throughput regression (522 -> 130 ops/ms on `iterateWithWrite`). Fresh Mut is essential.

```java
// What the generator emits (simplified):
for (int slot = 0; slot < count; slot++) {
    var mut = new Mut<>(value, slot, tracker, tick, false);  // EA scalar-replaces
    inst.iterate(velocity, mut);  // inlined — EA sees through
    if (mut.isChanged()) {
        storage.set(slot, mut.flush());
    }
}
```

### 3. Generated Mut subclass with primitive fields (HiddenMut)

Plain `Mut` stores `Record` references in its `current` and `pending` fields. The `Mut` constructor would create a record from SoA data for every entity in every write system -- 10,000 entities x 3 systems x ~20 bytes = 600 KB of records that EA struggled to eliminate.

The fix: generate a `Mut` subclass per component type via `Lookup.defineClass()` that stores current values as **primitive fields** (`cur_hp` as `long`) populated directly from SoA arrays. No record is ever created for the initial value.

- `get()` reconstructs a record from `cur_` fields on demand. User code immediately decomposes it into field accesses. EA eliminates the record.
- `set()` is **not** overridden -- the base `Mut.set()` at 11 bytes always inlines. It stores the record in `pending`.
- The flush path reads `pending`, decomposes it into SoA stores, and the record dies after the accessor calls.

Each system's call site sees only its specific HiddenMut subclass -- monomorphic, devirtualized, inlined, EA-friendly.

**Impact**: unified-delta allocation dropped from **605 KB to 297 KB** (51% reduction).

### 4. Split current/pending fields to eliminate phi merges

When `set()` overwrote `this.current`, C2's intermediate representation had a **phi node**: `current` held either the original record or the new record depending on whether `set()` was called. EA couldn't eliminate either record because it couldn't prove which value flowed through `flush()`.

The fix: `current` is set once in the constructor and never overwritten. `set()` writes to a separate `pending` field. No phi merge. EA independently proves: (1) the original record in `current` is dead after `get().field()` extracts the primitive, and (2) the new record in `pending` is dead after `flush()` decomposes it into SoA stores.

```java
// Before: phi merge blocks EA
public void set(T value) { this.current = value; changed = true; }
public T flush() { return current; }  // current has 2 possible values → phi

// After: no phi — each field holds exactly one value
public void set(T value) { this.pending = value; changed = true; }
public T flush() { return changed ? pending : current; }
```

**Impact**: allocation dropped from **1.07 MB to 603 KB** (44% reduction).

### 5. Uniform field widths for scalar replacement

Records with mixed primitive types (boolean + int + float) in the HiddenMut had heterogeneous field layouts. C2's EA struggles with mixed slot widths. Storing all `cur_` fields as `long` with bit-preserving widening (`Float.floatToRawIntBits`, `Double.doubleToRawLongBits`) gives uniform layout that EA handles trivially.

- `Flags(boolean, boolean, int)`: **560 KB -> 72 B**
- `Mixed(int, float, double, long)`: still at 40 KB -- this is the Mut wrapper interaction, not a C2 bug (verified: simple loops with Mixed records achieve 0 B/op)

There is **no 3-field inline limit**. Tested 1-8 fields of the same type -- all achieve ~0 B/op. The limit is about type mix, not field count.

### 6. Primitive field comparison for @ValueTracked

`@ValueTracked` components need an equals() check during flush to detect whether the value actually changed. Calling `Record.equals()` is virtual dispatch that blocks EA. The fix: compare `cur_` vs `pending` fields with primitive comparison instructions (`if_icmpne`, `fcmpl`, `dcmpl`, `lcmp`). No virtual dispatch needed.

**Impact**: same-value + observer scenario dropped from **16 KB to 200 B** (81x improvement).

### 7. Inline processAll -- entity loop in one compilation unit

The system scheduler dispatches through `invokeinterface`, which becomes megamorphic with multiple systems. Each system's `processAll` is compiled independently by C2. The entity loop is emitted **inline** in `processAll` (not delegated to `process()`) so C2 compiles it as one unit. Within each compilation, the user method call is monomorphic -- inlined -- and EA works across the full chain.

With 1 system (monomorphic): **48 B/op**. With 6 systems: no degradation per system.

Note: C2's bytecode-level escape analysis (BCEA) has a 150-byte limit, but graph-level EA runs independently and handles methods of 500+ bytes. The BCEA limit is a red herring -- don't split methods for BCEA, split for inlining and readability.

### 8. Framework overhead elimination

Every per-tick allocation adds up across systems. Sources found via JFR allocation profiling: `String.substring` in name checks, `Collections.unmodifiableList` wrappers, `this::methodRef` lambdas, `ArrayList` iterators from for-each loops.

Fix: cached lambdas, index-based loops, raw list returns, pre-computed name maps, cached singleton sets.

**Impact**: NBody went from **80 B/op to 0 B/op**.

### 9. SoA auto-promotion for custom factories

`SoAPromotingFactory` wraps custom storage factories and transparently upgrades all-primitive records to SoA. Users who set a custom factory still get SoA benefits for eligible records without changing their configuration.

**Impact**: forced `DefaultComponentStorage` for primitive records dropped from **26 KB to 1.2 KB** (22x reduction).

### 10. Nested record flattening

Records containing other records are SoA-eligible when the entire field tree is primitive. `RecordFlattener` recursively flattens the structure:

```java
record Vec3(float x, float y, float z) {}
record Transform(Vec3 pos, Vec3 vel) {}
// → 6 SoA arrays: pos_x[], pos_y[], pos_z[], vel_x[], vel_y[], vel_z[]
// → HiddenMut: cur_pos_x, cur_pos_y, cur_pos_z, cur_vel_x, ...
```

Zero-cost composition -- allocation and throughput are identical to flat records with the same number of fields.

## Data structure choices

### TreeSet -> sorted array (ArchetypeId)

`ArchetypeId` used a `TreeSet<ComponentId>` backed by `TreeMap`. Every iteration required `TreeMap.getFirstEntry()` plus an iterator chain with virtual dispatch per element -- 4-5% of CPU on every benchmark. Replaced with a sorted `ComponentId[]` flat array. Iteration is a plain `for` loop. `hashCode` is a cached sum. `equals` is `Arrays.equals`. 30-35% improvement on write-heavy benchmarks.

### Reusable object vs fresh allocation

A "zero-allocation" design that stores objects in heap fields can be slower than a "one-allocation-per-entity" design that EA scalar-replaces. Allocation is not expensive. L1 cache stores are expensive. EA eliminates both; reuse eliminates neither.

## EA stress test results

### What works perfectly (0 B/op)

- 1-4 `@Write` params per system
- 1-8 fields of same type
- `get()` after `set()`, loops, double-set patterns
- `@ForEachPair` with all write patterns (1-3 field records, dual write, cross-entity)
- 1-20 systems (linear scaling, ~0 B/system after framework overhead fix)
- Entity counts 100 to 1,000,000 (dense writes)
- `@ValueTracked` with different values
- Nested records (identical to flat)

### Known limits

| Issue | Allocation | Root cause |
|---|---:|---|
| Mixed(int,float,double,long) residual | 40 KB | Mut wrapper interaction with 4-type mix, not field count (simple loops = 0 B/op) |
| Sparse writes at 400-2500 entities | variable | JIT profiling artifact with conditional branches; recovers above 3000 |
| Non-SoA with String/Object fields | high | `Object[]` fundamentally defeats EA |
| Res + Commands service params | ~2x baseline | Extra locals increase register pressure |

## The optimization journey

| Round | What | Write us/op (10k) | vs Bevy |
|---|---|---:|---|
| Start | Reflective dispatch, Object[], HashMap everywhere | 57.4 | 9.1x slower |
| ArchetypeId flat array | TreeSet -> sorted ComponentId[] | 38.1 | 6.1x slower |
| Fresh Mut per entity | Reused Mut -> fresh allocation + EA | 24.8 | 3.9x slower |
| SoA storage + tier-1 inline | Object[] -> per-field primitive arrays | 1.70 | **3.7x faster** |
| Inline processAll + isChanged guard | Entity loop in one compilation unit | 1.9 | **3.3x faster** |

**Total: 57.4 -> 1.9 us = 30.2x speedup.**

## What Valhalla would add

1. **Flat backing arrays natively** -- the JVM handles SoA decomposition for value records, without the library needing to generate per-field arrays.
2. **Erased-boundary elimination** -- value types pass through the `Record` type boundary without boxing. Currently only affects non-system APIs (`world.setComponent`, `world.getComponent`), not the hot per-entity loop.

## Principles

1. **Profile before optimizing.** JFR allocation samples find what intuition misses.
2. **Think in escape paths, not allocation counts.** Reuse prevents EA; fresh allocation enables it.
3. **The JIT is your co-designer.** `Object[]` prevents EA; `float[]` enables it. `invokeinterface` prevents inlining; `invokevirtual` from a monomorphic site enables it.
4. **Tier-1 bytecode generation pays for itself.** Clean monomorphic `invokevirtual` lets the JIT inline aggressively.
5. **Benchmark what real code does.** `bh.consume(pos.x())` not `bh.consume(pos)`.
6. **Uniform field widths enable scalar replacement.** Store everything as `long`, widen/narrow at boundaries.
7. **Avoid phi merges on allocation-bearing fields.** Split into separate fields per allocation site.
8. **Zero overhead means zero.** Cache lambdas, use index loops, return raw lists.
9. **Graph-level EA handles large methods.** The BCEA 150-byte limit is a red herring; graph-level EA works on 500+ byte methods.
10. **No 3-field inline limit exists.** The limit is type mix, not field count (verified 1-8 fields).

## Related

- [Optimization journey (relations)](optimization-journey.md) -- the 167 -> 27.6 us predator/prey story
- [Multi-target @Filter log](unified-delta-optimization.md) -- the 9-system -> 3-system change detection story
- [Tier-1 bytecode generation](tier-1-generation.md) -- how the hidden classes are emitted
- [Write-path tax](write-path-tax.md) -- the historical context for why writes were slow
- [Benchmarks overview](../benchmarks/index.md) -- the full cross-library numbers
