---
title: "One JIT to rule them all"
---

# One JIT to rule them all

*How we made a Java ECS faster than Bevy (Rust) on writes — by learning what the JIT can and can't do, and restructuring every hot path around escape analysis.*

This page collects everything we learned about JVM performance during the japes optimization sessions. It's written for ECS authors and performance-oriented Java developers. The principles apply far beyond ECS.

## The thesis

The JIT compiler is astonishingly good at optimizing code — **if you let it**. Most "Java is slow" benchmarks are actually measuring how badly the code's data structures prevent the JIT from doing its job. Remove the obstacles, and Java competes with hand-written Rust on the same workload.

japes went from **9.3× slower than Bevy** on writes to **3.3× faster** — not by rewriting in native code, not by using `sun.misc.Unsafe`, not by waiting for Valhalla. Just by understanding three things:

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

## Dispatch-level EA: the processAll discovery

The tier-1 generators emit correct EA-friendly bytecode (fresh Mut, SoA inline), but **the JIT can't use it if it can't inline the method**. Two dispatch-level blockers had to be solved:

### Megamorphic dispatch prevents inlining

The system scheduler calls `processor.processAll(chunks, tick)` through a single `invokeinterface` call site. With N systems (6 in the unified-delta benchmark), the JIT sees N different hidden-class receivers → **megamorphic** → can't inline `processAll()` → EA can't see the Mut/record allocations inside.

**Verified**: with 1 system (monomorphic dispatch), allocation drops from 838 KB/op to **48 bytes/op**. EA eliminates everything.

**Partial fix**: inline the entity loop directly into `processAll()` instead of delegating to `process()`. C2 compiles each hidden class's `processAll` independently. Within each compilation, the user method call is monomorphic → inlined → EA can scalar-replace Mut. The entity-loop code is emitted by a shared emitter called from both `process()` and `processAll()`, avoiding duplication.

### BCEA bytecode size limit: a red herring

C2's bytecode-level escape analysis (BCEA) only analyzes methods below **150 bytes**. Our initial diagnosis blamed this for failing to eliminate observer record allocations in large `processAll` methods (~293 bytes).

**Experiment disproved this.** A focused JMH benchmark constructed methods >500 bytes with complex preambles (bitmap operations, array fills, dirty-slot computation) followed by 3-record allocation loops — identical to the observer shape. Result: **zero allocation in all variants**. C2's graph-level EA, which runs independently of BCEA, handles record elimination in large methods. The BCEA limit only affects the bytecode-level pre-analysis hints, not the actual elimination.

The remaining allocation in the unified-delta benchmark comes from structural mutations (spawn, despawn, component strip/restore) that go through the `World` API's megamorphic `ComponentStorage` dispatch — not from the system iteration loops.

### isChanged guard on flush

The tier-1 generator now emits `if (mut.isChanged()) { flush + write-back }` instead of unconditionally flushing every entity. This avoids `flush()`, SoA decomposition, and storage writes for the 90% of entities that a selective mutator system doesn't modify.

### The phi-merge killer: split current/pending fields

The deepest EA blocker was invisible at the bytecode level — it lived in C2's intermediate representation.

**The problem**: `Mut.set(newValue)` overwrote `this.current` with the new record. At the `isChanged()` guard after the user method, C2's IR had a **phi node**: `current` was either the original record (90% unchanged) or the new record (10% changed). `flush()` returned whichever `current` held. EA couldn't eliminate either record because it couldn't prove which value flowed through `flush()` → `checkcast` → accessor → SoA store.

**How we found it**: removing `m.set()` from the benchmark mutators (making them read-only) dropped allocation from **1.07 MB to 434 KB** — proving the write path was responsible for 640 KB of escaped records. LogCompilation confirmed: everything was inlined (Mut constructor, get, set, flush, markChanged, appendDirtyUnchecked), Mut itself was EA'd, but the Mana record was not.

**The fix**: split `current` into two fields — `current` (set once in the constructor, never overwritten) and `pending` (written by `set()`, null if unchanged). `get()` returns `pending` if changed, `current` otherwise. `flush()` returns `pending`.

