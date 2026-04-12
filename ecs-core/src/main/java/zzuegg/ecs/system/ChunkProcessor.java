package zzuegg.ecs.system;

import zzuegg.ecs.storage.Chunk;

import java.util.List;

@FunctionalInterface
public interface ChunkProcessor {
    void process(Chunk chunk, long currentTick);

    /**
     * Process all chunks for this system. The default delegates to
     * {@link #process(Chunk, long)} per chunk. The key benefit: when a
     * generated hidden class inherits this default, the JIT compiles
     * {@code processAll} into the hidden class, making the inner
     * {@code this.process(chunk, tick)} call <b>monomorphic</b> (a
     * self-dispatch). This lets the JIT inline {@code process()},
     * which enables escape analysis on Mut and record allocations.
     *
     * <p>Without this, the system scheduler calls {@code processor.process()}
     * through a shared loop — the JIT sees all system processors at that
     * single call site (megamorphic), preventing inlining and EA.
     */
    default void processAll(List<Chunk> chunks, long currentTick) {
        for (int i = 0, n = chunks.size(); i < n; i++) {
            process(chunks.get(i), currentTick);
        }
    }
}
