package zzuegg.ecs.storage;

import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.classfile.ClassFile.*;

/**
 * Auto-generated struct-of-arrays component storage for records with
 * all-primitive fields. Emits a hidden class per record type whose
 * {@code get(int)} / {@code set(int, T)} methods are direct primitive
 * array reads/writes — no reflection, no MethodHandle, no boxing.
 *
 * <p>This eliminates per-entity heap allocation on the write path:
 * the tier-1 generator's fresh-Mut-per-entity approach + SoA's
 * primitive array stores let EA scalar-replace both the Mut and the
 * record.
 */
public final class SoAComponentStorage {

    private static final AtomicLong COUNTER = new AtomicLong();

    private SoAComponentStorage() {}

    /**
     * Check if a record type is eligible for SoA storage:
     * all components must be primitive types.
     */
    public static boolean isEligible(Class<? extends Record> type) {
        var comps = type.getRecordComponents();
        if (comps == null || comps.length == 0) return false;
        for (var comp : comps) {
            if (!comp.getType().isPrimitive()) return false;
        }
        return true;
    }

    /**
     * Generate a SoA ComponentStorage hidden class for the given record type.
     * The generated class has one primitive array field per record component,
     * with get/set methods that do direct array I/O.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
        try {
            return doGenerate(type, capacity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate SoA storage for " + type.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> ComponentStorage<T> doGenerate(
            Class<T> type, int capacity) throws Exception {

        var comps = type.getRecordComponents();
        var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());

        var storageDesc = ClassDesc.of("zzuegg.ecs.storage.ComponentStorage");
        var recordDesc = ClassDesc.of("java.lang.Record");
        var typeDesc = type.describeConstable().orElseThrow();
        var classDesc = ClassDesc.of("java.lang.Class");

        var genName = type.getPackageName() + ".SoA_" + type.getSimpleName() + "_" + COUNTER.incrementAndGet();
        var genDesc = ClassDesc.of(genName);

        // Field descriptors for each record component's array type.
        var fieldDescs = new ClassDesc[comps.length];
        var fieldNames = new String[comps.length];
        for (int i = 0; i < comps.length; i++) {
            fieldNames[i] = "f_" + comps[i].getName();
            fieldDescs[i] = comps[i].getType().arrayType().describeConstable().orElseThrow();
        }

        // Accessor method descriptors (e.g., Position.x() → float)
        var accessorDescs = new MethodTypeDesc[comps.length];
        for (int i = 0; i < comps.length; i++) {
            accessorDescs[i] = MethodTypeDesc.of(
                comps[i].getType().describeConstable().orElseThrow());
        }

        // Constructor descriptor: (float, float, float) → void for Position
        var ctorParamDescs = new ClassDesc[comps.length];
        for (int i = 0; i < comps.length; i++) {
            ctorParamDescs[i] = comps[i].getType().describeConstable().orElseThrow();
        }
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ctorParamDescs);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(storageDesc));

            // Per-field arrays as instance fields.
            for (int i = 0; i < comps.length; i++) {
                clb.withField(fieldNames[i], fieldDescs[i], fb -> fb.withFlags(ACC_PRIVATE));
            }
            clb.withField("capacity", ConstantDescs.CD_int, fb -> fb.withFlags(ACC_PRIVATE | ACC_FINAL));
            clb.withField("type", classDesc, fb -> fb.withFlags(ACC_PRIVATE | ACC_FINAL));

            // Constructor: allocates the arrays.
            clb.withMethodBody("<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.invokespecial(ConstantDescs.CD_Object, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    cb.aload(0);
                    cb.iload(1);
                    cb.putfield(genDesc, "capacity", ConstantDescs.CD_int);
                    for (int i = 0; i < comps.length; i++) {
                        cb.aload(0);
                        cb.iload(1);
                        cb.newarray(java.lang.classfile.TypeKind.from(comps[i].getType()));
                        cb.putfield(genDesc, fieldNames[i], fieldDescs[i]);
                    }
                    cb.return_();
                });

            // get(int index) → T: reconstructs the record from per-field arrays.
            clb.withMethodBody("get",
                MethodTypeDesc.of(recordDesc, ConstantDescs.CD_int),
                ACC_PUBLIC, cb -> {
                    cb.new_(typeDesc);
                    cb.dup();
                    for (int i = 0; i < comps.length; i++) {
                        cb.aload(0);
                        cb.getfield(genDesc, fieldNames[i], fieldDescs[i]);
                        cb.iload(1);
                        emitArrayLoad(cb, comps[i].getType());
                    }
                    cb.invokespecial(typeDesc, "<init>", ctorDesc);
                    cb.areturn();
                });

            // set(int index, Record value): decomposes into per-field array stores.
            clb.withMethodBody("set",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, recordDesc),
                ACC_PUBLIC, cb -> {
                    for (int i = 0; i < comps.length; i++) {
                        cb.aload(0);
                        cb.getfield(genDesc, fieldNames[i], fieldDescs[i]);
                        cb.iload(1); // index
                        cb.aload(2); // value (Record)
                        cb.checkcast(typeDesc);
                        cb.invokevirtual(typeDesc, comps[i].getName(), accessorDescs[i]);
                        emitArrayStore(cb, comps[i].getType());
                    }
                    cb.return_();
                });

            // swapRemove(int index, int count)
            clb.withMethodBody("swapRemove",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_int),
                ACC_PUBLIC, cb -> {
                    var end = cb.newLabel();
                    cb.iload(1); // index
                    cb.iload(2); // count
                    cb.iconst_1();
                    cb.isub();   // last = count - 1
                    cb.dup();
                    var lastVar = 3;
                    cb.istore(lastVar);
                    cb.if_icmpge(end); // if index >= last, skip
                    for (int i = 0; i < comps.length; i++) {
                        cb.aload(0);
                        cb.getfield(genDesc, fieldNames[i], fieldDescs[i]);
                        cb.dup();
                        cb.iload(1); // index (dst)
                        cb.swap();
                        cb.iload(lastVar); // last (src)
                        emitArrayLoad(cb, comps[i].getType());
                        emitArrayStore(cb, comps[i].getType());
                    }
                    cb.labelBinding(end);
                    cb.return_();
                });

            // copyInto(int src, ComponentStorage dst, int dstI)
            clb.withMethodBody("copyInto",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
                    storageDesc, ConstantDescs.CD_int),
                ACC_PUBLIC, cb -> {
                    cb.aload(2); // dst
                    cb.iload(3); // dstI
                    cb.aload(0);
                    cb.iload(1);
                    cb.invokevirtual(genDesc, "get",
                        MethodTypeDesc.of(recordDesc, ConstantDescs.CD_int));
                    cb.invokeinterface(storageDesc, "set",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, recordDesc));
                    cb.return_();
                });

            // capacity()
            clb.withMethodBody("capacity",
                MethodTypeDesc.of(ConstantDescs.CD_int),
                ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(genDesc, "capacity", ConstantDescs.CD_int);
                    cb.ireturn();
                });

            // type()
            clb.withMethodBody("type",
                MethodTypeDesc.of(classDesc),
                ACC_PUBLIC, cb -> {
                    cb.ldc(typeDesc);
                    cb.areturn();
                });

            // soaFieldArrays() → Object[] of the per-field primitive arrays
            var objArrDesc = ConstantDescs.CD_Object.arrayType();
            clb.withMethodBody("soaFieldArrays",
                MethodTypeDesc.of(objArrDesc),
                ACC_PUBLIC, cb -> {
                    cb.ldc(comps.length);
                    cb.anewarray(ConstantDescs.CD_Object);
                    for (int i = 0; i < comps.length; i++) {
                        cb.dup();
                        cb.ldc(i);
                        cb.aload(0);
                        cb.getfield(genDesc, fieldNames[i], fieldDescs[i]);
                        cb.aastore();
                    }
                    cb.areturn();
                });
        });

        var hiddenLookup = lookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor(int.class).newInstance(capacity);
        return (ComponentStorage<T>) instance;
    }

    private static int arrayTypeCode(Class<?> type) {
        if (type == float.class) return 6;   // T_FLOAT
        if (type == int.class) return 10;    // T_INT
        if (type == double.class) return 7;  // T_DOUBLE
        if (type == long.class) return 11;   // T_LONG
        if (type == boolean.class) return 4; // T_BOOLEAN
        if (type == byte.class) return 8;    // T_BYTE
        if (type == short.class) return 9;   // T_SHORT
        if (type == char.class) return 5;    // T_CHAR
        throw new IllegalArgumentException("Not a primitive: " + type);
    }

    private static void emitArrayLoad(java.lang.classfile.CodeBuilder cb, Class<?> type) {
        if (type == float.class) cb.faload();
        else if (type == int.class) cb.iaload();
        else if (type == double.class) cb.daload();
        else if (type == long.class) cb.laload();
        else if (type == boolean.class || type == byte.class) cb.baload();
        else if (type == short.class) cb.saload();
        else if (type == char.class) cb.caload();
    }

    private static void emitArrayStore(java.lang.classfile.CodeBuilder cb, Class<?> type) {
        if (type == float.class) cb.fastore();
        else if (type == int.class) cb.iastore();
        else if (type == double.class) cb.dastore();
        else if (type == long.class) cb.lastore();
        else if (type == boolean.class || type == byte.class) cb.bastore();
        else if (type == short.class) cb.sastore();
        else if (type == char.class) cb.castore();
    }

    /**
     * Factory that auto-detects SoA eligibility.
     * Primitive-only records get generated SoA; everything else gets
     * {@link DefaultComponentStorage}.
     */
    public static final class SoAFactory implements ComponentStorage.Factory {
        @Override
        public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
            if (isEligible(type)) {
                return SoAComponentStorage.create(type, capacity);
            }
            return new DefaultComponentStorage<>(type, capacity);
        }
    }
}
