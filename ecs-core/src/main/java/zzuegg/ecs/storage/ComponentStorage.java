package zzuegg.ecs.storage;

public interface ComponentStorage<T extends Record> {

    T get(int index);
    void set(int index, T value);
    void swapRemove(int index, int count);
    void copyInto(int srcIndex, ComponentStorage<T> dst, int dstIndex);
    int capacity();
    Class<T> type();

    @FunctionalInterface
    interface Factory {
        <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity);
    }

    static <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
        return defaultFactory().create(type, capacity);
    }

    static Factory defaultFactory() {
        return DefaultComponentStorage::new;
    }
}
