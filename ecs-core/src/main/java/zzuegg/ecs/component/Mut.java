package zzuegg.ecs.component;

import zzuegg.ecs.change.ChangeTracker;

public final class Mut<T extends Record> {

    private final T original;
    private T current;
    private final int slot;
    private final ChangeTracker tracker;
    private final long tick;
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

    public T get() {
        return current;
    }

    public void set(T value) {
        this.current = value;
        this.changed = true;
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
