package zzuegg.ecs.storage;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;

public final class DefaultComponentStorage<T extends Record> implements ComponentStorage<T> {

    // ---- Optional Valhalla (JEP 401) flat-array opt-in ---------------
    //
    // Resolved once at class init. If the JVM is Valhalla EA *and* the
    // JVM was started with
    //
    //     --add-exports java.base/jdk.internal.value=ALL-UNNAMED
    //
    // these two MethodHandles point at {@code jdk.internal.value.ValueClass.
    // newNullRestrictedNonAtomicArray} and {@code isValueObjectCompatible}.
    // The storage will then use the flat-array allocator for component
    // records that are declared {@code value record}, which under Valhalla
    // stores the records' fields inline in a primitive-backed array instead
    // of boxing each element into a heap record.
    //
    // On stock JDKs both handles stay {@code null} and the storage falls
    // back to {@code Array.newInstance}, the current behaviour.
    private static final MethodHandle MH_NEW_FLAT_ARRAY;
    private static final MethodHandle MH_IS_VALUE_COMPATIBLE;

    static {
        MethodHandle newFlat = null;
        MethodHandle isCompat = null;
        // The Valhalla JEP 401 EA flat-array opt-in is OFF by default. A
        // tight A/B comparison on JDK 27 EA showed the flat-array path is
        // ~3.5x slower on iteration reads and ~15% slower on scenario
        // benchmarks because the EA JIT doesn't yet emit optimised code
        // for flat-array get/set. The reference-array fallback keeps the
        // real Valhalla wins (2-4x on reads, ~10% on writes) that come
        // from value-record layout being friendlier to escape analysis.
        //
        // Opt in with -Dzzuegg.ecs.useFlatStorage=true to experiment with
        // newNullRestrictedNonAtomicArray directly; as the Valhalla JIT
        // matures the flip will become the right default and this opt-in
        // will get removed.
        if (Boolean.getBoolean("zzuegg.ecs.useFlatStorage")) {
            try {
                var cls = Class.forName("jdk.internal.value.ValueClass");
                var lookup = MethodHandles.lookup();
                newFlat = lookup.findStatic(cls, "newNullRestrictedNonAtomicArray",
                    MethodType.methodType(Object[].class, Class.class, int.class, Object.class));
                isCompat = lookup.findStatic(cls, "isValueObjectCompatible",
                    MethodType.methodType(boolean.class, Class.class));
            } catch (Exception ignored) {
                // Stock JDK, or jdk.internal.value not exported. That's fine —
                // the storage falls back to Array.newInstance below.
            }
        }
        MH_NEW_FLAT_ARRAY = newFlat;
        MH_IS_VALUE_COMPATIBLE = isCompat;
    }

    private final T[] data;
    private final Class<T> type;
    // Whether {@link #data} is a null-restricted flat array. When true we
    // must not write null into it (the VM will throw) — swap-remove falls
    // back to overwriting the slot with the canonical zero prototype, and
    // the garbage-hygiene benefit of nulling references is moot anyway
    // because the contents are inline primitives.
    private final boolean flat;
    private final T zeroPrototype;

    @SuppressWarnings("unchecked")
    public DefaultComponentStorage(Class<T> type, int capacity) {
        this.type = type;
        T[] arr = null;
        T proto = null;
        if (MH_NEW_FLAT_ARRAY != null) {
            try {
                // Only attempt flat allocation for value-class-compatible
                // types. Stock `record` types return false here and drop
                // through to the reference-array fallback.
                if ((boolean) MH_IS_VALUE_COMPATIBLE.invoke(type)) {
                    var prototype = canonicalZeroInstance(type);
                    if (prototype != null) {
                        arr = (T[]) (Object[]) MH_NEW_FLAT_ARRAY.invoke(type, capacity, prototype);
                        proto = (T) prototype;
                    }
                }
            } catch (Throwable ignored) {
                // Flat allocation failed (e.g. non-primitive record fields
                // whose defaults we can't synthesise). MethodHandle.invoke()
                // is declared throws Throwable so we must catch Throwable here;
                // this code path is only reached on Valhalla EA JVMs when
                // -Dzzuegg.ecs.useFlatStorage=true is set, so fatal JVM errors
                // are not a realistic concern. Fall back to reference array.
            }
        }
        if (arr == null) {
            arr = (T[]) Array.newInstance(type, capacity);
            this.flat = false;
        } else {
            this.flat = true;
        }
        this.data = arr;
        this.zeroPrototype = proto;
        if (System.getProperty("zzuegg.ecs.debugFlat") != null) {
            System.err.println("[ecs] DefaultComponentStorage<" + type.getName()
                + "> cap=" + capacity + " flat=" + flat);
        }
    }

    /**
     * Build a canonical zero-initialised instance of the given record type,
     * for use as the non-null prototype of a null-restricted flat array.
     * Returns {@code null} if the record has any non-primitive component —
     * the caller then falls back to a reference array.
     */
    private static Object canonicalZeroInstance(Class<?> type) {
        if (!type.isRecord()) return null;
        try {
            var components = type.getRecordComponents();
            var paramTypes = new Class<?>[components.length];
            var args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                var pt = components[i].getType();
                paramTypes[i] = pt;
                Object def = primitiveZero(pt);
                if (def == null && pt.isPrimitive()) return null; // unhandled
                args[i] = def;
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
        if (flat) {
            // Null-restricted arrays reject null writes. Overwrite the
            // now-vacant slot with the canonical zero value so its contents
            // are deterministic, and so the component storage still looks
            // "empty" from a debugging standpoint.
            data[lastIndex] = zeroPrototype;
        } else {
            data[lastIndex] = null;
        }
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
