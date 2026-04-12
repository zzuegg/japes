---
title: "One JIT to rule them all"
---

# One JIT to rule them all

*How we made a Java ECS faster than Bevy (Rust) on writes — by learning what the JIT can and can't do, and restructuring every hot path around escape analysis.*

This page collects everything we learned about JVM performance during the japes optimization sessions. It's written for ECS authors and performance-oriented Java developers. The principles apply far beyond ECS.

## The thesis

The JIT compiler is astonishingly good at optimizing code — **if you let it**. Most "Java is slow" benchmarks are actually measuring how badly the code's data structures prevent the JIT from doing its job. Remove the obstacles, and Java competes with hand-written Rust on the same workload.

japes went from **9.3× slower than Bevy** on writes to **3.6× faster** — not by rewriting in native code, not by using `sun.misc.Unsafe`, not by waiting for Valhalla. Just by understanding three things:

1. What prevents escape analysis
2. What prevents scalar replacement
3. What the JIT can inline and what it can't

## Escape analysis: the single most important optimization

**Escape analysis (EA)** is the JIT's ability to prove that an object never leaves the method (or loop iteration) that created it. If the object doesn't escape, the JIT can:

- **Scalar-replace it**: turn the object's fields into CPU registers or stack slots. No heap allocation, no GC pressure, no cache miss.
- **Eliminate the constructor**: the `new` instruction and field initialization become register assignments.
- **Eliminate the garbage**: the object never touches the heap, so the GC never sees it.

### What makes an object escape

An object **escapes** if any of these happen:

| Escape path | Example | Fix |
|---|---|---|
| **Stored in a heap field** | `this.current = value;` on a heap-allocated `Mut` | Make the container scalar-replaceable too (fresh allocation per use) |
| **Stored in an array** | `data[slot] = record;` into an `Object[]` | Use primitive arrays (SoA) so only field values are stored |
| **Passed to a non-inlined method** | `invokeinterface` to a polymorphic call site | Use `invokevirtual` on a monomorphic site the JIT can inline |
| **Returned from a method** | `return new Position(x, y, z);` from a non-inlined call | Ensure the caller is inlined so EA sees through the return |
| **Stored in a synchronized block** | `synchronized(obj) { ... }` | Avoid synchronization on the hot path |

### The three discoveries that changed japes

#### 1. Fresh Mut per entity enables EA on the wrapper

**Before**: `Mut<Position>` was allocated once at plan-build time, stored as a field on the generated class (`this.muts[i]`), and reused across all entities via `resetValue(value, slot)`. Four heap field writes per entity. EA could not scalar-replace it because it was stored in a heap field.

**After**: allocate a fresh `new Mut(value, slot, tracker, tick, false)` per entity inside the generated loop. The Mut is created, passed to the inlined user method, flushed, and discarded within one iteration. It never escapes. EA scalar-replaces it — the 4 field writes become register assignments.

**Impact**: 36% improvement on `iterateWithWrite`. The 19% of CPU spent in `Mut.resetValue` disappeared entirely.

```java
// What the tier-1 generator emits (simplified):
for (int slot = 0; slot < count; slot++) {
    var value = storage.get(slot);
    var mut = new Mut<>(value, slot, tracker, tick, false);  // EA scalar-replaces
    inst.iterate(velocity, mut);  // inlined — EA sees through
    if (mut.isChanged()) {
        storage.set(slot, mut.flush());
    }
}
```

#### 2. Struct-of-arrays storage enables EA on the record

**Before**: component storage was `Object[]` — an array of boxed record references. `storage.set(slot, new Position(x, y, z))` does an `aastore` which requires a heap-resident reference. EA cannot scalar-replace the Position because it escapes into the array.

**After**: struct-of-arrays (SoA) storage uses one primitive array per record field (`float[] x`, `float[] y`, `float[] z`). `storage.set(slot, pos)` decomposes into `x[slot] = pos.x(); y[slot] = pos.y(); z[slot] = pos.z()` — three `fastore` instructions. Primitive array stores don't require a heap reference. EA can now prove the Position never escapes and scalar-replace it.

