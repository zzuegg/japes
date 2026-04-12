package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.query.FieldFilter;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SystemExecutionPlan {

    public record ParamSlot(int argIndex, ComponentAccess access, boolean isWrite, boolean isValueTracked) {}

    public enum FilterKind { ADDED, CHANGED }

    /**
     * One resolved change-filter group. Within a group, targets are
     * OR'd: the group matches a slot if ANY of the target trackers
     * report dirty. Across groups (multiple {@code @Filter}
     * annotations), the match is AND'd.
     *
     * <p>Single-target filters produce a group with one entry;
     * multi-target {@code @Filter(Changed, target = {A, B, C})}
     * produces a group with three entries.
     */
    public record ResolvedChangeFilter(ComponentId[] targetIds, FilterKind kind) {
        /** Backward-compat single-target constructor. */
        public ResolvedChangeFilter(ComponentId singleTarget, FilterKind kind) {
            this(new ComponentId[]{singleTarget}, kind);
        }
        /** Convenience: first target id (for single-target fast path). */
        public ComponentId targetId() { return targetIds[0]; }
    }

    private final Object[] args;
    private final ParamSlot[] slots; // array for indexed access, no List overhead
    private final Mut<?>[] mutCache;

    // Per-chunk cached references — set once per chunk, used for all entities
    private final ComponentStorage<?>[] cachedStorages;
    private final ChangeTracker[] cachedTrackers;
    private final Map<Integer, FieldFilter> whereFilters;

    // Change-filter state: resolved filters + per-chunk tracker cache + per-system
    // "last seen" tick so isAddedSince / isChangedSince compare against the tick
    // at which the system last ran. The tracker cache is 2D: first dimension is
    // filter-group index, second is target-within-group (for multi-target @Filter).
    private final ResolvedChangeFilter[] changeFilters;
    private final ChangeTracker[][] cachedFilterTrackers;
    private long lastSeenTick = 0;

    // Reusable component map for @Where evaluation — populated per entity from
    // the cached slot refs, cleared and refilled instead of allocated. Only
    // instantiated if whereFilters is non-empty so systems without @Where stay
    // allocation-free on the hot path.
    private final HashMap<Class<?>, Record> whereLookup;

    // Cached query sets: computed once at plan build time instead of rebuilt
    // every tick inside World.executeSystem.
    private Set<ComponentId> requiredComponents = Set.of();
    private Set<ComponentId> withoutComponents = Set.of();

    public Set<ComponentId> requiredComponents() { return requiredComponents; }
    public Set<ComponentId> withoutComponents() { return withoutComponents; }
    public void setQuerySets(Set<ComponentId> required, Set<ComponentId> without) {
        this.requiredComponents = required;
        this.withoutComponents = without;
        this.matchingArchetypes = null;
        this.matchingArchetypesGeneration = -1;
    }

    // Memoized result of ArchetypeGraph.findMatching(requiredComponents),
    // keyed by the graph's generation counter. If the cached generation
    // matches the graph's current generation, the cached list is still
    // valid and we skip the HashMap<Set<ComponentId>, ...> lookup
    // entirely — that lookup was measurably hot (~18% of a RealisticTick
    // tick) because AbstractSet.hashCode walks every element on every
    // call. Comparing two longs is O(1).
    @SuppressWarnings("rawtypes")
    private java.util.List matchingArchetypes;
    private long matchingArchetypesGeneration = -1;

    @SuppressWarnings("unchecked")
    public <A> java.util.List<A> cachedMatchingArchetypes(long graphGeneration) {
        if (matchingArchetypesGeneration == graphGeneration) {
            return (java.util.List<A>) matchingArchetypes;
        }
        return null;
    }

    public <A> void cacheMatchingArchetypes(long graphGeneration, java.util.List<A> list) {
        this.matchingArchetypes = list;
        this.matchingArchetypesGeneration = graphGeneration;
    }

    // Classes consumed via RemovedComponents<T> parameters. Used by World to
    // identify which plans participate in each per-component GC pass.
    private Set<Class<? extends Record>> consumedRemovedComponents = Set.of();
    public Set<Class<? extends Record>> consumedRemovedComponents() { return consumedRemovedComponents; }
    public void setConsumedRemovedComponents(Set<Class<? extends Record>> types) {
        this.consumedRemovedComponents = types;
    }

    // Relation types consumed via RemovedRelations<T> parameters. Parallels
    // consumedRemovedComponents — drives end-of-tick GC of each store's
    // PairRemovalLog using the minimum watermark across all plans that
    // consume the type.
    private Set<Class<? extends Record>> consumedRemovedRelations = Set.of();
    public Set<Class<? extends Record>> consumedRemovedRelations() { return consumedRemovedRelations; }
    public void setConsumedRemovedRelations(Set<Class<? extends Record>> types) {
        this.consumedRemovedRelations = types;
    }

    // Parameter indices that receive the current iteration entity handle
    // (Entity-typed method parameters). Filled per-slot inside processChunk.
    private int[] entitySlotIndices = new int[0];
    public int[] entitySlotIndices() { return entitySlotIndices; }
    public void setEntitySlotIndices(int[] indices) {
        this.entitySlotIndices = indices;
    }

    public SystemExecutionPlan(int paramCount, List<ParamSlot> componentSlots, List<Integer> serviceArgIndices,
                               Map<Integer, FieldFilter> whereFilters) {
        this(paramCount, componentSlots, serviceArgIndices, whereFilters, List.of());
    }

    public SystemExecutionPlan(int paramCount, List<ParamSlot> componentSlots, List<Integer> serviceArgIndices,
                               Map<Integer, FieldFilter> whereFilters,
                               List<ResolvedChangeFilter> changeFilters) {
        this.args = new Object[paramCount];
        this.slots = componentSlots.toArray(ParamSlot[]::new);
        this.mutCache = new Mut<?>[slots.length];
        this.cachedStorages = new ComponentStorage<?>[slots.length];
        this.cachedTrackers = new ChangeTracker[slots.length];
        this.whereFilters = whereFilters;
        this.changeFilters = changeFilters.toArray(ResolvedChangeFilter[]::new);
        this.cachedFilterTrackers = new ChangeTracker[this.changeFilters.length][];
        for (int i = 0; i < this.changeFilters.length; i++) {
            this.cachedFilterTrackers[i] = new ChangeTracker[this.changeFilters[i].targetIds().length];
        }
        this.whereLookup = whereFilters.isEmpty() ? null : new HashMap<>();
    }

    public boolean hasChangeFilters() {
        return changeFilters.length > 0;
    }

    public ResolvedChangeFilter[] resolvedChangeFilters() {
        return changeFilters;
    }

    public long lastSeenTick() {
        return lastSeenTick;
    }

    /**
     * Called by World after the system has finished iterating every chunk for
     * a given tick. Advances the "last seen" watermark used by @Filter(Added)
     * and @Filter(Changed) for the next run.
     *
     * We store (currentTick - 1) rather than currentTick so that entities
     * spawned or mutated DURING the current tick (e.g., commands flushed
     * between stages, or user code between world.tick() calls) remain visible
     * to the system on its next run — their addedTick/changedTick equals the
     * current tick, and strict &gt; comparison against (currentTick - 1) lets
     * them through.
     */
    public void markExecuted(long currentTick) {
        this.lastSeenTick = currentTick - 1;
    }

    public Object[] args() {
        return args;
    }

    public void setServiceArg(int argIndex, Object value) {
        args[argIndex] = value;
    }

    /**
     * Cache storage and tracker references for a chunk. Call once per chunk.
     */
    public void prepareChunk(Chunk chunk) {
        for (int i = 0; i < slots.length; i++) {
            var compId = slots[i].access().componentId();
            cachedStorages[i] = chunk.componentStorage(compId);
            if (slots[i].isWrite()) {
                cachedTrackers[i] = chunk.changeTracker(compId);
            }
        }
        for (int i = 0; i < changeFilters.length; i++) {
            var ids = changeFilters[i].targetIds();
            for (int t = 0; t < ids.length; t++) {
                cachedFilterTrackers[i][t] = chunk.changeTracker(ids[t]);
            }
        }
    }

    /**
     * Fill component args using cached storage references. Zero HashMap lookups.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fillComponentArgs(int slot, long currentTick) {
        for (int i = 0; i < slots.length; i++) {
            var cs = slots[i];
            if (cs.isWrite()) {
                var value = cachedStorages[i].get(slot);
                var existing = (Mut) mutCache[i];
                if (existing == null) {
                    var mut = new Mut(value, slot, cachedTrackers[i], currentTick, cs.isValueTracked());
                    mutCache[i] = mut;
                    args[cs.argIndex()] = mut;
                } else {
                    existing.reset(value, slot, cachedTrackers[i], currentTick);
                }
            } else {
                args[cs.argIndex()] = cachedStorages[i].get(slot);
            }
        }
    }

    /**
     * Flush Mut values back using cached storage references.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void flushMuts() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isWrite()) {
                var mut = (Mut) mutCache[i];
                var newValue = mut.flush();
                ((ComponentStorage) cachedStorages[i]).set(mut.slot(), newValue);
            }
        }
    }

    public ParamSlot[] componentSlots() {
        return slots;
    }

    /**
     * Process an entire chunk in a single tight loop. Avoids per-entity virtual
     * dispatch overhead by keeping fill/invoke/flush in one method body that the
     * JIT can optimize as a single hot loop.
     */
    public void processChunk(Chunk chunk, SystemInvoker invoker, long currentTick) {
        prepareChunk(chunk);
        int count = chunk.count();

        if (changeFilters.length > 0) {
            // Sparse path: iterate the union of the first filter group's
            // dirty lists. For single-target groups this is one dirty list
            // (same as before). For multi-target groups (e.g.
            // @Filter(Changed, target = {A, B, C})) we iterate ALL targets'
            // dirty lists and use a seen-bitmap to deduplicate slots.
            //
            // Per-slot: each filter GROUP is checked with OR semantics
            // across its targets (any dirty target in the group → group
            // matches). Across groups the match is AND'd (all groups must
            // match). This preserves the existing contract for stacked
            // @Filter annotations while adding multi-target OR.
            var primaryTrackers = cachedFilterTrackers[0];
            boolean multiTarget = primaryTrackers.length > 1;

            // Collect iteration slots. For single-target, just walk the
            // one dirty list directly. For multi-target, union into a
            // temporary int array with dedup via a small bitset (if count
            // is manageable) or a HashSet (if huge).
            if (!multiTarget) {
                // Fast path: single-target, same as before.
                var primary = primaryTrackers[0];
                int[] dirty = primary.dirtySlots();
                int dirtyN = primary.dirtyCount();

                for (int d = 0; d < dirtyN; d++) {
                    int slot = dirty[d];
                    if (slot >= count) continue;

                    if (!checkAllFilterGroups(slot)) continue;
                    processSlot(chunk, slot, invoker, currentTick);
                }
            } else {
                // Multi-target path: union dirty lists, deduplicate.
                var seen = new java.util.BitSet(count);
                for (var tracker : primaryTrackers) {
                    int[] dirty = tracker.dirtySlots();
                    int dirtyN = tracker.dirtyCount();
                    for (int d = 0; d < dirtyN; d++) {
                        int slot = dirty[d];
                        if (slot >= count) continue;
                        if (seen.get(slot)) continue;
                        seen.set(slot);

                        // Check this slot against ALL filter groups.
                        if (!checkAllFilterGroups(slot)) continue;
                        processSlot(chunk, slot, invoker, currentTick);
                    }
                }
            }
        } else {
            // Dense path: full slot scan. Unchanged from the pre-dirty-list
            // implementation; systems without change filters pay no bitmap
            // setup, no dirty-list bookkeeping.
            for (int slot = 0; slot < count; slot++) {
                processSlot(chunk, slot, invoker, currentTick);
            }
        }
    }

    /**
     * Check a slot against ALL filter groups using OR-within-group +
     * AND-across-groups semantics. Returns {@code true} if the slot
     * matches every group.
     */
    private boolean checkAllFilterGroups(int slot) {
        for (int i = 0; i < changeFilters.length; i++) {
            if (!checkFilterGroup(i, slot)) return false;
        }
        return true;
    }

    /**
     * Check a slot against filter groups 1..N (skipping group 0, which
     * was already used as the iteration source for single-target).
     */
    private boolean checkRemainingFilters(int slot) {
        for (int i = 1; i < changeFilters.length; i++) {
            if (!checkFilterGroup(i, slot)) return false;
        }
        return true;
    }

    /**
     * Check a single filter group against a slot. OR semantics: returns
     * {@code true} if ANY target in the group reports dirty since the
     * last seen tick.
     */
    private boolean checkFilterGroup(int groupIndex, int slot) {
        var cf = changeFilters[groupIndex];
        var trackers = cachedFilterTrackers[groupIndex];
        for (var tracker : trackers) {
            boolean ok = switch (cf.kind()) {
                case ADDED -> tracker.isAddedSince(slot, lastSeenTick);
                case CHANGED -> tracker.isChangedSince(slot, lastSeenTick);
            };
            if (ok) return true;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processSlot(Chunk chunk, int slot, SystemInvoker invoker, long currentTick) {
        // Fill args
        for (int i = 0; i < slots.length; i++) {
            var cs = slots[i];
            if (cs.isWrite()) {
                var value = cachedStorages[i].get(slot);
                var existing = (Mut) mutCache[i];
                if (existing == null) {
                    var mut = new Mut(value, slot, cachedTrackers[i], currentTick, cs.isValueTracked());
                    mutCache[i] = mut;
                    args[cs.argIndex()] = mut;
                } else {
                    existing.reset(value, slot, cachedTrackers[i], currentTick);
                }
            } else {
                args[cs.argIndex()] = cachedStorages[i].get(slot);
            }
        }

        // Fill Entity-typed parameters with the current iteration entity.
        if (entitySlotIndices.length > 0) {
            var entity = chunk.entity(slot);
            for (int i = 0; i < entitySlotIndices.length; i++) {
                args[entitySlotIndices[i]] = entity;
            }
        }

        // Check @Where filters — reuse a single lookup map per plan to avoid
        // per-entity HashMap allocation in the inner loop.
        if (whereLookup != null) {
            whereLookup.clear();
            for (int i = 0; i < slots.length; i++) {
                var cs = slots[i];
                var value = cs.isWrite() ? ((Mut) mutCache[i]).get() : args[cs.argIndex()];
                whereLookup.put(cs.access().type(), (Record) value);
            }
            boolean pass = true;
            for (var filter : whereFilters.values()) {
                if (!filter.test(whereLookup)) { pass = false; break; }
            }
            if (!pass) return;
        }

        // Invoke
        try {
            invoker.invoke(args);
        } catch (Throwable e) {
            throw new RuntimeException("System invocation failed at slot " + slot, e);
        }

        // Flush writes
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isWrite()) {
                var mut = (Mut) mutCache[i];
                var newValue = mut.flush();
                ((ComponentStorage) cachedStorages[i]).set(mut.slot(), newValue);
            }
        }
    }
}
