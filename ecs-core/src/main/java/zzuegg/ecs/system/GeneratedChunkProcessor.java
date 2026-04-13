package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.lang.classfile.ClassFile;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates a hidden class per system with a tight iteration loop that calls
 * the system method via invokevirtual/invokestatic — fully inlineable by the JIT.
 * Eliminates the ~1.3ns per-entity MethodHandle dispatch overhead.
 */
public final class GeneratedChunkProcessor {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER = new java.util.concurrent.atomic.AtomicLong();

    private GeneratedChunkProcessor() {}

    /**
     * Build a valid JVM class name for a hidden processor in the given package.
     * The hidden class MUST live in the same package as its {@code Lookup}
     * (i.e. the system class) — {@code defineHiddenClass} rejects cross-package
     * names, and package-private system methods are only reachable from a
     * nestmate in the same package.
     *
     * desc.name() is "Class.method" — the dot is a JVM package separator, so we
     * sanitise it (and any other illegal identifier characters) into '_' before
     * embedding the name in ClassDesc.of. A monotonic counter guarantees
     * uniqueness across rebuilds instead of relying on nanoTime() resolution.
     */
    static String generateClassName(String packageName, String descName) {
        var sb = new StringBuilder(descName.length());
        for (int i = 0; i < descName.length(); i++) {
            char c = descName.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && i > 0);
            sb.append(ok ? c : '_');
        }
        String prefix = packageName == null || packageName.isEmpty() ? "" : packageName + ".";
        return prefix + "Proc_" + sb + "_" + COUNTER.incrementAndGet();
    }

    /** Legacy shim for the existing test that asserts the generated name shape. */
    static String generateClassName(String descName) {
        return generateClassName("zzuegg.ecs.generated", descName);
    }

    /**
     * Result of generating a specialised Mut subclass for a specific component type.
     */
    record HiddenMutInfo(Class<?> mutClass, ClassDesc classDesc,
                         java.util.List<zzuegg.ecs.storage.RecordFlattener.FlatField> flatFields) {}

    /**
     * Generate a Mut subclass specialised for a specific SoA-eligible record type
     * (possibly with nested records). The subclass stores current values as
     * flattened primitive fields (all widened to long for uniform EA layout),
     * eliminating the Record allocation needed for the Mut constructor's
     * "current" value.
     *
     * <p>Overrides get(), set(), isChanged(), and flush(). get() always
     * reconstructs the Record from cur_ fields. set() decomposes the Record
     * directly into native-typed cur_ fields without storing in `pending`,
     * so the Record never escapes and C2 can fully scalarize it. For records
     * with 1-3 fields, set() is a single method under MaxInlineSize (35 bytes).
     * For 4+ fields, set() delegates to a private decompose$() helper via
     * invokespecial, keeping both methods under 35 bytes.
     */
    @SuppressWarnings("unchecked")
    static HiddenMutInfo generateHiddenMut(MethodHandles.Lookup systemLookup,
                                           Class<? extends Record> componentType,
                                           String uniqueSuffix) throws Exception {
        var flatFields = zzuegg.ecs.storage.RecordFlattener.flatten(componentType);
        var mutCd = ClassDesc.of("zzuegg.ecs.component.Mut");
        var recordCd = ClassDesc.of("java.lang.Record");
        var trackerCd = ClassDesc.of("zzuegg.ecs.change.ChangeTracker");
        var recCd = componentType.describeConstable().orElseThrow();

        String pkg = systemLookup.lookupClass().getPackageName();
        String pfx = (pkg == null || pkg.isEmpty()) ? "" : pkg + ".";
        var sfx = new StringBuilder(uniqueSuffix.length());
        for (int si = 0; si < uniqueSuffix.length(); si++) {
            char c = uniqueSuffix.charAt(si);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && si > 0);
            sfx.append(ok ? c : '_');
        }
        String className = pfx + "HiddenMut_" + componentType.getSimpleName()
            + "_" + sfx + "_" + COUNTER.incrementAndGet();
        var genCd = ClassDesc.of(className);

        // cur_ fields use their native primitive types. The set() override
        // decomposes the Record directly into these fields via a private
        // decompose$ helper, keeping both methods under MaxInlineSize (35 bytes)
        // so C2 inlines them unconditionally and can scalarize the Record.
        // Previously all fields were uniform long, but that made set() too
        // large (51 bytes) to inline via MaxInlineSize.

        byte[] bytes = ClassFile.of().build(genCd, clb -> {
            clb.withFlags(ACC_PUBLIC);
            clb.withSuperclass(mutCd);
            // Flattened cur_ fields — one per primitive leaf, native type
            for (var ff : flatFields) {
                clb.withField("cur_" + ff.flatName(),
                    ff.type().describeConstable().orElseThrow(),
                    fb -> fb.withFlags(ACC_PUBLIC));
            }
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void),
                ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.invokespecial(mutCd, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                    cb.return_();
                });
            // get() -> Record: always reconstruct from cur_ fields. After
            // set(), cur_ holds the new values; before, the originals.
            clb.withMethodBody("get", MethodTypeDesc.of(recordCd), ACC_PUBLIC, cb -> {
                emitMutRecordReconstructionNative(cb, componentType, flatFields,
                    new int[]{0}, genCd);
                cb.areturn();
            });

            // isChanged() -> boolean
            clb.withMethodBody("isChanged", MethodTypeDesc.of(ConstantDescs.CD_boolean),
                ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.getfield(mutCd, "changed", ConstantDescs.CD_boolean);
                    cb.ireturn();
                });
            // Estimate set() bytecode size: 5 (changed) + 5 (checkcast+astore)
            // + 8*N (per field: aload+aload+invoke+putfield) + 1 (return).
            // For N<=3 fields: 11 + 8*N <= 35 bytes → fits MaxInlineSize.
            // For N>=4 fields: split into set() + private decompose$().
            final int setEstimate = 11 + 8 * flatFields.size();
            if (setEstimate > 35) {
                // Two-method split: decompose$() does the field writes,
                // set() delegates via invokespecial. Both under 35 bytes.
                clb.withMethodBody("decompose$", MethodTypeDesc.of(ConstantDescs.CD_void, recCd),
                    ACC_PRIVATE, cb -> {
                        for (var ff : flatFields) {
                            cb.aload(0);
                            cb.aload(1);
                            emitAccessorChainOnStack(cb, componentType, ff.accessors());
                            cb.putfield(genCd, "cur_" + ff.flatName(),
                                ff.type().describeConstable().orElseThrow());
                        }
                        cb.return_();
                    });
                clb.withMethodBody("set", MethodTypeDesc.of(ConstantDescs.CD_void, recordCd),
                    ACC_PUBLIC, cb -> {
                        cb.aload(0);
                        cb.iconst_1();
                        cb.putfield(mutCd, "changed", ConstantDescs.CD_boolean);
                        cb.aload(0);
                        cb.aload(1);
                        cb.checkcast(recCd);
                        cb.invokespecial(genCd, "decompose$",
                            MethodTypeDesc.of(ConstantDescs.CD_void, recCd));
                        cb.return_();
                    });
            } else {
                // Single set() method — all field writes inline.
                clb.withMethodBody("set", MethodTypeDesc.of(ConstantDescs.CD_void, recordCd),
                    ACC_PUBLIC, cb -> {
                        cb.aload(0);
                        cb.iconst_1();
                        cb.putfield(mutCd, "changed", ConstantDescs.CD_boolean);
                        cb.aload(1);
                        cb.checkcast(recCd);
                        cb.astore(2);
                        for (var ff : flatFields) {
                            cb.aload(0);
                            cb.aload(2);
                            emitAccessorChainOnStack(cb, componentType, ff.accessors());
                            cb.putfield(genCd, "cur_" + ff.flatName(),
                                ff.type().describeConstable().orElseThrow());
                        }
                        cb.return_();
                    });
            }
            // flush() -> Record: reconstruct from cur_ fields + markChanged
            clb.withMethodBody("flush", MethodTypeDesc.of(recordCd), ACC_PUBLIC, cb -> {
                var changedPath = cb.newLabel();
                cb.aload(0);
                cb.getfield(mutCd, "changed", ConstantDescs.CD_boolean);
                cb.ifne(changedPath);
                emitMutRecordReconstructionNative(cb, componentType, flatFields,
                    new int[]{0}, genCd);
                cb.areturn();
                cb.labelBinding(changedPath);
                cb.aload(0); cb.getfield(mutCd, "tracker", trackerCd);
                cb.aload(0); cb.getfield(mutCd, "slot", ConstantDescs.CD_int);
                cb.aload(0); cb.getfield(mutCd, "tick", ConstantDescs.CD_long);
                cb.invokevirtual(trackerCd, "markChanged",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, ConstantDescs.CD_long));
                emitMutRecordReconstructionNative(cb, componentType, flatFields,
                    new int[]{0}, genCd);
                cb.areturn();
            });
        });

        var mutClass = systemLookup.defineClass(bytes);
        return new HiddenMutInfo(mutClass, genCd, flatFields);
    }

    /**
     * Emit bytecode to reconstruct a (possibly nested) record from a HiddenMut's
     * native-typed cur_ fields. No narrowing needed — fields are already the
     * correct primitive type.
     */
    private static void emitMutRecordReconstructionNative(
            java.lang.classfile.CodeBuilder cb,
            Class<?> recordType,
            java.util.List<zzuegg.ecs.storage.RecordFlattener.FlatField> flatFields,
            int[] flatIdx,
            ClassDesc genCd) {
        var recCd = recordType.describeConstable().orElseThrow();
        var comps = recordType.getRecordComponents();
        var ctorPs = new ClassDesc[comps.length];
        for (int i = 0; i < comps.length; i++) {
            ctorPs[i] = comps[i].getType().describeConstable().orElseThrow();
        }
        cb.new_(recCd);
        cb.dup();
        for (var comp : comps) {
            if (comp.getType().isPrimitive()) {
                var ff = flatFields.get(flatIdx[0]++);
                cb.aload(0); // this (Mut instance)
                cb.getfield(genCd, "cur_" + ff.flatName(),
                    ff.type().describeConstable().orElseThrow());
            } else {
                emitMutRecordReconstructionNative(cb, comp.getType(), flatFields, flatIdx,
                    genCd);
            }
        }
        cb.invokespecial(recCd, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, ctorPs));
    }

    /**
     * Emit bytecode to widen a primitive value on the JVM operand stack to {@code long}.
     * Preserves exact bit patterns for floating-point types via raw-bits conversion.
     * <ul>
     *   <li>boolean, byte, short, char, int &rarr; {@code i2l}</li>
     *   <li>float &rarr; {@code Float.floatToRawIntBits} then {@code i2l}</li>
     *   <li>double &rarr; {@code Double.doubleToRawLongBits}</li>
     *   <li>long &rarr; no-op</li>
     * </ul>
     */
    static void emitWidenToLong(java.lang.classfile.CodeBuilder cb, Class<?> type) {
        if (type == long.class) {
            // already long — nothing to do
        } else if (type == double.class) {
            cb.invokestatic(ClassDesc.of("java.lang.Double"), "doubleToRawLongBits",
                MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_double));
        } else if (type == float.class) {
            cb.invokestatic(ClassDesc.of("java.lang.Float"), "floatToRawIntBits",
                MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_float));
            cb.i2l();
        } else {
            // boolean, byte, short, char, int — all occupy one int slot on the stack
            cb.i2l();
        }
    }

    /**
     * Emit bytecode to narrow a {@code long} on the JVM operand stack back to the
     * original primitive type. Inverse of {@link #emitWidenToLong}.
     */
    static void emitNarrowFromLong(java.lang.classfile.CodeBuilder cb, Class<?> type) {
        if (type == long.class) {
            // already long — nothing to do
        } else if (type == double.class) {
            cb.invokestatic(ClassDesc.of("java.lang.Double"), "longBitsToDouble",
                MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_long));
        } else if (type == float.class) {
            cb.l2i();
            cb.invokestatic(ClassDesc.of("java.lang.Float"), "intBitsToFloat",
                MethodTypeDesc.of(ConstantDescs.CD_float, ConstantDescs.CD_int));
        } else {
            // boolean, byte, short, char, int — l2i is sufficient
            // (record constructors take int for boolean/byte/short/char)
            cb.l2i();
        }
    }

    /**
     * Package-private skip-reason introspection. Returns {@code null} if this
     * processor tier can handle the system; otherwise a human-readable reason
     * the system was rejected. Kept on this class so callers (e.g. future
     * diagnostics, a {@code -Dzzuegg.ecs.logGenerated} flag) can explain why
     * the slower fallback path was picked without digging through code.
     */
    /**
     * Emit a branch to {@code target} if the two primitive values on
     * top of the stack are NOT equal. Consumes both values.
     */
    static void emitPrimitiveNotEqual(java.lang.classfile.CodeBuilder cb, Class<?> type, java.lang.classfile.Label target) {
        if (type == float.class) { cb.fcmpl(); cb.ifne(target); }
        else if (type == double.class) { cb.dcmpl(); cb.ifne(target); }
        else if (type == long.class) { cb.lcmp(); cb.ifne(target); }
        else { cb.if_icmpne(target); } // int, boolean, byte, short, char
    }

    /**
     * Emit bytecode to reconstruct a (possibly nested) record from flattened
     * SoA arrays in the entity loop. Loads each primitive from its SoA array
     * local at the given slot index.
     *
     * @param flatIdx mutable counter into flatFields
     */
    private static void emitSoARecordReconstruction(
            java.lang.classfile.CodeBuilder cb,
            Class<?> recordType,
            java.util.List<zzuegg.ecs.storage.RecordFlattener.FlatField> flatFields,
            int[] flatIdx,
            int[] soaFieldLocals,
            int slotVar) {
        var recDesc = recordType.describeConstable().orElseThrow();
        var comps = recordType.getRecordComponents();
        var ctorPs = new ClassDesc[comps.length];
        for (int i = 0; i < comps.length; i++) {
            ctorPs[i] = comps[i].getType().describeConstable().orElseThrow();
        }
        cb.new_(recDesc);
        cb.dup();
        for (var comp : comps) {
            if (comp.getType().isPrimitive()) {
                int fi = flatIdx[0]++;
                cb.aload(soaFieldLocals[fi]);
                cb.iload(slotVar);
                zzuegg.ecs.storage.SoAComponentStorage.emitArrayLoad(cb, comp.getType());
            } else {
                emitSoARecordReconstruction(cb, comp.getType(), flatFields, flatIdx,
                    soaFieldLocals, slotVar);
            }
        }
        cb.invokespecial(recDesc, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void, ctorPs));
    }

    /**
     * Emit bytecode to navigate an accessor chain on a record already on the stack.
     * The root type is used to properly cast before the first accessor.
     */
    private static void emitAccessorChainOnStack(
            java.lang.classfile.CodeBuilder cb,
            Class<?> rootType,
            java.util.List<java.lang.reflect.RecordComponent> chain) {
        for (var rc : chain) {
            var ownerDesc = rc.getDeclaringRecord().describeConstable().orElseThrow();
            var retDesc = rc.getType().describeConstable().orElseThrow();
            cb.invokevirtual(ownerDesc, rc.getName(), MethodTypeDesc.of(retDesc));
        }
    }

    static String skipReason(SystemDescriptor desc) {
        var method = desc.method();
        var params = method.getParameters();
        int componentCount = 0;
        for (var param : params) {
            if (param.isAnnotationPresent(Read.class) || param.isAnnotationPresent(Write.class)) {
                componentCount++;
            }
            // Non-component params (Entity / Commands / Res / ResMut / EventReader
            // / EventWriter / Local / RemovedComponents) compile to a
            // constant-reference field or a chunk.entity(slot) call and don't
            // block the fast path.
        }
        if (componentCount < 1) return "system has no component parameters";
        if (componentCount > 4) return "system has " + componentCount + " component parameters (tier-1 limit is 4)";
        if (params.length > 8) return "system has " + params.length + " total parameters (tier-1 limit is 8)";
        if (!desc.whereFilters().isEmpty()) return "system uses @Where filters";
        // @Filter(Removed) isn't expressible via tracker ticks — it needs a
        // separate removal log. Only Added/Changed are supported in tier-1.
        for (var f : desc.changeFilters()) {
            if (f.filterType() != Added.class && f.filterType() != Changed.class) {
                return "system uses @Filter(" + f.filterType().getSimpleName() + ") which is unsupported";
            }
        }
        return null;
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        return tryGenerate(desc, serviceArgs, true, null);
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs,
                                             boolean useDefaultStorageFactory) {
        return tryGenerate(desc, serviceArgs, useDefaultStorageFactory, null);
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs,
                                             boolean useDefaultStorageFactory,
                                             SystemExecutionPlan plan) {
        return tryGenerate(desc, serviceArgs, useDefaultStorageFactory, plan, false);
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs,
                                             boolean useDefaultStorageFactory,
                                             SystemExecutionPlan plan,
                                             boolean useSoAStorage) {
        if (skipReason(desc) != null) return null;
        if (!desc.changeFilters().isEmpty() && plan == null) return null;
        try {
            return generateWithBytecode(desc, serviceArgs, useDefaultStorageFactory, plan, useSoAStorage);
        } catch (Exception e) {
            // Fall back to invokeExact path. The invokeExact fallback cannot
            // handle Mut/Entity/service parameters — if any such param exists,
            // the fallback would attempt a direct cast and crash at runtime
            // with a confusing ClassCastException, so surface the bytecode
            // failure with full context instead.
            for (var p : desc.method().getParameters()) {
                if (!p.isAnnotationPresent(Read.class)) {
                    throw new RuntimeException(
                        "tier-1 bytecode generation failed for " + desc.name()
                            + " and the invokeExact fallback only supports all-@Read systems", e);
                }
            }
            return generateWithInvokeExact(desc);
        }
    }

    /**
     * Generate a hidden class with invokevirtual/invokestatic to the system method.
     * This is the fastest path — the JIT can fully inline the system call.
     *
     * Supports both read (@Read Component) and write (@Write Mut&lt;Component&gt;)
     * parameters. For write params, the generated bytecode:
     *   - loads the target component's {@code ChangeTracker} once per chunk
     *   - keeps a reused {@code Mut} instance in an instance field
     *   - calls {@code Mut.reset(value, slot, tracker, tick)} per entity
     *     before passing the Mut to the user method
     *   - calls {@code Mut.flush()} after the system body and writes the
     *     returned value back to component storage; the flush itself does
     *     the value-tracked suppression and {@code ChangeTracker.markChanged}
     *     bookkeeping, so no extra logic is emitted here.
     */
    // Per-parameter classification for the generator.
    private enum ParamKind { READ, WRITE, ENTITY, SERVICE }

    private static ChunkProcessor generateWithBytecode(SystemDescriptor desc, Object[] serviceArgs,
                                                        boolean useDefaultStorageFactory,
                                                        SystemExecutionPlan plan,
                                                        boolean useSoAStorage) throws Exception {
        var method = desc.method();
        var accesses = desc.componentAccesses();
        int paramCount = method.getParameterCount();
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        var params = method.getParameters();

        // Classify each parameter. Component params (Read/Write) consume an
        // entry from desc.componentAccesses(); Entity and service params don't.
        ParamKind[] kinds = new ParamKind[paramCount];
        Class<?>[] componentTypes = new Class<?>[paramCount]; // non-null only for READ/WRITE
        int writeCount = 0;
        int componentIdx = 0;
        // Mapping from param slot to ComponentAccess index for READ/WRITE params.
        int[] paramToAccessIdx = new int[paramCount];
        for (int i = 0; i < paramCount; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                kinds[i] = ParamKind.READ;
                componentTypes[i] = accesses.get(componentIdx).type();
                paramToAccessIdx[i] = componentIdx++;
            } else if (params[i].isAnnotationPresent(Write.class)) {
                kinds[i] = ParamKind.WRITE;
                componentTypes[i] = accesses.get(componentIdx).type();
                paramToAccessIdx[i] = componentIdx++;
                writeCount++;
            } else if (params[i].getType() == zzuegg.ecs.entity.Entity.class) {
                kinds[i] = ParamKind.ENTITY;
                paramToAccessIdx[i] = -1;
            } else {
                kinds[i] = ParamKind.SERVICE;
                paramToAccessIdx[i] = -1;
            }
        }

        // SoA detection: only when the world uses a non-default storage factory.
        // The default factory produces DefaultComponentStorage with Object[]
        // backing — no SoA. With a SoA factory, eligible primitive-only records
        // get per-field arrays and the generator emits faload/fastore directly.
        boolean[] isSoA = new boolean[paramCount];
        @SuppressWarnings("unchecked")
        java.util.List<zzuegg.ecs.storage.RecordFlattener.FlatField>[] soaFlatFields =
            new java.util.List[paramCount];
        boolean anySoA = false;
        if (useSoAStorage) {
            for (int i = 0; i < paramCount; i++) {
                if ((kinds[i] == ParamKind.READ || kinds[i] == ParamKind.WRITE)
                    && componentTypes[i] != null
                    && Record.class.isAssignableFrom(componentTypes[i])
                    && zzuegg.ecs.storage.SoAComponentStorage.isEligible((Class<? extends Record>) componentTypes[i])) {
                    isSoA[i] = true;
                    soaFlatFields[i] = zzuegg.ecs.storage.RecordFlattener.flatten(
                        (Class<? extends Record>) componentTypes[i]);
                    anySoA = true;
                }
            }
        }

        var systemLookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());

        // Generate specialised Mut subclasses for ALL SoA WRITE params,
        // including @ValueTracked. The flush path handles valueTracked by
        // comparing cur_ fields against the pending Record's fields using
        // primitive comparison — no Record.equals() virtual dispatch needed.
        HiddenMutInfo[] hiddenMutInfos = new HiddenMutInfo[paramCount];
        boolean[] paramValueTracked = new boolean[paramCount];
        for (int i = 0; i < paramCount; i++) {
            if (kinds[i] == ParamKind.WRITE && isSoA[i]) {
                hiddenMutInfos[i] = generateHiddenMut(systemLookup,
                    (Class<? extends Record>) componentTypes[i], desc.name());
                paramValueTracked[i] = componentTypes[i].isAnnotationPresent(
                    zzuegg.ecs.component.ValueTracked.class);
            }
        }

        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();
        var storageDesc = ClassDesc.of("zzuegg.ecs.storage.ComponentStorage");
        var chunkDesc = ClassDesc.of("zzuegg.ecs.storage.Chunk");
        var compIdDesc = ClassDesc.of("zzuegg.ecs.component.ComponentId");
        var processorDesc = ClassDesc.of("zzuegg.ecs.system.ChunkProcessor");
        var mutDesc = ClassDesc.of("zzuegg.ecs.component.Mut");
        var trackerDesc = ClassDesc.of("zzuegg.ecs.change.ChangeTracker");
        var recordDesc = ClassDesc.of("java.lang.Record");
        // The generated class must share a package with the system class so
        // the hidden class can be a nestmate and invokevirtual a
        // package-private system method.
        var genName = generateClassName(method.getDeclaringClass().getPackageName(), desc.name());
        var genDesc = ClassDesc.of(genName);
        var objDesc = ConstantDescs.CD_Object;

        // Build the JVM-level method type descriptor. For read params the
        // declared type is the component itself; for write params it's Mut,
        // erased to its raw form at the JVM level.
        var paramDescs = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            paramDescs[i] = method.getParameterTypes()[i].describeConstable().orElseThrow();
        }
        var systemMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs);

        var compIdArrayDesc = compIdDesc.arrayType();
        var mutArrayDesc = mutDesc.arrayType();
        var trackerArrayDesc = trackerDesc.arrayType();
        var objArrayDesc = objDesc.arrayType();
        var recordArrayDesc = recordDesc.arrayType();
        var defaultStorageDesc = ClassDesc.of("zzuegg.ecs.storage.DefaultComponentStorage");
        var rawArrayDesc = MethodTypeDesc.of(recordArrayDesc);
        var entityDesc = ClassDesc.of("zzuegg.ecs.entity.Entity");
        var chunkEntityDesc = MethodTypeDesc.of(entityDesc, ConstantDescs.CD_int);

        // Descriptors for Mut.setContext / Mut.resetValue / Mut.flush and
        // ComponentStorage.set / get.
        var mutSetContextDesc = MethodTypeDesc.of(ConstantDescs.CD_void, trackerDesc, ConstantDescs.CD_long);
        var mutResetValueDesc = MethodTypeDesc.of(ConstantDescs.CD_void, recordDesc, ConstantDescs.CD_int);
        var mutFlushDesc = MethodTypeDesc.of(recordDesc);
        var storageGetDesc = MethodTypeDesc.of(recordDesc, ConstantDescs.CD_int);
        var storageSetDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int, recordDesc);
        var chunkChangeTrackerDesc = MethodTypeDesc.of(trackerDesc, compIdDesc);

        final boolean hasWrites = writeCount > 0;
        boolean hasServiceLocal = false;
        for (var kind : kinds) if (kind == ParamKind.SERVICE) { hasServiceLocal = true; break; }
        final boolean hasServices = hasServiceLocal;

        // Change filter setup — only populated when the system has filters.
        // The resolved filters from the plan give us ComponentIds already.
        final boolean hasFilters = plan != null && plan.hasChangeFilters();
        final SystemExecutionPlan.ResolvedChangeFilter[] resolvedFilters =
            hasFilters ? plan.resolvedChangeFilters() : new SystemExecutionPlan.ResolvedChangeFilter[0];
        // Multi-target: any @Filter group with > 1 target component.
        boolean isMultiTarget = false;
        int totalFilterTargets = 0;
        for (var f : resolvedFilters) {
            totalFilterTargets += f.targetIds().length;
            if (f.targetIds().length > 1) isMultiTarget = true;
        }
        final boolean multiTargetFilter = isMultiTarget;
        final int filterTargetCount = totalFilterTargets;
        var planClassDesc = ClassDesc.of("zzuegg.ecs.system.SystemExecutionPlan");
        var planLastSeenDesc = MethodTypeDesc.of(ConstantDescs.CD_long);
        var trackerDirtySlotsDesc = MethodTypeDesc.of(ClassDesc.ofDescriptor("[I"));
        var trackerDirtyCountDesc = MethodTypeDesc.of(ConstantDescs.CD_int);
        var trackerIsChangedSinceDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_int, ConstantDescs.CD_long);
        var trackerIsAddedSinceDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_int, ConstantDescs.CD_long);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(processorDesc));

            // Field: system instance (if non-static)
            if (!isStatic) {
                clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }

            // Field: ComponentId[] for each param
            clb.withField("cids", compIdArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // Fields for write support — only emitted if the system actually
            // has @Write params, so pure-read systems get identical bytecode
            // to before the write-path extension.
            if (hasWrites) {
                clb.withField("muts", mutArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }
            // Field for service params — resolved once at plan-build time and
            // passed as constant references into the loop. Populated from the
            // resolvedServiceArgs array we receive from the caller.
            if (hasServices) {
                clb.withField("services", objArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }
            // Fields for change-filter support: a reference to the owning
            // SystemExecutionPlan (for lastSeenTick) and an array of the
            // filter-target ComponentIds (resolved per chunk via
            // chunk.changeTracker() into trackers[]). Both are stable for
            // the processor's lifetime.
            if (hasFilters) {
                clb.withField("plan", planClassDesc, fb -> fb.withFlags(ACC_PUBLIC));
                clb.withField("filterCids", compIdArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
                if (multiTargetFilter) {
                    // Reusable per-chunk buffers — allocated once, cleared per call.
                    clb.withField("seenBitmap", ClassDesc.ofDescriptor("[J"), fb -> fb.withFlags(ACC_PUBLIC));
                    clb.withField("resultBuf", ClassDesc.ofDescriptor("[I"), fb -> fb.withFlags(ACC_PUBLIC));
                    clb.withField("filterTrackers", trackerArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));
                }
            }

            // Constructor
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                cb.return_();
            });

            // Shared entity-loop emitter. Called once for process() (chunk at
            // param 1, tick at param 2) and once for processAll() (chunk in a
            // local from the List loop, tick at param 2). By inlining the full
            // entity loop into processAll, the JIT compiles it as one unit —
            // no cross-method boundary prevents escape analysis.
            //
            // chunkVar: local index holding the Chunk reference
            // tickVar:  local index holding the tick (long, 2 slots)
            // baseLocal: first free local after the method parameters
            interface EntityLoopEmitter {
                void emit(java.lang.classfile.CodeBuilder cb, int chunkVar, int tickVar, int baseLocal);
            }
            EntityLoopEmitter emitEntityLoop = (cb, chunkVar, tickVar, baseLocal) -> {

                // Local var layout (relative to baseLocal):
                //   baseLocal+0  count
                //   baseLocal+1  slot
                //   +2..                             : storages, trackers, temp, filter state, hoisted refs
                int countVar = baseLocal;
                int slotVar = baseLocal + 1;
                int firstStorageVar = baseLocal + 2;
                int firstTrackerVar = firstStorageVar + paramCount;
                int tempValueVar = firstTrackerVar + paramCount;
                int dirtyIdxVar = tempValueVar + 1;
                int dirtyListVar = dirtyIdxVar + 1;
                int dirtyCountVar = dirtyListVar + 1;
                int firstFilterTrackerVar = dirtyCountVar + 1;
                // For single-target: one tracker local per filter group (existing).
                // For multi-target: one tracker local per individual target across all groups.
                int filterTrackerLocals = multiTargetFilter ? filterTargetCount : resolvedFilters.length;
                int lastSeenVar = firstFilterTrackerVar + filterTrackerLocals;
                // lastSeen is a long → consumes 2 slots. Next free slot is
                // lastSeenVar + 2 if hasFilters, else lastSeenVar (== lastSeenVar
                // was never written). Keep it simple: reserve 2 slots always.
                int firstHoistVar = lastSeenVar + (hasFilters ? 2 : 0);
                int instLocal = !isStatic ? firstHoistVar++ : -1;
                // One local per parameter slot that we want to hoist. We only
                // hoist the SERVICE and WRITE-mut refs — storages and trackers
                // are already per-chunk locals, and READ params re-load per
                // slot intentionally (they're the values the user reads).
                int[] serviceLocal = new int[paramCount];
                int[] mutLocal = new int[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    serviceLocal[i] = -1;
                    mutLocal[i] = -1;
                    if (kinds[i] == ParamKind.SERVICE) {
                        serviceLocal[i] = firstHoistVar++;
                    } else if (kinds[i] == ParamKind.WRITE) {
                        mutLocal[i] = firstHoistVar++;
                    }
                }
                // If the system takes an Entity parameter, hoist the
                // chunk's raw entityArray() reference to a local so
                // per-slot access becomes an aaload instead of an
                // invokevirtual through Chunk.entity(int).
                boolean anyEntity = false;
                for (var k : kinds) if (k == ParamKind.ENTITY) { anyEntity = true; break; }
                int entityArrayLocal = anyEntity ? firstHoistVar++ : -1;

                cb.aload(chunkVar); // chunk
                cb.invokevirtual(chunkDesc, "count", MethodTypeDesc.of(ConstantDescs.CD_int));
                cb.istore(countVar);

                // Load storages into local vars for READ/WRITE params.
                // Three paths:
                //   1. SoA: extract per-field primitive arrays from soaFieldArrays()
                //   2. Default factory: grab raw Record[] via rawArray()
                //   3. Interface: store the ComponentStorage ref for invokeinterface
                //
                // SoA per-field arrays are stored in extra locals starting after
                // the normal layout. For param i with N record components,
                // soaFieldLocals[i][0..N-1] hold the primitive array refs.
                int[][] soaFieldLocals = new int[paramCount][];
                int soaNextLocal = firstHoistVar; // extend from the existing layout
                // First pass: allocate locals for SoA field arrays (flattened).
                for (int i = 0; i < paramCount; i++) {
                    if (!isSoA[i]) continue;
                    int nFields = soaFlatFields[i].size();
                    soaFieldLocals[i] = new int[nFields];
                    for (int f = 0; f < nFields; f++) {
                        soaFieldLocals[i][f] = soaNextLocal++;
                    }
                }
                // Adjust firstHoistVar if SoA locals were allocated
                // (the entity array local was already allocated, but SoA
                // locals come after it — let's put them AFTER all existing
                // hoisted locals by bumping soaNextLocal only).
                // Actually, soaNextLocal started at firstHoistVar which is
                // already past all existing hoisted locals. So soaFieldLocals
                // won't collide.

                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.READ && kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(chunkVar); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(paramToAccessIdx[i]);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "componentStorage",
                        MethodTypeDesc.of(storageDesc, compIdDesc));
                    if (isSoA[i]) {
                        // Extract per-field arrays from soaFieldArrays().
                        var objArrDesc = ConstantDescs.CD_Object.arrayType();
                        cb.invokeinterface(storageDesc, "soaFieldArrays",
                            MethodTypeDesc.of(objArrDesc));
                        // For each flattened field: cast and store in a local
                        var ff = soaFlatFields[i];
                        for (int f = 0; f < ff.size(); f++) {
                            if (f < ff.size() - 1) cb.dup();
                            cb.ldc(f);
                            cb.aaload();
                            var arrType = ff.get(f).type().arrayType()
                                .describeConstable().orElseThrow();
                            cb.checkcast(arrType);
                            cb.astore(soaFieldLocals[i][f]);
                        }
                        // Also store a placeholder in firstStorageVar (unused for SoA
                        // reads/writes, but keeps the local layout consistent).
                        cb.aconst_null();
                        cb.astore(firstStorageVar + i);
                    } else if (useDefaultStorageFactory) {
                        cb.checkcast(defaultStorageDesc);
                        cb.invokevirtual(defaultStorageDesc, "rawArray", rawArrayDesc);
                        cb.astore(firstStorageVar + i);
                    } else {
                        cb.astore(firstStorageVar + i);
                    }
                }

                // Load trackers for WRITE params: trackerN = chunk.changeTracker(cids[accessIdx])
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    cb.aload(chunkVar); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(paramToAccessIdx[i]);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                    cb.astore(firstTrackerVar + i);
                }

                // Per-chunk: tracker + tick are stable. With fresh-Mut-per-entity
                // the tracker is already in firstTrackerVar[i] and tick is in
                // the method arg (slot 2). No per-chunk Mut setup needed.

                // Filter setup: load a ChangeTracker for each filter target,
                // grab the primary filter's dirty list + count, and cache
                // plan.lastSeenTick() in a local so the per-slot check is a
                // plain long comparison.
                if (hasFilters) {
                    // lastSeen = plan.lastSeenTick()  (used by both paths)
                    cb.aload(0);
                    cb.getfield(genDesc, "plan", planClassDesc);
                    cb.invokevirtual(planClassDesc, "lastSeenTick", planLastSeenDesc);
                    cb.lstore(lastSeenVar);

                    if (multiTargetFilter) {
                        // Multi-target path: build a ChangeTracker[] per chunk,
                        // clear the reusable bitmap, call the helper which fills
                        // the reusable resultBuf. Zero per-chunk allocation.
                        var helperDesc = ClassDesc.of("zzuegg.ecs.system.MultiFilterHelper");
                        var longArrDesc = ClassDesc.ofDescriptor("[J");
                        var intArrDesc = ClassDesc.ofDescriptor("[I");
                        var unionDesc = MethodTypeDesc.of(
                            ConstantDescs.CD_int,           // return: match count
                            trackerArrayDesc,               // ChangeTracker[]
                            ConstantDescs.CD_int,           // count
                            ConstantDescs.CD_long,          // lastSeen
                            ConstantDescs.CD_boolean,       // isAdded
                            longArrDesc,                    // seenBitmap
                            intArrDesc);                    // resultBuf
                        // Fill the reusable filterTrackers[] from chunk + filterCids
                        cb.aload(0);
                        cb.getfield(genDesc, "filterTrackers", trackerArrayDesc);
                        for (int ti = 0; ti < filterTargetCount; ti++) {
                            cb.dup();
                            cb.ldc(ti);
                            cb.aload(chunkVar); // chunk
                            cb.aload(0);
                            cb.getfield(genDesc, "filterCids", compIdArrayDesc);
                            cb.ldc(ti);
                            cb.aaload();
                            cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                            cb.aastore();
                        }
                        // stack: [ChangeTracker[]]
                        cb.iload(countVar);           // count
                        cb.lload(lastSeenVar);        // lastSeen
                        boolean isAdded = resolvedFilters[0].kind() == SystemExecutionPlan.FilterKind.ADDED;
                        cb.ldc(isAdded ? 1 : 0);
                        // Clear seenBitmap: Arrays.fill(seenBitmap, 0L)
                        cb.aload(0);
                        cb.getfield(genDesc, "seenBitmap", longArrDesc);
                        cb.dup();
                        cb.lconst_0();
                        cb.invokestatic(ClassDesc.of("java.util.Arrays"), "fill",
                            MethodTypeDesc.of(ConstantDescs.CD_void, longArrDesc, ConstantDescs.CD_long));
                        // stack: [ChangeTracker[], count, lastSeen, isAdded, seenBitmap]
                        cb.aload(0);
                        cb.getfield(genDesc, "resultBuf", intArrDesc);
                        // stack: [..., seenBitmap, resultBuf]
                        cb.invokestatic(helperDesc, "unionDirtySlots", unionDesc);
                        // return value is match count → store as dirtyCount
                        cb.istore(dirtyCountVar);
                        // dirtyList = this.resultBuf (the helper wrote into it)
                        cb.aload(0);
                        cb.getfield(genDesc, "resultBuf", intArrDesc);
                        cb.astore(dirtyListVar);
                        cb.iconst_0();
                        cb.istore(dirtyIdxVar);
                    } else {
                        // Single-target path: load one tracker per filter, use
                        // the primary's dirty list directly (existing code).
                        for (int fi = 0; fi < resolvedFilters.length; fi++) {
                            cb.aload(chunkVar); // chunk
                            cb.aload(0);
                            cb.getfield(genDesc, "filterCids", compIdArrayDesc);
                            cb.ldc(fi);
                            cb.aaload();
                            cb.invokevirtual(chunkDesc, "changeTracker", chunkChangeTrackerDesc);
                            cb.astore(firstFilterTrackerVar + fi);
                        }
                        cb.aload(firstFilterTrackerVar);
                        cb.invokevirtual(trackerDesc, "dirtySlots", trackerDirtySlotsDesc);
                        cb.astore(dirtyListVar);
                        cb.aload(firstFilterTrackerVar);
                        cb.invokevirtual(trackerDesc, "dirtyCount", trackerDirtyCountDesc);
                        cb.istore(dirtyCountVar);
                        cb.iconst_0();
                        cb.istore(dirtyIdxVar);
                    }
                } else {
                    // int slot = 0
                    cb.iconst_0();
                    cb.istore(slotVar);
                }

                // Hoist per-instance field loads out of the loop. Without
                // this, every iteration does:
                //   aload0; getfield inst; checkcast systemClass
                //   aload0; getfield services; ldc i; aaload; checkcast P
                //   aload0; getfield muts; ldc i; aaload
                // The JIT's loop-invariant code motion can't hoist these
                // because the fields are `public` non-final and the
                // invokevirtual to the user body is treated as a potential
                // side effect. Hoisting them here in bytecode makes the hot
                // loop load from locals only.
                if (instLocal >= 0) {
                    cb.aload(0);
                    cb.getfield(genDesc, "inst", objDesc);
                    cb.checkcast(systemClassDesc);
                    cb.astore(instLocal);
                }
                for (int i = 0; i < paramCount; i++) {
                    if (serviceLocal[i] < 0) continue;
                    cb.aload(0);
                    cb.getfield(genDesc, "services", objArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.checkcast(params[i].getType().describeConstable().orElseThrow());
                    cb.astore(serviceLocal[i]);
                }
                // Note: mutLocal[i] is NOT pre-loaded from this.muts[i].
                // The entity loop creates a fresh Mut per entity. Pre-loading
                // the heap-resident muts[i] would poison the local's type
                // profile at the loop-header merge point (heap object + fresh
                // allocation in the same local), potentially blocking EA.
                // Hoist chunk.entityArray() so per-slot Entity access
                // is a plain aaload on a local Entity[] rather than an
                // invokevirtual through Chunk.entity(int).
                if (entityArrayLocal >= 0) {
                    cb.aload(chunkVar); // chunk
                    cb.invokevirtual(chunkDesc, "entityArray",
                        MethodTypeDesc.of(entityDesc.arrayType()));
                    cb.astore(entityArrayLocal);
                }

                var loopStart = cb.newLabel();
                var loopEnd = cb.newLabel();
                var loopContinue = cb.newLabel();

                cb.labelBinding(loopStart);
                if (hasFilters) {
                    // if (dirtyIdx >= dirtyCount) goto end
                    cb.iload(dirtyIdxVar);
                    cb.iload(dirtyCountVar);
                    cb.if_icmpge(loopEnd);
                    // slot = dirty[dirtyIdx++]
                    cb.aload(dirtyListVar);
                    cb.iload(dirtyIdxVar);
                    cb.iaload();
                    cb.istore(slotVar);
                    cb.iinc(dirtyIdxVar, 1);
                    if (multiTargetFilter) {
                        // Multi-target: the helper already filtered + deduped.
                        // No per-slot check needed — every slot in the array
                        // is guaranteed to match. The helper also excluded
                        // slots >= count, so no bounds check required.
                    } else {
                        // Single-target: bounds check + per-filter AND check.
                        cb.iload(slotVar);
                        cb.iload(countVar);
                        cb.if_icmpge(loopContinue);
                        for (int fi = 0; fi < resolvedFilters.length; fi++) {
                            cb.aload(firstFilterTrackerVar + fi);
                            cb.iload(slotVar);
                            cb.lload(lastSeenVar);
                            var kind = resolvedFilters[fi].kind();
                            if (kind == SystemExecutionPlan.FilterKind.ADDED) {
                                cb.invokevirtual(trackerDesc, "isAddedSince", trackerIsAddedSinceDesc);
                            } else {
                                cb.invokevirtual(trackerDesc, "isChangedSince", trackerIsChangedSinceDesc);
                            }
                            cb.ifeq(loopContinue);
                        }
                    }
                } else {
                    cb.iload(slotVar);
                    cb.iload(countVar);
                    cb.if_icmpge(loopEnd);
                }

                // Load instance for invokevirtual (non-static only) —
                // hoisted to `instLocal` in the preamble.
                if (!isStatic) {
                    cb.aload(instLocal);
                }

                // Build the arg list
                for (int i = 0; i < paramCount; i++) {
                    switch (kinds[i]) {
                        case READ -> {
                            if (isSoA[i]) {
                                // SoA: construct record from flattened per-field arrays.
                                emitSoARecordReconstruction(cb, componentTypes[i],
                                    soaFlatFields[i], new int[]{0}, soaFieldLocals[i], slotVar);
                            } else if (useDefaultStorageFactory) {
                                cb.aload(firstStorageVar + i); // Record[]
                                cb.iload(slotVar);
                                cb.aaload();
                                cb.checkcast(componentTypes[i].describeConstable().orElseThrow());
                            } else {
                                cb.aload(firstStorageVar + i);
                                cb.iload(slotVar);
                                cb.invokeinterface(storageDesc, "get", storageGetDesc);
                                cb.checkcast(componentTypes[i].describeConstable().orElseThrow());
                            }
                        }
                        case WRITE -> {
                            if (hiddenMutInfos[i] != null) {
                                // Specialised Mut subclass: stores primitives
                                // directly from SoA arrays, no record alloc.
                                var hmi = hiddenMutInfos[i];
                                var hmDesc = hmi.classDesc();
                                var hmFf = hmi.flatFields();
                                cb.new_(hmDesc);
                                cb.dup();
                                cb.invokespecial(hmDesc, "<init>",
                                    MethodTypeDesc.of(ConstantDescs.CD_void));
                                for (int f = 0; f < hmFf.size(); f++) {
                                    cb.dup();
                                    cb.aload(soaFieldLocals[i][f]);
                                    cb.iload(slotVar);
                                    zzuegg.ecs.storage.SoAComponentStorage.emitArrayLoad(cb, hmFf.get(f).type());
                                    cb.putfield(hmDesc, "cur_" + hmFf.get(f).flatName(),
                                        hmFf.get(f).type().describeConstable().orElseThrow());
                                }
                                cb.dup();
                                cb.iload(slotVar);
                                cb.putfield(mutDesc, "slot", ConstantDescs.CD_int);
                                cb.dup();
                                cb.aload(firstTrackerVar + i);
                                cb.putfield(mutDesc, "tracker", trackerDesc);
                                cb.dup();
                                cb.lload(tickVar);
                                cb.putfield(mutDesc, "tick", ConstantDescs.CD_long);
                                cb.astore(mutLocal[i]);
                                cb.aload(mutLocal[i]);
                            } else {
                                // Regular Mut path (non-SoA or @ValueTracked).
                                var mutInitDesc = MethodTypeDesc.of(
                                    ConstantDescs.CD_void,
                                    recordDesc,              // value
                                    ConstantDescs.CD_int,    // slot
                                    trackerDesc,             // tracker
                                    ConstantDescs.CD_long,   // tick
                                    ConstantDescs.CD_boolean  // valueTracked
                                );
                                cb.new_(mutDesc);
                                cb.dup();
                                if (isSoA[i]) {
                                    emitSoARecordReconstruction(cb, componentTypes[i],
                                        soaFlatFields[i], new int[]{0}, soaFieldLocals[i], slotVar);
                                } else if (useDefaultStorageFactory) {
                                    cb.aload(firstStorageVar + i);
                                    cb.iload(slotVar);
                                    cb.aaload();
                                } else {
                                    cb.aload(firstStorageVar + i);
                                    cb.iload(slotVar);
                                    cb.invokeinterface(storageDesc, "get", storageGetDesc);
                                }
                                cb.iload(slotVar);               // slot
                                cb.aload(firstTrackerVar + i);   // tracker
                                cb.lload(tickVar);               // tick
                                boolean vt = componentTypes[i].isAnnotationPresent(
                                    zzuegg.ecs.component.ValueTracked.class);
                                cb.ldc(vt ? 1 : 0);             // valueTracked
                                cb.invokespecial(mutDesc, "<init>", mutInitDesc);
                                cb.astore(mutLocal[i]);
                                cb.aload(mutLocal[i]);
                            }
                        }
                        case ENTITY -> {
                            // entities[slot] via the hoisted raw Entity[]
                            // local — one aaload, no virtual dispatch.
                            cb.aload(entityArrayLocal);
                            cb.iload(slotVar);
                            cb.aaload();
                        }
                        case SERVICE -> {
                            // Hoisted in the preamble — just reload the
                            // cast reference from the local.
                            cb.aload(serviceLocal[i]);
                        }
                    }
                }

                // Call the system method
                if (isStatic) {
                    cb.invokestatic(systemClassDesc, method.getName(), systemMethodDesc);
                } else {
                    cb.invokevirtual(systemClassDesc, method.getName(), systemMethodDesc);
                }

                // Post-call: for each write param, check isChanged() then
                // flush + write back. Hidden Mut path bypasses flush() and
                // reads pnd_ primitives directly into SoA arrays.
                var mutIsChangedDesc = MethodTypeDesc.of(ConstantDescs.CD_boolean);
                var markChangedDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                    ConstantDescs.CD_int, ConstantDescs.CD_long);
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] != ParamKind.WRITE) continue;
                    var skipFlush = cb.newLabel();
                    cb.aload(mutLocal[i]);
                    cb.invokevirtual(mutDesc, "isChanged", mutIsChangedDesc);
                    cb.ifeq(skipFlush); // skip if !isChanged

                    if (hiddenMutInfos[i] != null) {
                        var hmi = hiddenMutInfos[i];
                        var hmFf = hmi.flatFields();
                        var hmDesc = hmi.classDesc();

                        // set() already decomposed the Record into native-typed
                        // cur_ fields via the inlined decompose$ helper. The
                        // Record never escapes — C2 can fully scalarize it.

                        if (paramValueTracked[i]) {
                            // @ValueTracked: compare cur_ (new) against SoA (old).
                            // Both are native primitives — use type-appropriate compare.
                            var doFlush = cb.newLabel();
                            for (int f = 0; f < hmFf.size(); f++) {
                                var ff = hmFf.get(f);
                                var fieldDesc = ff.type().describeConstable().orElseThrow();
                                // Load old value from SoA
                                cb.aload(soaFieldLocals[i][f]);
                                cb.iload(slotVar);
                                zzuegg.ecs.storage.SoAComponentStorage.emitArrayLoad(cb, ff.type());
                                // Load new value from cur_
                                cb.aload(mutLocal[i]);
                                cb.getfield(hmDesc, "cur_" + ff.flatName(), fieldDesc);
                                emitPrimitiveNotEqual(cb, ff.type(), doFlush);
                            }
                            cb.goto_(skipFlush);
                            cb.labelBinding(doFlush);
                        }

                        // markChanged + write cur_ fields to SoA arrays (native types).
                        cb.aload(firstTrackerVar + i);
                        cb.iload(slotVar);
                        cb.lload(tickVar);
                        cb.invokevirtual(trackerDesc, "markChanged", markChangedDesc);
                        for (int f = 0; f < hmFf.size(); f++) {
                            var ff = hmFf.get(f);
                            var fieldDesc = ff.type().describeConstable().orElseThrow();
                            cb.aload(soaFieldLocals[i][f]);
                            cb.iload(slotVar);
                            cb.aload(mutLocal[i]);
                            cb.getfield(hmDesc, "cur_" + ff.flatName(), fieldDesc);
                            zzuegg.ecs.storage.SoAComponentStorage.emitArrayStore(cb, ff.type());
                        }
                    } else {
                        cb.aload(mutLocal[i]);
                        cb.invokevirtual(mutDesc, "flush", mutFlushDesc);
                        cb.astore(tempValueVar);

                        if (isSoA[i]) {
                            var ff = soaFlatFields[i];
                            for (int f = 0; f < ff.size(); f++) {
                                cb.aload(soaFieldLocals[i][f]);
                                cb.iload(slotVar);
                                cb.aload(tempValueVar);
                                cb.checkcast(componentTypes[i].describeConstable().orElseThrow());
                                emitAccessorChainOnStack(cb, componentTypes[i], ff.get(f).accessors());
                                zzuegg.ecs.storage.SoAComponentStorage.emitArrayStore(cb, ff.get(f).type());
                            }
                            } else if (useDefaultStorageFactory) {
                            cb.aload(firstStorageVar + i);
                            cb.iload(slotVar);
                            cb.aload(tempValueVar);
                            cb.aastore();
                        } else {
                            cb.aload(firstStorageVar + i);
                            cb.iload(slotVar);
                            cb.aload(tempValueVar);
                            cb.invokeinterface(storageDesc, "set", storageSetDesc);
                        }
                    }
                    cb.labelBinding(skipFlush);
                }

                cb.labelBinding(loopContinue);
                if (!hasFilters) {
                    cb.iinc(slotVar, 1);
                }
                cb.goto_(loopStart);
                cb.labelBinding(loopEnd);
            };

            // process(Chunk chunk, long currentTick) — interface method.
            // Delegates to the entity loop emitter with chunk at param 1, tick at param 2.
            clb.withMethodBody("process",
                MethodTypeDesc.of(ConstantDescs.CD_void, chunkDesc, ConstantDescs.CD_long),
                ACC_PUBLIC, cb -> {
                    // chunk = param 1, tick = param 2, first free local = 4
                    emitEntityLoop.emit(cb, 1, 2, 4);
                    cb.return_();
                });

            // processAll(List<Chunk>, long) — self-contained: the chunk loop
            // AND the entity loop are emitted inline in one method body.
            // No delegation to process() — the JIT compiles this as a single
            // compilation unit, so EA can scalar-replace Mut and records
            // without being blocked by "already compiled" heuristics.
            var listDescLocal = ClassDesc.of("java.util.List");
            clb.withMethodBody("processAll",
                MethodTypeDesc.of(ConstantDescs.CD_void, listDescLocal, ConstantDescs.CD_long),
                ACC_PUBLIC, cb -> {
                    // Local layout:
                    //   0  this
                    //   1  chunks (List)
                    //   2-3 tick (long)
                    //   4  chunkCount (int)
                    //   5  chunkIdx (int)
                    //   6  currentChunk (Chunk)
                    //   7+ entity loop locals (via emitEntityLoop baseLocal=7)
                    int chunkCountLocal = 4;
                    int chunkIdxLocal = 5;
                    int currentChunkLocal = 6;
                    int entityLoopBase = 7;

                    // int chunkCount = chunks.size();
                    cb.aload(1);
                    cb.invokeinterface(listDescLocal, "size", MethodTypeDesc.of(ConstantDescs.CD_int));
                    cb.istore(chunkCountLocal);
                    // for (int ci = 0; ci < chunkCount; ci++)
                    cb.iconst_0();
                    cb.istore(chunkIdxLocal);
                    var outerStart = cb.newLabel();
                    var outerEnd = cb.newLabel();
                    cb.labelBinding(outerStart);
                    cb.iload(chunkIdxLocal);
                    cb.iload(chunkCountLocal);
                    cb.if_icmpge(outerEnd);
                    // Chunk chunk = (Chunk) chunks.get(ci);
                    cb.aload(1);
                    cb.iload(chunkIdxLocal);
                    cb.invokeinterface(listDescLocal, "get",
                        MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int));
                    cb.checkcast(chunkDesc);
                    cb.astore(currentChunkLocal);
                    // Inline the full entity loop for this chunk.
                    // chunk = currentChunkLocal, tick = param 2
                    emitEntityLoop.emit(cb, currentChunkLocal, 2, entityLoopBase);
                    cb.iinc(chunkIdxLocal, 1);
                    cb.goto_(outerStart);
                    cb.labelBinding(outerEnd);
                    cb.return_();
                });
        });

        // Optional dump of generated bytecode for inspection. Set
        // -Dzzuegg.ecs.dumpGenerated=/path/to/dir to write every
        // hidden class to disk as <descName>.class. Used to audit
        // the tier-1 hot path with javap -c.
        var dumpDir = java.lang.System.getProperty("zzuegg.ecs.dumpGenerated");
        if (dumpDir != null) {
            try {
                var path = java.nio.file.Path.of(dumpDir, genName.replace('.', '_') + ".class");
                java.nio.file.Files.createDirectories(path.getParent());
                java.nio.file.Files.write(path, bytes);
            } catch (Exception ignored) { /* best-effort */ }
        }

        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        if (!isStatic) {
            clazz.getField("inst").set(instance, desc.instance());
        }

        // cids is indexed by ComponentAccess order, matching
        // paramToAccessIdx[i] for READ/WRITE param slots.
        var compIds = new ComponentId[accesses.size()];
        for (int i = 0; i < accesses.size(); i++) {
            compIds[i] = accesses.get(i).componentId();
        }
        clazz.getField("cids").set(instance, compIds);

        if (hasWrites) {
            var muts = new Mut[paramCount];
            for (int i = 0; i < paramCount; i++) {
                if (kinds[i] == ParamKind.WRITE) {
                    // @ValueTracked is a type-level annotation on the record
                    // itself, so we can read it without needing the registry.
                    boolean valueTracked = componentTypes[i].isAnnotationPresent(
                        zzuegg.ecs.component.ValueTracked.class);
                    muts[i] = new Mut<>(null, 0, null, 0, valueTracked);
                }
            }
            clazz.getField("muts").set(instance, muts);
        }

        if (hasServices) {
            // services is just the same resolvedServiceArgs the plan uses —
            // indexed by parameter slot, with nulls for non-service slots.
            clazz.getField("services").set(instance, serviceArgs);
        }

        if (hasFilters) {
            clazz.getField("plan").set(instance, plan);
            // Flatten all target ComponentIds across all filter groups
            // into a single array. Multi-target @Filter groups contribute
            // multiple entries; single-target groups contribute one.
            var allIds = new java.util.ArrayList<ComponentId>();
            for (var f : resolvedFilters) {
                for (var tid : f.targetIds()) allIds.add(tid);
            }
            clazz.getField("filterCids").set(instance, allIds.toArray(ComponentId[]::new));
            if (multiTargetFilter) {
                int maxChunkSize = 1024;
                clazz.getField("seenBitmap").set(instance, new long[(maxChunkSize + 63) >>> 6]);
                clazz.getField("resultBuf").set(instance, new int[maxChunkSize]);
                clazz.getField("filterTrackers").set(instance,
                    new zzuegg.ecs.change.ChangeTracker[filterTargetCount]);
            }
        }

        return (ChunkProcessor) instance;
    }

    /**
     * Fallback: uses invokeExact with arity specialization.
     */
    private static ChunkProcessor generateWithInvokeExact(SystemDescriptor desc) {
        try {
            var method = desc.method();
            var accesses = desc.componentAccesses();
            int paramCount = method.getParameterCount();

            var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            var mh = lookup.unreflect(method);
            if (desc.instance() != null) {
                mh = mh.bindTo(desc.instance());
            }

            var objectTypes = new Class<?>[paramCount];
            java.util.Arrays.fill(objectTypes, Object.class);
            var genericMh = mh.asType(MethodType.methodType(void.class, objectTypes));

            var compIds = new ComponentId[paramCount];
            for (int i = 0; i < paramCount; i++) {
                compIds[i] = accesses.get(i).componentId();
            }

            final var finalMh = genericMh;
            final var finalCompIds = compIds;

            return switch (paramCount) {
                case 1 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 2 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 3 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    var s2 = chunk.componentStorage(finalCompIds[2]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                case 4 -> (chunk, tick) -> {
                    var s0 = chunk.componentStorage(finalCompIds[0]);
                    var s1 = chunk.componentStorage(finalCompIds[1]);
                    var s2 = chunk.componentStorage(finalCompIds[2]);
                    var s3 = chunk.componentStorage(finalCompIds[3]);
                    int count = chunk.count();
                    try { for (int slot = 0; slot < count; slot++) finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot), (Object) s3.get(slot)); }
                    catch (Throwable e) { throw new RuntimeException(e); }
                };
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }
}
