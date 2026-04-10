package zzuegg.ecs.change;

import java.util.Arrays;

public final class ChangeTracker {

    private final long[] addedTicks;
    private final long[] changedTicks;

    // Dedup'd dirty-slot list. Every markAdded/markChanged call ensures the
    // slot is present at most once so @Filter(Added/Changed) iteration cost
    // scales with the number of distinct dirty slots, not the number of
    // writes since the last prune.
    //
    // dirtyBits is a one-bit-per-slot membership check: set on first append,
    // cleared when the prune pass drops a slot. The list holds the slot
    // indices in insertion order so iteration is cache-friendly.
    private final long[] dirtyBits;
    private int[] dirtyList = new int[16];
    private int dirtyCount = 0;
    // Dirty-list bookkeeping is opt-in per tracker. When no @Filter system
    // observes this component, markAdded/markChanged skip the append entirely
    // so pure-write workloads don't pay ~3ns per mark. World flips this when
    // a plan registers a filter targeting the component.
    private boolean dirtyTracked = false;

    public ChangeTracker(int capacity) {
        this.addedTicks = new long[capacity];
        this.changedTicks = new long[capacity];
        this.dirtyBits = new long[(capacity + 63) >>> 6];
    }

    /**
     * Enable (or disable) dirty-list bookkeeping for this tracker. Enabling
     * also seeds the dirty list from the existing tick arrays so slots that
     * were written before tracking was turned on remain visible to the first
     * filter run — otherwise a system registered mid-world would miss any
     * pre-existing state.
     */
    public void setDirtyTracked(boolean tracked) {
        if (this.dirtyTracked == tracked) return;
        this.dirtyTracked = tracked;
        if (tracked) {
            for (int slot = 0; slot < addedTicks.length; slot++) {
                if (addedTicks[slot] != 0L || changedTicks[slot] != 0L) {
                    appendDirtyUnchecked(slot);
                }
            }
        }
    }

    public boolean isDirtyTracked() {
        return dirtyTracked;
    }

    public void markAdded(int slot, long tick) {
        addedTicks[slot] = tick;
        // Intentionally not updating changedTicks: 'added' and 'changed' are
        // independent in Bevy-style change detection. Newly spawned entities
        // are observable via @Filter(Added) but not @Filter(Changed) until
        // something actually mutates them.
        appendDirty(slot);
    }

    public void markChanged(int slot, long tick) {
        changedTicks[slot] = tick;
        appendDirty(slot);
    }

    private void appendDirty(int slot) {
        if (!dirtyTracked) return;  // short-circuit untracked components
        appendDirtyUnchecked(slot);
    }

    private void appendDirtyUnchecked(int slot) {
        int word = slot >>> 6;
        long mask = 1L << (slot & 63);
        if ((dirtyBits[word] & mask) != 0) return;  // already in the list
        dirtyBits[word] |= mask;
        if (dirtyCount == dirtyList.length) {
            dirtyList = Arrays.copyOf(dirtyList, dirtyList.length * 2);
        }
        dirtyList[dirtyCount++] = slot;
    }

    public long addedTick(int slot) {
        return addedTicks[slot];
    }

    public long changedTick(int slot) {
        return changedTicks[slot];
    }

    public boolean isAddedSince(int slot, long sinceExclusive) {
        return addedTicks[slot] > sinceExclusive;
    }

    public boolean isChangedSince(int slot, long sinceExclusive) {
        return changedTicks[slot] > sinceExclusive;
    }

    /**
     * Raw dirty-slot list — {@code dirtySlots()[0..dirtyCount()]} are the
     * distinct slots that were marked added or changed since the last prune.
     * Callers still must do the per-slot tick check to filter against their
     * own watermark.
     */
    public int[] dirtySlots() {
        return dirtyList;
    }

    public int dirtyCount() {
        return dirtyCount;
    }

    /**
     * Drop dirty-list entries whose addedTick and changedTick are both
     * {@code <=} the given watermark and clear their membership bits so the
     * next markAdded/markChanged re-adds them. Called by World at end of
     * tick with the minimum {@code lastSeenTick} across all systems that
     * observe this tracker's component via a change filter.
     */
    public void pruneDirtyList(long minWatermark) {
        int write = 0;
        for (int i = 0; i < dirtyCount; i++) {
            int slot = dirtyList[i];
            if (addedTicks[slot] > minWatermark || changedTicks[slot] > minWatermark) {
                dirtyList[write++] = slot;
            } else {
                dirtyBits[slot >>> 6] &= ~(1L << (slot & 63));
            }
        }
        dirtyCount = write;
    }

    public void swapRemove(int slot, int count) {
        int last = count - 1;
        if (slot < last) {
            addedTicks[slot] = addedTicks[last];
            changedTicks[slot] = changedTicks[last];
        }
        addedTicks[last] = 0;
        changedTicks[last] = 0;
        // Drop the removed slot from the dirty bitmap — the next real write
        // to that index will re-add it. The list itself may still contain a
        // stale entry pointing to `last`; the prune pass will drop it when
        // the tick check fails, and in the meantime `slot >= count` filters
        // it out of iteration.
        dirtyBits[last >>> 6] &= ~(1L << (last & 63));
    }

    public int capacity() {
        return addedTicks.length;
    }
}
