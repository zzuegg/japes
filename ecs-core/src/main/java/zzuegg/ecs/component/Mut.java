package zzuegg.ecs.component;

import zzuegg.ecs.change.ChangeTracker;

public final class Mut<T extends Record> {

    private T original;
    private T current;
    private int slot;
    private ChangeTracker tracker;
    private long tick;
    private final boolean valueTracked;
    private boolean changed;

    public Mut(T value, int slot, ChangeTracker tracker, long tick, boolean valueTracked) {
        if (valueTracked) this.original = value;
        this.current = value;
        this.slot = slot;
        this.tracker = tracker;
        this.tick = tick;
        this.valueTracked = valueTracked;
        this.changed = false;
    }

    /**
     * Legacy per-entity reset with all four fields. Kept so tier-2/tier-3
     * fallbacks that haven't been refactored to the split shape still compile.
     */
    public void reset(T value, int newSlot, ChangeTracker newTracker, long newTick) {
        setContext(newTracker, newTick);
        resetValue(value, newSlot);
    }

    /**
     * Set the per-chunk context — the ChangeTracker and the current tick are
     * stable across all entities in a chunk, so tier-1 calls this once before
     * the iteration loop instead of paying the stores per entity.
     */
    public void setContext(ChangeTracker newTracker, long newTick) {
        this.tracker = newTracker;
        this.tick = newTick;
    }

    /**
     * Per-entity reset — updates only the fields that change between
     * iterations. Call after {@link #setContext} in the tier-1 hot loop.
     */
    public void resetValue(T value, int newSlot) {
        if (valueTracked) this.original = value;
        this.current = value;
        this.slot = newSlot;
        this.changed = false;
    }

    public T get() {
        return current;
    }

    public void set(T value) {
        this.current = value;
        this.changed = true;
    }

    public int slot() {
        return slot;
    }

    public boolean isChanged() {
        return changed;
    }

    public T flush() {
        if (!changed) {
            return current;
        }
        if (valueTracked && original.equals(current)) {
            changed = false;
            return current;
        }
        tracker.markChanged(slot, tick);
        return current;
    }
}