**Impact**: 15× improvement on `iterateWithWrite`. Zero per-entity heap allocation (verified by GC profiler: 241 KB/tick → 798 B/tick).

```
Object[] storage (old):
  write: aastore → Position must be heap-allocated → EA fails
  read:  aaload  → returns existing heap reference → no allocation

SoA float[] storage (new):
  write: fastore × 3 → only float values needed → EA eliminates Position
  read:  faload × 3 + new Position() → EA eliminates if fields don't escape
```

#### 3. Field-level consumption enables EA on reads

**Before**: benchmarks consumed the whole record — `bh.consume(pos)` — which forces it onto the heap (the Blackhole holds a reference). This penalised SoA reads (which reconstruct the record per entity) while giving Object[] a free pass.

**After**: consume individual fields — `bh.consume(pos.x()); bh.consume(pos.y()); bh.consume(pos.z())`. The Position is only used for field access, then discarded. EA scalar-replaces it — the reconstruction becomes three register loads. This matches real game code where systems compute with field values, not store references.

**Impact**: SoA reads went from 5× slower to 1.74× faster than Object[] (with field-level consumption on both).

## Inlining: the prerequisite for everything else

EA only works on objects whose entire lifetime is visible to the JIT in one compilation unit. If a method call isn't inlined, the JIT can't see what happens to the object on the other side — it conservatively assumes it escapes.

### What the tier-1 generator does for inlining

The generated chunk processor emits a direct `invokevirtual` to the user's system method. The call site is **monomorphic** (one target class, one method) so the JIT profiles it, inlines it, and then EA can see through the entire chain:

```
generated loop body  →  invokevirtual user.iterate(vel, mut)  →  inlined
    →  mut.get()     →  inlined (returns this.current → register)
    →  new Position  →  EA scalar-replaces (fields → registers)
    →  mut.set(pos)  →  inlined (this.current = pos → register)
    →  mut.flush()   →  inlined (returns this.current → register)
    →  fastore × 3   →  primitive writes to SoA arrays
```

