package zzuegg.ecs.persistence;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-derived binary codec for record types. Encodes each field in
 * declaration order using DataOutput primitives. Supports nested records
 * recursively — each nested record is encoded inline (no length prefix).
 *
 * <p>This codec handles all primitive types: byte, short, int, long,
 * float, double, boolean, char. Reference fields (String, Object, etc.)
 * are not supported — use a custom {@link ComponentCodec} for those.
 */
public final class BinaryCodec<T extends Record> implements ComponentCodec<T> {

    private final Class<T> type;
    private final List<FieldCodec> fieldCodecs;
    private final MethodHandle constructor;

    @SuppressWarnings("unchecked")
    public BinaryCodec(Class<T> type) {
        this.type = type;
        var components = type.getRecordComponents();
        this.fieldCodecs = new ArrayList<>(components.length);
        var paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            fieldCodecs.add(createFieldCodec(components[i]));
        }
        try {
            var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            var ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            this.constructor = lookup.unreflectConstructor(ctor);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot access constructor for " + type.getName(), e);
        }
    }

    @Override
    public void encode(T value, DataOutput out) throws IOException {
        for (var fc : fieldCodecs) {
            fc.encode(value, out);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public T decode(DataInput in) throws IOException {
        var args = new Object[fieldCodecs.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = fieldCodecs.get(i).decode(in);
        }
        try {
            return (T) constructor.invokeWithArguments(args);
        } catch (Throwable e) {
            throw new IOException("Failed to construct " + type.getName(), e);
        }
    }

    @Override
    public Class<T> type() {
        return type;
    }

    private static FieldCodec createFieldCodec(RecordComponent comp) {
        var fieldType = comp.getType();
        MethodHandle accessor;
        try {
            var method = comp.getAccessor();
            method.setAccessible(true);
            accessor = MethodHandles.privateLookupIn(comp.getDeclaringRecord(), MethodHandles.lookup())
                .unreflect(method);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot access " + comp.getName(), e);
        }

        if (fieldType == byte.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeByte((byte) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readByte(); }
        };
        if (fieldType == short.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeShort((short) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readShort(); }
        };
        if (fieldType == int.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeInt((int) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readInt(); }
        };
        if (fieldType == long.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeLong((long) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readLong(); }
        };
        if (fieldType == float.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeFloat((float) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readFloat(); }
        };
        if (fieldType == double.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeDouble((double) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readDouble(); }
        };
        if (fieldType == boolean.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeBoolean((boolean) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readBoolean(); }
        };
        if (fieldType == char.class) return new PrimitiveFieldCodec(accessor) {
            @Override void writeValue(Object val, DataOutput out) throws IOException { out.writeChar((char) val); }
            @Override Object readValue(DataInput in) throws IOException { return in.readChar(); }
        };
        if (fieldType.isRecord()) {
            @SuppressWarnings("unchecked")
            var nestedCodec = new BinaryCodec<>((Class<? extends Record>) fieldType);
            return new NestedRecordFieldCodec(accessor, nestedCodec);
        }
        throw new IllegalArgumentException(
            "Unsupported field type: " + fieldType.getName() + " in " + comp.getDeclaringRecord().getName());
    }

    private interface FieldCodec {
        void encode(Object record, DataOutput out) throws IOException;
        Object decode(DataInput in) throws IOException;
    }

    private static abstract class PrimitiveFieldCodec implements FieldCodec {
        private final MethodHandle accessor;
        PrimitiveFieldCodec(MethodHandle accessor) { this.accessor = accessor; }

        @Override
        public void encode(Object record, DataOutput out) throws IOException {
            try {
                writeValue(accessor.invoke(record), out);
            } catch (IOException e) { throw e; }
            catch (Throwable e) { throw new IOException("accessor failed", e); }
        }

        abstract void writeValue(Object val, DataOutput out) throws IOException;
        abstract Object readValue(DataInput in) throws IOException;

        @Override
        public Object decode(DataInput in) throws IOException {
            return readValue(in);
        }
    }

    private static final class NestedRecordFieldCodec implements FieldCodec {
        private final MethodHandle accessor;
        private final BinaryCodec<? extends Record> nestedCodec;
        NestedRecordFieldCodec(MethodHandle accessor, BinaryCodec<? extends Record> nestedCodec) {
            this.accessor = accessor;
            this.nestedCodec = nestedCodec;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void encode(Object record, DataOutput out) throws IOException {
            try {
                var nested = (Record) accessor.invoke(record);
                ((BinaryCodec) nestedCodec).encode(nested, out);
            } catch (IOException e) { throw e; }
            catch (Throwable e) { throw new IOException("accessor failed", e); }
        }

        @Override
        public Object decode(DataInput in) throws IOException {
            return nestedCodec.decode(in);
        }
    }
}
