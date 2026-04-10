package zzuegg.ecs.system;

import zzuegg.ecs.component.ComponentId;
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
    private static final String GENERATED_PACKAGE = "zzuegg.ecs.generated";

    private GeneratedChunkProcessor() {}

    /**
     * Build a valid JVM class name for a hidden processor.
     * desc.name() is "Class.method" — the dot is a JVM package separator, so we
     * sanitise it (and any other illegal identifier characters) into '_' before
     * embedding the name in ClassDesc.of. A monotonic counter guarantees
     * uniqueness across rebuilds instead of relying on nanoTime() resolution.
     */
    static String generateClassName(String descName) {
        var sb = new StringBuilder(descName.length());
        for (int i = 0; i < descName.length(); i++) {
            char c = descName.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && i > 0);
            sb.append(ok ? c : '_');
        }
        return GENERATED_PACKAGE + ".Proc_" + sb + "_" + COUNTER.incrementAndGet();
    }

    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        var method = desc.method();
        var params = method.getParameters();

        // Only handle read-only systems with 1-4 component params, no service params, no filters
        boolean allRead = true;
        boolean hasService = false;
        for (var param : params) {
            if (!param.isAnnotationPresent(Read.class)) {
                if (param.isAnnotationPresent(Write.class)) allRead = false;
                else hasService = true;
            }
        }
        if (!allRead || hasService || params.length < 1 || params.length > 4) return null;
        if (!desc.whereFilters().isEmpty()) return null;

        try {
            return generateWithBytecode(desc);
        } catch (Exception e) {
            // Fall back to invokeExact path
            return generateWithInvokeExact(desc);
        }
    }

    /**
     * Generate a hidden class with invokevirtual/invokestatic to the system method.
     * This is the fastest path — the JIT can fully inline the system call.
     */
    private static ChunkProcessor generateWithBytecode(SystemDescriptor desc) throws Exception {
        var method = desc.method();
        var accesses = desc.componentAccesses();
        int paramCount = method.getParameterCount();
        boolean isStatic = Modifier.isStatic(method.getModifiers());

        // Get lookup with access to the system class
        var systemLookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());

        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();
        var storageDesc = ClassDesc.of("zzuegg.ecs.storage.ComponentStorage");
        var chunkDesc = ClassDesc.of("zzuegg.ecs.storage.Chunk");
        var compIdDesc = ClassDesc.of("zzuegg.ecs.component.ComponentId");
        var processorDesc = ClassDesc.of("zzuegg.ecs.system.ChunkProcessor");
        var genName = generateClassName(desc.name());
        var genDesc = ClassDesc.of(genName);
        var objDesc = ConstantDescs.CD_Object;

        // Build the method type descriptor for the system method
        var paramDescs = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            paramDescs[i] = method.getParameterTypes()[i].describeConstable().orElseThrow();
        }
        var systemMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs);

        // Build: class that implements ChunkProcessor with fields for instance + compIds
        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(processorDesc));

            // Field: system instance (if non-static)
            if (!isStatic) {
                clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            }

            // Fields: ComponentId[] for each param
            var compIdArrayDesc = compIdDesc.arrayType();
            clb.withField("cids", compIdArrayDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // Constructor
            clb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                cb.aload(0);
                cb.invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));
                cb.return_();
            });

            // process(Chunk chunk, long currentTick) method
            clb.withMethodBody("process",
                MethodTypeDesc.of(ConstantDescs.CD_void, chunkDesc, ConstantDescs.CD_long),
                ACC_PUBLIC, cb -> {

                // Local vars: 0=this, 1=chunk, 2-3=tick(long), 4=count, 5=slot, 6..=storages
                int countVar = 4;
                int slotVar = 5;
                int firstStorageVar = 6;

                // Get count
                cb.aload(1); // chunk
                cb.invokevirtual(chunkDesc, "count", MethodTypeDesc.of(ConstantDescs.CD_int));
                cb.istore(countVar);

                // Load storages: storageN = chunk.componentStorage(cids[n])
                for (int i = 0; i < paramCount; i++) {
                    cb.aload(1); // chunk
                    cb.aload(0); // this
                    cb.getfield(genDesc, "cids", compIdArrayDesc);
                    cb.ldc(i);
                    cb.aaload();
                    cb.invokevirtual(chunkDesc, "componentStorage",
                        MethodTypeDesc.of(storageDesc, compIdDesc));
                    cb.astore(firstStorageVar + i);
                }

                // int slot = 0
                cb.iconst_0();
                cb.istore(slotVar);

                var loopStart = cb.newLabel();
                var loopEnd = cb.newLabel();

                cb.labelBinding(loopStart);
                cb.iload(slotVar);
                cb.iload(countVar);
                cb.if_icmpge(loopEnd);

                // Load instance if non-static
                if (!isStatic) {
                    cb.aload(0); // this
                    cb.getfield(genDesc, "inst", objDesc);
                    cb.checkcast(systemClassDesc);
                }

                // Load args: (Type) storageN.get(slot)
                for (int i = 0; i < paramCount; i++) {
                    cb.aload(firstStorageVar + i); // storage
                    cb.iload(slotVar); // slot
                    cb.invokeinterface(storageDesc, "get",
                        MethodTypeDesc.of(ClassDesc.of("java.lang.Record"), ConstantDescs.CD_int));
                    cb.checkcast(paramDescs[i]); // cast to actual component type
                }

                // Call system method
                if (isStatic) {
                    cb.invokestatic(systemClassDesc, method.getName(), systemMethodDesc);
                } else {
                    cb.invokevirtual(systemClassDesc, method.getName(), systemMethodDesc);
                }

                cb.iinc(slotVar, 1);
                cb.goto_(loopStart);
                cb.labelBinding(loopEnd);
                cb.return_();
            });
        });

        // Define hidden class using the system class's lookup (for access)
        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        // Set instance field
        if (!isStatic) {
            clazz.getField("inst").set(instance, desc.instance());
        }

        // Set compIds
        var compIds = new ComponentId[paramCount];
        for (int i = 0; i < paramCount; i++) {
            compIds[i] = accesses.get(i).componentId();
        }
        clazz.getField("cids").set(instance, compIds);

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
