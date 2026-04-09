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
        this.original = value;
        this.current = value;
        this.slot = slot;
        this.tracker = tracker;
        this.tick = tick;
        this.valueTracked = valueTracked;
        this.changed = false;
    }

    public void reset(T value, int newSlot, ChangeTracker newTracker, long newTick) {
        this.original = value;
        this.current = value;
        this.slot = newSlot;
        this.tracker = newTracker;
        this.tick = newTick;
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
