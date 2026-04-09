package zzuegg.ecs.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class EntityAllocator {

    private final List<Integer> generations = new ArrayList<>();
    private final List<Boolean> alive = new ArrayList<>();
    private final Deque<Integer> freeList = new ArrayDeque<>();
    private int liveCount = 0;

    public Entity allocate() {
        int index;
        int generation;
        if (!freeList.isEmpty()) {
            index = freeList.pop();
            generation = generations.get(index);
        } else {
            index = generations.size();
            generation = 0;
            generations.add(0);
            alive.add(false);
        }
        alive.set(index, true);
        liveCount++;
        return Entity.of(index, generation);
    }

    public void free(Entity entity) {
        int index = entity.index();
        if (index < 0 || index >= generations.size()) {
            throw new IllegalArgumentException("Entity was never allocated: " + entity);
        }
        if (generations.get(index) != entity.generation() || !alive.get(index)) {
            throw new IllegalArgumentException("Stale or already freed entity: " + entity);
        }
        alive.set(index, false);
        generations.set(index, entity.generation() + 1);
        freeList.push(index);
        liveCount--;
    }

    public boolean isAlive(Entity entity) {
        int index = entity.index();
        return index >= 0
            && index < generations.size()
            && generations.get(index) == entity.generation()
            && alive.get(index);
    }

    public int entityCount() {
        return liveCount;
    }
}
