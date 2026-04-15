package zzuegg.ecs.bench.valhalla.storage;

import zzuegg.ecs.storage.ComponentStorage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;

/**
 * Valhalla-aware component storage that uses null-restricted flat arrays
 * for value-class-compatible record types. Falls back to reference arrays
 * for non-value types.
 *
 * <p>Requires JDK 27 EA (JEP 401) with:
 * {@code --add-exports java.base/jdk.internal.value=ALL-UNNAMED}
 *
 * <p>Use via {@link ValhallaStorageFactory} as a {@code ComponentStorage.Factory}.
 */
public final class ValhallaComponentStorage<T extends Record> implements ComponentStorage<T> {

    private static final MethodHandle MH_NEW_FLAT_ARRAY;
    private static final MethodHandle MH_IS_VALUE_COMPATIBLE;

    static {
        MethodHandle newFlat = null;
        MethodHandle isCompat = null;
        try {
            var cls = Class.forName("jdk.internal.value.ValueClass");
            var lookup = MethodHandles.lookup();
            newFlat = lookup.findStatic(cls, "newNullRestrictedNonAtomicArray",
                MethodType.methodType(Object[].class, Class.class, int.class, Object.class));
            isCompat = lookup.findStatic(cls, "isValueObjectCompatible",
                MethodType.methodType(boolean.class, Class.class));
        } catch (Exception ignored) {
            // Not on Valhalla JVM — factory should not be used
        }
        MH_NEW_FLAT_ARRAY = newFlat;
        MH_IS_VALUE_COMPATIBLE = isCompat;
    }

    private final T[] data;
    private final Class<T> type;
    private final boolean flat;
    private final T zeroPrototype;

    @SuppressWarnings("unchecked")
    public ValhallaComponentStorage(Class<T> type, int capacity) {
        this.type = type;
        T[] arr = null;
        T proto = null;
        if (MH_NEW_FLAT_ARRAY != null) {
            try {
                if ((boolean) MH_IS_VALUE_COMPATIBLE.invoke(type)) {
                    var prototype = canonicalZeroInstance(type);
                    if (prototype != null) {
                        arr = (T[]) (Object[]) MH_NEW_FLAT_ARRAY.invoke(type, capacity, prototype);
                        proto = (T) prototype;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (arr == null) {
            arr = (T[]) Array.newInstance(type, capacity);
            this.flat = false;
        } else {
            this.flat = true;
        }
        this.data = arr;
        this.zeroPrototype = proto;
    }

    public static boolean isAvailable() {
        return MH_NEW_FLAT_ARRAY != null;
    }

    @Override public T get(int index) { return data[index]; }
    @Override public void set(int index, T value) { data[index] = value; }

    @Override
    public void swapRemove(int index, int count) {
        int lastIndex = count - 1;
        if (index < lastIndex) data[index] = data[lastIndex];
        if (flat) {
            data[lastIndex] = zeroPrototype;
        } else {
            data[lastIndex] = null;
        }
    }

    @Override
    public void copyInto(int srcIndex, ComponentStorage<T> dst, int dstIndex) {
        dst.set(dstIndex, data[srcIndex]);
    }

    @Override public int capacity() { return data.length; }
    @Override public Class<T> type() { return type; }

    public T[] rawArray() { return data; }
    public boolean isFlat() { return flat; }

    private static Object canonicalZeroInstance(Class<?> type) {
        if (!type.isRecord()) return null;
        try {
            var components = type.getRecordComponents();
            var paramTypes = new Class<?>[components.length];
            var args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var pt = components[i].getType();
                paramTypes[i] = pt;
                args[i] = primitiveZero(pt);
                if (args[i] == null && pt.isPrimitive()) return null;
            }
            Constructor<?> ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object primitiveZero(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == byte.class)    return (byte) 0;
        if (t == short.class)   return (short) 0;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        if (t == float.class)   return 0.0f;
        if (t == double.class)  return 0.0d;
        if (t == char.class)    return '\0';
        return null;
    }
}
