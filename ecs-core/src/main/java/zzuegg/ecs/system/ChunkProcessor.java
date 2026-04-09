package zzuegg.ecs.system;

import zzuegg.ecs.storage.Chunk;

@FunctionalInterface
public interface ChunkProcessor {
    void process(Chunk chunk, long currentTick);
}
