package zzuegg.ecs.change;

public final class ChangeTracker {

    private final long[] addedTicks;
    private final long[] changedTicks;

    public ChangeTracker(int capacity) {
        this.addedTicks = new long[capacity];
        this.changedTicks = new long[capacity];
    }

    public void markAdded(int slot, long tick) {
        addedTicks[slot] = tick;
        // Intentionally not updating changedTicks: 'added' and 'changed' are
        // independent in Bevy-style change detection. Newly spawned entities
        // are observable via @Filter(Added) but not @Filter(Changed) until
        // something actually mutates them.
    }

    public void markChanged(int slot, long tick) {
        changedTicks[slot] = tick;
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

    public void swapRemove(int slot, int count) {
        int last = count - 1;
        if (slot < last) {
            addedTicks[slot] = addedTicks[last];
            changedTicks[slot] = changedTicks[last];
        }
        addedTicks[last] = 0;
        changedTicks[last] = 0;
    }

    public int capacity() {
        return addedTicks.length;
    }
}
