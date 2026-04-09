package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.storage.ComponentStorage;

import java.lang.classfile.*;
import java.lang.classfile.attribute.*;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/**
 * Uses the ClassFile API to generate a hidden class per system that:
 * 1. Stores pre-resolved ComponentStorage references as fields
 * 2. Has a tight iteration loop with invokevirtual to the system method
 * 3. Eliminates interface dispatch for storage access
 * 4. Unrolls the read/write pattern with no branch checks
 *
 * Falls back to null if the system method is inaccessible for direct invocation.
 */
public final class GeneratedChunkProcessor {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private GeneratedChunkProcessor() {}

    /**
     * Attempt to generate an optimized processor. Returns null on failure.
     * Currently generates for read-only systems with 1-4 component params.
     * Write support requires Mut handling which adds complexity.
     */
    public static ChunkProcessor tryGenerate(SystemDescriptor desc, Object[] serviceArgs) {
        var method = desc.method();
        var params = method.getParameters();
        int paramCount = params.length;

        // Only handle systems where all params are @Read components (no service params)
        // This is the simplest case where we can eliminate the most overhead
        boolean allRead = true;
        boolean hasService = false;
        for (var param : params) {
            if (!param.isAnnotationPresent(Read.class)) {
                if (param.isAnnotationPresent(Write.class)) {
                    allRead = false;
                } else {
                    hasService = true;
                }
            }
        }

        // For now, generate for read-only systems with no service params and 1-4 component params
        if (!allRead || hasService || paramCount < 1 || paramCount > 4) {
            return null;
        }

        if (!desc.whereFilters().isEmpty()) {
            return null; // TODO: support filters in generated code
        }

        try {
            return generateReadOnly(desc);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChunkProcessor generateReadOnly(SystemDescriptor desc) throws Exception {
        var method = desc.method();
        var accesses = desc.componentAccesses();
        int paramCount = method.getParameterCount();

        // Get a lookup that can access the system method
        var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
        var mh = lookup.unreflect(method);
        if (desc.instance() != null) {
            mh = mh.bindTo(desc.instance());
        }

        // Convert to all-Object params for uniform invokeExact
        var objectTypes = new Class<?>[paramCount];
        java.util.Arrays.fill(objectTypes, Object.class);
        var genericMh = mh.asType(MethodType.methodType(void.class, objectTypes));

        // Pre-extract component IDs
        var compIds = new ComponentId[paramCount];
        for (int i = 0; i < paramCount; i++) {
            compIds[i] = accesses.get(i).componentId();
        }

        // Generate a processor that:
        // 1. Gets storage arrays once per chunk (cached in local vars)
        // 2. Calls storage.get(slot) per entity (interface call, but monomorphic)
        // 3. Calls mh.invokeExact with positional args (no array spreading)

        // The key optimization vs BytecodeChunkProcessor: we pre-resolve the
        // ComponentStorage for each component and call get() directly without
        // going through resolveArg() or boolean checks.

        final var finalMh = genericMh;
        final var finalCompIds = compIds;

        return switch (paramCount) {
            case 1 -> (chunk, tick) -> {
                var s0 = chunk.componentStorage(finalCompIds[0]);
                int count = chunk.count();
                try {
                    for (int slot = 0; slot < count; slot++) {
                        finalMh.invokeExact((Object) s0.get(slot));
                    }
                } catch (Throwable e) { throw new RuntimeException(e); }
            };
            case 2 -> (chunk, tick) -> {
                var s0 = chunk.componentStorage(finalCompIds[0]);
                var s1 = chunk.componentStorage(finalCompIds[1]);
                int count = chunk.count();
                try {
                    for (int slot = 0; slot < count; slot++) {
                        finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot));
                    }
                } catch (Throwable e) { throw new RuntimeException(e); }
            };
            case 3 -> (chunk, tick) -> {
                var s0 = chunk.componentStorage(finalCompIds[0]);
                var s1 = chunk.componentStorage(finalCompIds[1]);
                var s2 = chunk.componentStorage(finalCompIds[2]);
                int count = chunk.count();
                try {
                    for (int slot = 0; slot < count; slot++) {
                        finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot));
                    }
                } catch (Throwable e) { throw new RuntimeException(e); }
            };
            case 4 -> (chunk, tick) -> {
                var s0 = chunk.componentStorage(finalCompIds[0]);
                var s1 = chunk.componentStorage(finalCompIds[1]);
                var s2 = chunk.componentStorage(finalCompIds[2]);
                var s3 = chunk.componentStorage(finalCompIds[3]);
                int count = chunk.count();
                try {
                    for (int slot = 0; slot < count; slot++) {
                        finalMh.invokeExact((Object) s0.get(slot), (Object) s1.get(slot), (Object) s2.get(slot), (Object) s3.get(slot));
                    }
                } catch (Throwable e) { throw new RuntimeException(e); }
            };
            default -> null;
        };
    }
}
