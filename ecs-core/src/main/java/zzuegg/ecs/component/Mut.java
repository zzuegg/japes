package zzuegg.ecs.component;

import zzuegg.ecs.change.ChangeTracker;

public class Mut<T extends Record> {

    private T original;
    private T current;       // set once in constructor, never overwritten
    private T pending;       // written by set(), null if unchanged
    // Widened to public so generated hidden-class Mut subclasses in other
    // packages can putfield these directly. Not part of the public API.
    public int slot;
    public ChangeTracker tracker;
    public long tick;
    private final boolean valueTracked;
    public boolean changed;

    public Mut(T value, int slot, ChangeTracker tracker, long tick, boolean valueTracked) {
        if (valueTracked) this.original = value;
        this.current = value;
        // pending stays null -- no write needed
        this.slot = slot;
        this.tracker = tracker;
        this.tick = tick;
        this.valueTracked = valueTracked;
        this.changed = false;
    }

    /** Protected no-arg constructor for generated specialised subclasses. */
    protected Mut() {
        this.valueTracked = false;
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

    public void setContext(ChangeTracker newTracker, long newTick) {
        this.tracker = newTracker;
        this.tick = newTick;
    }

    public void resetValue(T value, int newSlot) {
        if (valueTracked) this.original = value;
        this.current = value;
        this.pending = null;
        this.slot = newSlot;
        this.changed = false;
    }

    public T get() {
        return changed ? pending : current;
    }

    public void set(T value) {
        this.pending = value;
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
        if (valueTracked && original.equals(pending)) {
            changed = false;
            return current;
        }
        tracker.markChanged(slot, tick);
        return pending;
    }
}