Every link in this chain must be inlined for EA to work. If any link breaks (e.g., an `invokeinterface` that the JIT can't devirtualize), the object escapes at that boundary and must be heap-allocated.

### What breaks inlining

| Pattern | Why it breaks | Fix |
|---|---|---|
| `invokeinterface` on a polymorphic site | JIT sees multiple implementors → can't pick one to inline | Use `invokevirtual` on a concrete class, or ensure monomorphic profile |
| Method too large (>325 bytecodes default) | JIT gives up on inlining large methods | Keep system methods short (typical: 1-5 lines) |
| `MethodHandle.invoke` / `invokeWithArguments` | Generic dispatch, JIT can't specialize | Use `invokevirtual` from generated bytecode |
| Recursion or deep call chains | JIT has an inlining depth budget | Flatten hot paths |

## Data structures: every choice is a JIT decision

### TreeSet vs sorted array (ArchetypeId)

`ArchetypeId` used a `TreeSet<ComponentId>` backed by `TreeMap`. Every iteration required `TreeMap.getFirstEntry()` + an iterator chain with virtual dispatch per element. **4-5% of CPU** on every benchmark.

Replaced with a sorted `ComponentId[]` flat array. Iteration is a plain `for` loop. `contains` is `Arrays.binarySearch`. `hashCode` is a cached sum loop. `equals` is `Arrays.equals`. **30-35% improvement on write-heavy benchmarks** — the accumulated effect of faster archetype lookups across every spawn, despawn, and component migration.

**Lesson**: the JIT can't remove virtual dispatch from `TreeMap$Entry.next()` calls, but it trivially optimizes `array[i]` into a register-indexed load.

### HashMap vs flat array (ArchetypeGraph edges)

`addEdges` and `removeEdges` were `HashMap<ArchetypeId, HashMap<ComponentId, ArchetypeId>>` — nested HashMaps with ~5 entries in the inner map. Replaced the inner map with `ArchetypeId[]` indexed by `ComponentId.id()`. **8% improvement on bulk spawn**.

### Reusable object vs fresh allocation (Mut)

Counter-intuitively, **allocating a fresh object per iteration can be faster than reusing one** — if the fresh allocation enables EA. A reusable `Mut` stored as a heap field costs 4 real memory writes per entity. A fresh `Mut` that EA scalar-replaces costs 0 memory writes.

**Lesson**: allocation is not expensive. L1 cache stores are expensive. EA eliminates both; reuse eliminates neither.

### LinkedHashMap vs long[] bitmap (MultiFilterHelper)

The multi-target `@Filter` helper originally used `BitSet` for deduplication (heap-allocated per chunk). Replaced with a reusable `long[]` bitmap field on the generated class. **Zero per-chunk allocation**.

## The optimization journey in numbers

| Round | What | Write µs/op (10k) | vs Bevy |
|---|---|---:|---|
| Start | Reflective dispatch, Object[], HashMap everywhere | 57.4 | 9.1× slower |
| ArchetypeId flat array | TreeSet → sorted ComponentId[] | 38.1 | 6.1× slower |
| Fresh Mut per entity | Reused Mut → fresh allocation + EA | 24.8 | 3.9× slower |
| SoA storage + tier-1 inline | Object[] → per-field primitive arrays | 1.75 | **3.6× faster** |

**Total: 57.4 µs → 1.75 µs = 32.8× speedup.** From 9.1× slower than Bevy to 3.6× faster. All on stock JDK 26, no `Unsafe`, no Valhalla.

## What Valhalla would add

JEP 401 (value classes) would give two additional wins:

1. **Flat backing arrays natively**: `value record Position(float x, float y, float z)` stored in a null-restricted flat array — the JVM handles the SoA decomposition automatically, without the library needing to generate per-field arrays.

2. **Erased-boundary elimination**: currently `World.setComponent(Record component)` forces boxing at the `Record` type boundary. Value types would let the JVM pass the flat value through without materializing a heap wrapper.

Our current SoA approach achieves (1) at the library level. (2) is the remaining cost that only Valhalla can fix — but it only affects the non-system APIs (`world.setComponent`, `world.getComponent`), not the hot per-entity loop.

## Principles for ECS authors

1. **Profile before optimizing.** The ArchetypeId TreeSet was 4% of CPU for months before anyone noticed. JFR execution samples + allocation samples find what intuition misses.

2. **Think in terms of escape paths, not allocation counts.** A "zero-allocation" design that stores objects in heap fields can be slower than a "one-allocation-per-entity" design that EA scalar-replaces.

3. **The JIT is your co-designer.** Every data structure choice is a JIT optimization opportunity or obstacle. `Object[]` prevents EA; `float[]` enables it. `invokeinterface` prevents inlining; `invokevirtual` from a monomorphic site enables it.

4. **Tier-1 bytecode generation pays for itself.** The cost of emitting 800 lines of `java.lang.classfile` code is amortized across every tick for the lifetime of the world. The JIT sees clean, predictable bytecode and inlines aggressively.

5. **Benchmark what real code does, not what's convenient.** `bh.consume(pos)` measures heap-reference survival. `bh.consume(pos.x())` measures field access. Real game code does the latter.

## Related

- [Optimization journey (relations)](optimization-journey.md) — the 167 → 27.7 µs predator/prey story
- [Multi-target @Filter log](unified-delta-optimization.md) — the 9-system → 3-system change detection story
- [Tier-1 bytecode generation](tier-1-generation.md) — how the hidden classes are emitted
- [Write-path tax](write-path-tax.md) — the historical context for why writes were slow
- [Benchmarks overview](../benchmarks/index.md) — the full cross-library numbers
