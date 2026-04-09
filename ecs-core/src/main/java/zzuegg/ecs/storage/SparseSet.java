package zzuegg.ecs.storage;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SparseSet<T extends Record> {

    private static final int ABSENT = -1;

    private int[] sparse = new int[0];
    private final List<Entity> denseEntities = new ArrayList<>();
    private final List<T> denseValues = new ArrayList<>();

    public void insert(Entity entity, T value) {
        int index = entity.index();
        ensureSparseCapacity(index + 1);

        if (sparse[index] != ABSENT) {
            denseValues.set(sparse[index], value);
            return;
        }

        sparse[index] = denseEntities.size();
        denseEntities.add(entity);
        denseValues.add(value);
    }

    public T get(Entity entity) {
        int index = entity.index();
        if (index >= sparse.length || sparse[index] == ABSENT) {
            return null;
        }
        return denseValues.get(sparse[index]);
    }

    public boolean contains(Entity entity) {
        int index = entity.index();
        return index < sparse.length && sparse[index] != ABSENT;
    }

    public void remove(Entity entity) {
        int index = entity.index();
        if (index >= sparse.length || sparse[index] == ABSENT) {
            return;
        }

        int denseIndex = sparse[index];
        int lastDense = denseEntities.size() - 1;

        if (denseIndex < lastDense) {
            Entity lastEntity = denseEntities.get(lastDense);
            denseEntities.set(denseIndex, lastEntity);
            denseValues.set(denseIndex, denseValues.get(lastDense));
            sparse[lastEntity.index()] = denseIndex;
        }

        denseEntities.removeLast();
        denseValues.removeLast();
        sparse[index] = ABSENT;
    }

    public int size() {
        return denseEntities.size();
    }

    public List<Entity> entities() {
        return denseEntities;
    }

    public List<T> values() {
        return denseValues;
    }

    private void ensureSparseCapacity(int required) {
        if (required > sparse.length) {
            int newSize = Math.max(required, sparse.length * 2);
            int oldLength = sparse.length;
            sparse = Arrays.copyOf(sparse, newSize);
            Arrays.fill(sparse, oldLength, newSize, ABSENT);
        }
    }
}
