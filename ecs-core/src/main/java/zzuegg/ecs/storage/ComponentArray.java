package zzuegg.ecs.storage;

import java.lang.reflect.Array;

public final class ComponentArray<T extends Record> {

    private final T[] data;
    private final Class<T> type;

    @SuppressWarnings("unchecked")
    public ComponentArray(Class<T> type, int capacity) {
        this.type = type;
        this.data = (T[]) Array.newInstance(type, capacity);
    }

    public T get(int index) {
        checkBounds(index);
        return data[index];
    }

    public void set(int index, T value) {
        checkBounds(index);
        data[index] = value;
    }

    public void swapRemove(int index, int count) {
        checkBounds(index);
        int lastIndex = count - 1;
        if (index < lastIndex) {
            data[index] = data[lastIndex];
        }
        data[lastIndex] = null;
    }

    public void copyInto(int srcIndex, ComponentArray<T> dst, int dstIndex) {
        dst.set(dstIndex, this.get(srcIndex));
    }

    public int capacity() {
        return data.length;
    }

    public Class<T> type() {
        return type;
    }

    public T[] rawArray() {
        return data;
    }

    private void checkBounds(int index) {
        if (index < 0 || index >= data.length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for capacity " + data.length);
        }
    }
}