```java
// Before: phi merge blocks EA
public void set(T value) { this.current = value; changed = true; }
public T flush() { return current; }  // current has 2 possible values → phi

// After: no phi — each field holds exactly one value
public void set(T value) { this.pending = value; changed = true; }
public T flush() { return changed ? pending : current; }
```

No phi merge → EA independently proves: (1) the original record in `current` is dead after `get().field()` extracts the primitive, and (2) the new record in `pending` is dead after `flush()` decomposes it into SoA stores.

**Impact**: unified-delta allocation dropped from **1.07 MB to 603 KB** (44% reduction). EA now eliminates 78% of all allocations (up from 61%).

### The 600 KB floor — and how we broke through it

After the phi-merge fix, we tested **10 different approaches** in parallel git worktrees: inline flush, eliminate pending, primitive payload, unconditional decompose, null pending, primitive Mut field, hidden Mut subclass, diagnostic variants, void flushChanged, unconditional write. Nine converged on ~600 KB.

**Two found the breakthrough: generated Mut subclass with primitive fields.**

The root cause of the 600 KB floor: the Mut constructor created a Record (`new Health(soaHp[slot])`) for every entity in every write system — 10,000 × 3 systems × ~20 bytes = 600 KB. EA couldn't eliminate these because they flowed through `get()`'s `changed ? pending : current` phi. The phi-merge fix helped the *write* path but not the *read* path — the original record lived through the conditional in `get()`.

**The fix**: generate a `Mut` subclass per component type via `Lookup.defineClass()` that stores current/pending values as **primitive fields** (`cur_hp`, `pnd_hp`) instead of Record references:

- `get()` reconstructs a Record from primitives on demand — immediately decomposed by user code → EA eliminates it
- `set()` decomposes the Record into primitives immediately — the user's `new Health(hp-1)` dies right after decomposition → EA eliminates it
- The entity loop populates primitive fields directly from SoA arrays — **no Record ever created** for the initial value
- The flush path reads primitive fields directly into SoA arrays — no Record intermediary

Each system's call site sees only its specific Mut subclass → monomorphic → JIT devirtualizes and inlines → EA sees the full chain.

**Impact**: allocation dropped from **603 KB to 297 KB** (51% reduction). EA now eliminates **89%** of all allocations (verified: 2.77 MB with EA off → 297 KB with EA on).

### The 297 KB structural floor (verified via JFR)

The remaining 297 KB is purely structural — objects that genuinely escape into framework data structures:

- **~120 KB**: `HashMap.Node[]`, `int[]`, `long[]` from archetype graph operations during spawn/despawn/strip
- **~60 KB**: Records from `Chunk.get()` in the despawn removal log (stored in `RemovalLog` across ticks)
- **~40 KB**: `EntityLocation` records from archetype migration
- **~35 KB**: `RemovalLog.Entry` objects from despawn
- **~25 KB**: Records from `addComponent`/`removeComponent` migration paths
- **~17 KB**: Observer record reconstruction residual + HiddenMut EA residual from JIT warmup

Zero allocation samples from the mutator system `set()` path — the generated primitive Mut eliminates them completely.

## EA stress testing: what works and what doesn't

We ran **10 parallel stress-test benchmarks** covering every EA dimension. Key results:

### What EA handles perfectly (0 B/op per entity)

| Pattern | Allocation | EA rate |
|---|---:|---:|
| 2-4 @Write params per system | 0 B | >99.99% |
| 6-field records (all same type) | 0 B | >99.99% |
| get() after set(), loops, double-set | 0 B | >99.99% |
| @ForEachPair with all write patterns | 0 B | >99.99% |
| 1-20 systems (linear scaling, 0 B/system) | 0 B | 100% |
| Entity counts 100 to 1,000,000 (dense writes) | 0 B | 100% |
| @ValueTracked with different values | 0 B | >99.99% |

### What we fixed

| Issue | Before | After | Fix |
|---|---:|---:|---|
| Mixed-type records (bool+int) | 560 KB | **0 B** | Uniform long cur\_ fields |
| Mixed-type records (int+float+double+long) | 1.0 MB | **40 KB** | Same (JVM limit on 4-type mix) |
| @ValueTracked same-value + observer | 16 KB | **200 B** | Primitive field comparison in flush |
| Non-SoA with all-primitive records | 26 KB | **1.2 KB** | SoAPromotingFactory auto-promotes |
| Per-tick framework overhead | 80 B/system | **0 B** | Cached lambdas, index loops, raw lists |

