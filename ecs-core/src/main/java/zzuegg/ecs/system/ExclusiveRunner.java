package zzuegg.ecs.system;

/**
 * Runtime shape of a tier-1 generated {@code @Exclusive} system
 * runner. The scheduler calls {@link #run()} once per tick; the
 * generated {@code run()} method unboxes a pre-resolved
 * {@code Object[]} args array and invokes the user method via
 * direct {@code invokevirtual} — no {@link SystemInvoker}
 * {@code MethodHandle.invokeExact} spreader call, no reflection.
 *
 * <p>Parallels {@link PairIterationRunner} for {@code @ForEachPair}
 * systems — same "scheduler-calls-trivial-interface-method, JIT
 * inlines the user body" contract, with the narrowest possible
 * generated body for service-only systems.
 */
public interface ExclusiveRunner {
    void run();
}
