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

public final class SystemExecutionPlan {

    public record ParamSlot(int argIndex, ComponentAccess access, boolean isWrite, boolean isValueTracked) {}

    public enum FilterKind { ADDED, CHANGED }

    public record ResolvedChangeFilter(ComponentId targetId, FilterKind kind) {}

    private final Object[] args;
    private final ParamSlot[] slots; // array for indexed access, no List overhead
    private final Mut<?>[] mutCache;

    // Per-chunk cached references — set once per chunk, used for all entities
    private final ComponentStorage<?>[] cachedStorages;
    private final ChangeTracker[] cachedTrackers;
    private final Map<Integer, FieldFilter> whereFilters;

    // Change-filter state: resolved filters + per-chunk tracker cache + per-system
    // "last seen" tick so isAddedSince / isChangedSince compare against the tick
    // at which the system last ran.
    private final ResolvedChangeFilter[] changeFilters;
    private final ChangeTracker[] cachedFilterTrackers;
    private long lastSeenTick = 0;

    // Reusable component map for @Where evaluation — populated per entity from
    // the cached slot refs, cleared and refilled instead of allocated. Only
    // instantiated if whereFilters is non-empty so systems without @Where stay
    // allocation-free on the hot path.
    private final HashMap<Class<?>, Record> whereLookup;

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
        this.cachedFilterTrackers = new ChangeTracker[this.changeFilters.length];
        this.whereLookup = whereFilters.isEmpty() ? null : new HashMap<>();
    }

    public boolean hasChangeFilters() {
        return changeFilters.length > 0;
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
            var compId = slots[i].access.componentId();
            cachedStorages[i] = chunk.componentStorage(compId);
            if (slots[i].isWrite) {
                cachedTrackers[i] = chunk.changeTracker(compId);
            }
        }
        for (int i = 0; i < changeFilters.length; i++) {
            cachedFilterTrackers[i] = chunk.changeTracker(changeFilters[i].targetId());
        }
    }

    /**
     * Fill component args using cached storage references. Zero HashMap lookups.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fillComponentArgs(int slot, long currentTick) {
        for (int i = 0; i < slots.length; i++) {
            var cs = slots[i];
            if (cs.isWrite) {
                var value = cachedStorages[i].get(slot);
                var existing = (Mut) mutCache[i];
                if (existing == null) {
                    var mut = new Mut(value, slot, cachedTrackers[i], currentTick, cs.isValueTracked);
                    mutCache[i] = mut;
                    args[cs.argIndex] = mut;
                } else {
                    existing.reset(value, slot, cachedTrackers[i], currentTick);
                }
            } else {
                args[cs.argIndex] = cachedStorages[i].get(slot);
            }
        }
    }

    /**
     * Flush Mut values back using cached storage references.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void flushMuts() {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i].isWrite) {
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void processChunk(Chunk chunk, SystemInvoker invoker, long currentTick) {
        prepareChunk(chunk);
        int count = chunk.count();

        for (int slot = 0; slot < count; slot++) {
            // Change filters (@Filter(Added/Changed, target=X)): skip entities whose
            // target-component tracker is stale relative to the last tick this system ran.
            if (changeFilters.length > 0) {
                boolean match = true;
                for (int i = 0; i < changeFilters.length; i++) {
                    var cf = changeFilters[i];
                    var tracker = cachedFilterTrackers[i];
                    boolean ok = switch (cf.kind()) {
                        case ADDED -> tracker.isAddedSince(slot, lastSeenTick);
                        case CHANGED -> tracker.isChangedSince(slot, lastSeenTick);
                    };
                    if (!ok) { match = false; break; }
                }
                if (!match) continue;
            }

            // Fill args
            for (int i = 0; i < slots.length; i++) {
                var cs = slots[i];
                if (cs.isWrite) {
                    var value = cachedStorages[i].get(slot);
                    var existing = (Mut) mutCache[i];
                    if (existing == null) {
                        var mut = new Mut(value, slot, cachedTrackers[i], currentTick, cs.isValueTracked);
                        mutCache[i] = mut;
                        args[cs.argIndex] = mut;
                    } else {
                        existing.reset(value, slot, cachedTrackers[i], currentTick);
                    }
                } else {
                    args[cs.argIndex] = cachedStorages[i].get(slot);
                }
            }

            // Check @Where filters — reuse a single lookup map per plan to avoid
            // per-entity HashMap allocation in the inner loop.
            if (whereLookup != null) {
                whereLookup.clear();
                for (int i = 0; i < slots.length; i++) {
                    var cs = slots[i];
                    var value = cs.isWrite ? ((Mut) mutCache[i]).get() : args[cs.argIndex];
                    whereLookup.put(cs.access.type(), (Record) value);
                }
                boolean pass = true;
                for (var filter : whereFilters.values()) {
                    if (!filter.test(whereLookup)) { pass = false; break; }
                }
                if (!pass) continue;
            }

            // Invoke
            try {
                invoker.invoke(args);
            } catch (Throwable e) {
                throw new RuntimeException("System invocation failed at slot " + slot, e);
            }

            // Flush writes
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].isWrite) {
                    var mut = (Mut) mutCache[i];
                    var newValue = mut.flush();
                    ((ComponentStorage) cachedStorages[i]).set(mut.slot(), newValue);
                }
            }
        }
    }
}
