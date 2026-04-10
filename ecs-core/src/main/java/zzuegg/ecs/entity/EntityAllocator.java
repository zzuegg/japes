package zzuegg.ecs.entity;

import java.util.Arrays;

public final class EntityAllocator {

    // Primitive-backed state: boxed List<Integer>/List<Boolean> autoboxed on
    // every allocate/free. A pair of growable int[]/long[] arrays (plus an
    // int[]-backed free stack) is strictly cheaper on the spawn/despawn path
    // and friendlier to the JIT.
    private int[] generations = new int[16];
    private long[] aliveBits = new long[1]; // bit per slot
    private int capacity = 0;               // number of valid indices

    private int[] freeStack = new int[16];
    private int freeTop = 0;

    private int liveCount = 0;

    public Entity allocate() {
        int index;
        int generation;
        if (freeTop > 0) {
            index = freeStack[--freeTop];
            generation = generations[index];
        } else {
            ensureCapacity(capacity + 1);
            index = capacity++;
            generation = 0;
            generations[index] = 0;
        }
        setAlive(index, true);
        liveCount++;
        return Entity.of(index, generation);
    }

    public void free(Entity entity) {
        int index = entity.index();
        if (index < 0 || index >= capacity) {
            throw new IllegalArgumentException("Entity was never allocated: " + entity);
        }
        if (generations[index] != entity.generation() || !isAliveBit(index)) {
            throw new IllegalArgumentException("Stale or already freed entity: " + entity);
        }
        setAlive(index, false);
        generations[index] = entity.generation() + 1;
        if (freeTop == freeStack.length) {
            freeStack = Arrays.copyOf(freeStack, freeStack.length * 2);
        }
        freeStack[freeTop++] = index;
        liveCount--;
    }

    public boolean isAlive(Entity entity) {
        int index = entity.index();
        return index >= 0
            && index < capacity
            && generations[index] == entity.generation()
            && isAliveBit(index);
    }

    public int entityCount() {
        return liveCount;
    }

    private void ensureCapacity(int needed) {
        if (needed > generations.length) {
            int newLen = Math.max(generations.length * 2, needed);
            generations = Arrays.copyOf(generations, newLen);
        }
        int bitsNeeded = (needed + 63) >>> 6;
        if (bitsNeeded > aliveBits.length) {
            aliveBits = Arrays.copyOf(aliveBits, Math.max(aliveBits.length * 2, bitsNeeded));
        }
    }

    private void setAlive(int index, boolean alive) {
        int word = index >>> 6;
        long mask = 1L << (index & 63);
        if (alive) aliveBits[word] |= mask;
        else aliveBits[word] &= ~mask;
    }

    private boolean isAliveBit(int index) {
        return (aliveBits[index >>> 6] & (1L << (index & 63))) != 0L;
    }
}
