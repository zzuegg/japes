package zzuegg.ecs.storage;

import java.lang.reflect.Array;

public final class DefaultComponentStorage<T extends Record> implements ComponentStorage<T> {

    private final T[] data;
    private final Class<T> type;

    @SuppressWarnings("unchecked")
    public DefaultComponentStorage(Class<T> type, int capacity) {
        this.type = type;
        this.data = (T[]) Array.newInstance(type, capacity);
    }

    @Override
    public T get(int index) {
        return data[index];
    }

    @Override
    public void set(int index, T value) {
        data[index] = value;
    }

    @Override
    public void swapRemove(int index, int count) {
        int lastIndex = count - 1;
        if (index < lastIndex) {
            data[index] = data[lastIndex];
        }
        data[lastIndex] = null;
    }

    @Override
    public void copyInto(int srcIndex, ComponentStorage<T> dst, int dstIndex) {
        dst.set(dstIndex, data[srcIndex]);
    }

    @Override
    public int capacity() {
        return data.length;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    public T[] rawArray() {
        return data;
    }
}
