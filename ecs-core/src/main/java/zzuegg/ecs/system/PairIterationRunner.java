package zzuegg.ecs.system;

/**
 * Runtime shape of a tier-1 generated {@code @ForEachPair}
 * processor. The scheduler calls {@link #run(long)} once per tick
 * with the current tick counter; the generated {@code run} walks
 * the driving relation store's forward index directly and invokes
 * the user method per pair via {@code invokevirtual}, with cached
 * per-archetype source and target storages.
 *
 * <p>Parallels {@link ChunkProcessor} for per-entity systems — same
 * "scheduler-calls-trivial-interface-method, JIT inlines the user
 * body" contract, just driven by relation store iteration instead
 * of chunk iteration.
 */
public interface PairIterationRunner {
    void run(long tick);
}
