package zzuegg.ecs.relation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Per-pair change tracker for one relation type. Parallels
 * {@code zzuegg.ecs.change.ChangeTracker} but keyed on
 * {@link PairKey} identity instead of archetype slot.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code markAdded} / {@code markChanged} set the added/changed
 *       tick for the key and add it to the dirty list at most once
 *       (dedup via membership set).</li>
 *   <li>{@code addedTick} / {@code changedTick} return {@code 0} for
 *       keys the tracker has never seen (matches the component-level
 *       "never set" sentinel).</li>
 *   <li>{@code pruneDirtyList(minWatermark)} drops dirty-list entries
 *       whose max(addedTick, changedTick) is {@code <= minWatermark}.</li>
 *   <li>{@code remove} clears both ticks and evicts the key from the
 *       dirty list.</li>
 * </ul>
 */
public final class PairChangeTracker {

    private final Map<PairKey, Long> addedTicks = new HashMap<>();
    private final Map<PairKey, Long> changedTicks = new HashMap<>();
    private final List<PairKey> dirtyList = new ArrayList<>();
    private final Set<PairKey> dirtyMembership = new HashSet<>();
    // When true, every mark/remove call becomes a no-op — the per-
    // pair tick maps and dirty list are never touched. Set at plan
    // build time by {@code World} when no system declares an
    // {@code @Filter(Added/Changed, target = T.class)} observer
    // over the relation type this tracker belongs to; every
    // bookkeeping call is pure waste in that case.
    private boolean fullyUntracked = false;

    public void setFullyUntracked(boolean untracked) {
        this.fullyUntracked = untracked;
    }

    public boolean isFullyUntracked() {
        return fullyUntracked;
    }

    public long addedTick(PairKey key) {
        var t = addedTicks.get(key);
        return t == null ? 0L : t;
    }

    public long changedTick(PairKey key) {
        var t = changedTicks.get(key);
        return t == null ? 0L : t;
    }

    public void markAdded(PairKey key, long tick) {
        if (fullyUntracked) return;
        addedTicks.put(key, tick);
        appendDirty(key);
    }

    public void markChanged(PairKey key, long tick) {
        if (fullyUntracked) return;
        changedTicks.put(key, tick);
        appendDirty(key);
    }

    public int dirtyCount() {
        return dirtyList.size();
    }

    public void forEachDirty(Consumer<PairKey> consumer) {
        for (var k : dirtyList) consumer.accept(k);
    }

    /**
     * Drop dirty-list entries whose max tick is {@code <= minWatermark}.
     * Entries whose key has since been {@link #remove removed} are also
     * dropped.
     */
    public void pruneDirtyList(long minWatermark) {
        var kept = new ArrayList<PairKey>(dirtyList.size());
        var keptSet = new HashSet<PairKey>(dirtyList.size() * 2);
        for (var k : dirtyList) {
            var added = addedTicks.get(k);
            var changed = changedTicks.get(k);
            if (added == null && changed == null) continue;
            long max = 0L;
            if (added != null) max = Math.max(max, added);
            if (changed != null) max = Math.max(max, changed);
            if (max > minWatermark) {
                kept.add(k);
                keptSet.add(k);
            }
        }
        dirtyList.clear();
        dirtyList.addAll(kept);
        dirtyMembership.clear();
        dirtyMembership.addAll(keptSet);
    }

    /**
     * Forget the key entirely — clear ticks and drop from the dirty
     * list. Used by the removal path of the relation store.
     */
    public void remove(PairKey key) {
        if (fullyUntracked) return;
        addedTicks.remove(key);
        changedTicks.remove(key);
        if (dirtyMembership.remove(key)) {
            dirtyList.remove(key);
        }
    }

    /** Drop all ticks and dirty-list state. */
    public void clear() {
        addedTicks.clear();
        changedTicks.clear();
        dirtyList.clear();
        dirtyMembership.clear();
    }

    private void appendDirty(PairKey key) {
        if (dirtyMembership.add(key)) {
            dirtyList.add(key);
        }
    }
}
