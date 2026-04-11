package zzuegg.ecs.system;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;

import static java.lang.classfile.ClassFile.*;

/**
 * Tier-1 bytecode generator for {@code @Exclusive} systems whose
 * parameters are all framework-provided services (no component
 * arguments, no entity arguments, no pair walker — the narrowest
 * subset of system shapes). Emits a hidden class per system with a
 * {@code run()} method that:
 *
 * <ol>
 *   <li>Loads each resolved service from the hoisted {@code args}
 *       {@code Object[]} field,</li>
 *   <li>Casts it to the declared parameter type,</li>
 *   <li>Invokes the user method via a direct {@code invokevirtual}
 *       (or {@code invokestatic} for static methods).</li>
 * </ol>
 *
 * <p>Replaces the reflective path that went through
 * {@link SystemInvoker} — {@code MethodHandle.asSpreader(Object[])}
 * plus a {@code MethodHandle.invoke} call — for the common case of
 * cleanup / setup systems that take only services. The win is
 * modest in absolute terms (exclusive systems are called at most a
 * handful of times per tick), but it eliminates one reflective
 * boundary entirely and the generated body inlines through
 * {@code invokevirtual} just like the per-entity chunk processors.
 *
 * <p>Unsupported cases fall back to {@link SystemInvoker}:
 * <ul>
 *   <li>Non-{@code @Exclusive} systems (they have component args —
 *       use {@code GeneratedChunkProcessor} or
 *       {@code GeneratedPairIterationProcessor} instead),</li>
 *   <li>Methods with no declaring-class access (inaccessible).</li>
 * </ul>
 */
public final class GeneratedExclusiveProcessor {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER =
        new java.util.concurrent.atomic.AtomicLong();

    private GeneratedExclusiveProcessor() {}

    /**
     * Report why a given system isn't eligible for tier-1 generation.
     * Returns {@code null} if the system shape is fully supported.
     */
    static String skipReason(SystemDescriptor desc) {
        if (!desc.isExclusive()) return "not an @Exclusive system";
        if (desc.method() == null) return "no method";
        return null;
    }

    /**
     * Try to emit a tier-1 runner. Returns {@code null} on any
     * unsupported shape — the caller should keep the reflective
     * {@link SystemInvoker} path.
     *
     * @param desc     the system descriptor (must be {@code @Exclusive})
     * @param argsRef  the plan's pre-resolved {@code Object[]} args
     *                 array. The runner holds a reference to this
     *                 array and re-reads each slot per call, so the
     *                 scheduler can update service entries mid-tick
     *                 (e.g., Commands buffer rotation) and the runner
     *                 picks up the new values transparently.
     */
    public static ExclusiveRunner tryGenerate(SystemDescriptor desc, Object[] argsRef) {
        var reason = skipReason(desc);
        if (reason != null) return null;
        try {
            return generate(desc, argsRef);
        } catch (Exception e) {
            throw new RuntimeException(
                "Tier-1 @Exclusive generation failed for " + desc.name(), e);
        }
    }

    private static ExclusiveRunner generate(SystemDescriptor desc, Object[] argsRef) throws Exception {
        var method = desc.method();
        var params = method.getParameterTypes();
        int paramCount = params.length;
        boolean isStatic = Modifier.isStatic(method.getModifiers());

        var systemLookup = MethodHandles.privateLookupIn(
            method.getDeclaringClass(), MethodHandles.lookup());
        var systemClassDesc = method.getDeclaringClass().describeConstable().orElseThrow();

        var runnerDesc = ClassDesc.of("zzuegg.ecs.system.ExclusiveRunner");
        var objDesc = ConstantDescs.CD_Object;
        var objArrDesc = objDesc.arrayType();

        var userMethodParams = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            userMethodParams[i] = params[i].describeConstable().orElseThrow();
        }
        var userMethodDesc = MethodTypeDesc.of(ConstantDescs.CD_void, userMethodParams);

        var sanitized = sanitize(desc.name());
        var genName = method.getDeclaringClass().getPackageName()
            + "." + "ExclProc_" + sanitized + "_" + COUNTER.incrementAndGet();
        var genDesc = ClassDesc.of(genName);

        byte[] bytes = ClassFile.of().build(genDesc, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL);
            clb.withSuperclass(ConstantDescs.CD_Object);
            clb.withInterfaces(clb.constantPool().classEntry(runnerDesc));

            clb.withField("inst", objDesc, fb -> fb.withFlags(ACC_PUBLIC));
            clb.withField("args", objArrDesc, fb -> fb.withFlags(ACC_PUBLIC));

            // Default constructor: just super().
            clb.withMethodBody("<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                    cb.aload(0);
                    cb.invokespecial(ConstantDescs.CD_Object, "<init>",
                        MethodTypeDesc.of(ConstantDescs.CD_void));
                    cb.return_();
                });

            // run() — loads args[i] per param, casts to declared type,
            // invokes the user method.
            clb.withMethodBody("run",
                MethodTypeDesc.of(ConstantDescs.CD_void), ACC_PUBLIC, cb -> {
                    if (!isStatic) {
                        cb.aload(0);
                        cb.getfield(genDesc, "inst", objDesc);
                        cb.checkcast(systemClassDesc);
                    }
                    // Hoist args array once into a local.
                    cb.aload(0);
                    cb.getfield(genDesc, "args", objArrDesc);
                    cb.astore(1);
                    for (int i = 0; i < paramCount; i++) {
                        cb.aload(1);
                        cb.ldc(i);
                        cb.aaload();
                        cb.checkcast(userMethodParams[i]);
                    }
                    if (isStatic) {
                        cb.invokestatic(systemClassDesc, method.getName(), userMethodDesc);
                    } else {
                        cb.invokevirtual(systemClassDesc, method.getName(), userMethodDesc);
                    }
                    cb.return_();
                });
        });

        var hiddenLookup = systemLookup.defineHiddenClass(bytes, true);
        var clazz = hiddenLookup.lookupClass();
        var instance = clazz.getDeclaredConstructor().newInstance();

        if (!isStatic) {
            clazz.getField("inst").set(instance, desc.instance());
        }
        clazz.getField("args").set(instance, argsRef);

        return (ExclusiveRunner) instance;
    }

    private static String sanitize(String name) {
        var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9' && i > 0);
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
