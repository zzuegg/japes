package zzuegg.ecs.storage;

public interface ComponentStorage<T extends Record> {

    T get(int index);
    void set(int index, T value);
    void swapRemove(int index, int count);
    void copyInto(int srcIndex, ComponentStorage<T> dst, int dstIndex);
    int capacity();
    Class<T> type();

    /**
     * For SoA storages: returns the per-field primitive arrays as an
     * {@code Object[]} (each element is e.g. {@code float[]},
     * {@code int[]}). The tier-1 generator uses this to emit direct
     * per-field array reads/writes, bypassing interface dispatch.
     * Returns {@code null} for non-SoA storages.
     */
    default Object[] soaFieldArrays() { return null; }

    /**
     * Storage factory. Not a @FunctionalInterface: because create() is
     * itself generic, a lambda cannot express the signature — only method
     * references (e.g., {@code DefaultComponentStorage::new}) work.
     */
    interface Factory {
        <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity);
    }

    static <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
        return defaultFactory().create(type, capacity);
    }

    static Factory defaultFactory() {
        return new SoAComponentStorage.SoAFactory();
    }
}