### Known limits

| Issue | Allocation | Root cause |
|---|---:|---|
| Sparse writes at 400-2500 entities | 56×N B | JIT profiling artifact — C2 branch profile at mid-range counts defeats EA. Recovers above 3000. Dense writes unaffected. |
| Mixed(int,float,double,long) residual | 40 KB | User's `new Mixed(...)` has genuinely mixed 32/64-bit field widths that C2 can't scalar-replace — JVM limitation. |
| Non-SoA with String/Object fields | 26-75 KB | Object[] backing fundamentally defeats EA. Records with reference fields can't use SoA. |
| Res + Commands service params | ~1.4 KB | Extra locals increase register pressure, occasionally preventing EA. |

### Nested records: zero-cost composition

Records containing other records are now SoA-eligible. `RecordFlattener` recursively flattens the field tree:

```java
record Vec3(float x, float y, float z) {}
record Transform(Vec3 pos, Vec3 vel) {}
// → flattened into 6 SoA arrays: pos_x[], pos_y[], pos_z[], vel_x[], vel_y[], vel_z[]
// → HiddenMut: cur_pos_x, cur_pos_y, cur_pos_z, cur_vel_x, ...
// → EA: zero per-entity allocation, identical to flat 6-field record
```

## The optimization journey in numbers

| Round | What | Write µs/op (10k) | vs Bevy |
|---|---|---:|---|
| Start | Reflective dispatch, Object[], HashMap everywhere | 57.4 | 9.1× slower |
| ArchetypeId flat array | TreeSet → sorted ComponentId[] | 38.1 | 6.1× slower |
| Fresh Mut per entity | Reused Mut → fresh allocation + EA | 24.8 | 3.9× slower |
| SoA storage + tier-1 inline | Object[] → per-field primitive arrays | 1.70 | **3.7× faster** |
| Inline processAll + isChanged guard | Entity loop in one compilation unit | 1.9 | **3.3× faster** |

**Total: 57.4 µs → 1.9 µs = 30.2× speedup.** From 9.1× slower than Bevy to 3.3× faster. All on stock JDK 26, no `Unsafe`, no Valhalla.

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

6. **Graph-level EA is more capable than BCEA suggests.** C2's bytecode-level escape analysis (BCEA) has a 150-byte limit, but the graph-level EA that runs during C2 optimization handles much larger methods. Tested: methods >500 bytes with complex preambles still got full record elimination. Don't split methods just for BCEA — split for inlining and readability.

7. **Avoid phi merges on allocation-bearing fields.** If a field can hold two different freshly-allocated objects depending on a branch (e.g., original vs. mutated value), EA can't eliminate either. Split the values into separate fields — one per allocation site — so each field holds exactly one value. The JIT tracks each independently and can prove each dead after use.

8. **Uniform field widths enable scalar replacement.** A class with `boolean`, `int`, `float`, and `long` fields has 4 different JVM slot widths. C2's EA struggles with heterogeneous layouts. Storing everything as `long` with bit-preserving widening (`Float.floatToRawIntBits`, `Double.doubleToRawLongBits`) gives uniform layout that EA handles trivially. Tested: `Flags(boolean, boolean, int)` went from 560 KB to 0 B by widening cur\_ fields to long.

9. **Zero overhead means zero.** Every per-tick allocation source matters. `String.substring` in a name check, `Collections.unmodifiableList` wrappers, `this::methodRef` lambdas, `ArrayList` iterators from for-each loops — each is 16-48 bytes that add up across systems. Caching, index-based loops, and raw list returns eliminated 80 B/system/tick → 0 B. NBody went from 80 B/op to literally 0.006 B/op.

## Related

- [Optimization journey (relations)](optimization-journey.md) — the 167 → 27.6 µs predator/prey story
- [Multi-target @Filter log](unified-delta-optimization.md) — the 9-system → 3-system change detection story
- [Tier-1 bytecode generation](tier-1-generation.md) — how the hidden classes are emitted
- [Write-path tax](write-path-tax.md) — the historical context for why writes were slow
- [Benchmarks overview](../benchmarks/index.md) — the full cross-library numbers
