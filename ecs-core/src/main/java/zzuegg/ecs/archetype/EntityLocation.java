package zzuegg.ecs.archetype;

/**
 * Where an entity lives in the archetype graph. Holds a direct
 * {@link Archetype} reference (not an {@link ArchetypeId}) so hot paths
 * like {@code World.setComponent} can skip the
 * {@code archetypeGraph.get(archetypeId)} HashMap lookup entirely — the
 * archetype is reachable in one field load from the location.
 *
 * {@code archetypeId()} remains available as a convenience but just
 * forwards to {@code archetype().id()}.
 *
 * Note: a previous revision also carried a direct {@code Chunk}
 * reference on the location, but benchmarking showed it *regressed*
 * {@code setComponent}-heavy workloads by ~9 %. The JIT was able to
 * CSE {@code archetype.chunks().get(chunkIndex)} out of the loop when
 * the archetype was stable, whereas
 * {@code location.chunk()} varies per entity and prevents the hoist.
 * Reverted — chunkIndex wins.
 */
public record EntityLocation(Archetype archetype, int chunkIndex, int slotIndex) {

    public ArchetypeId archetypeId() {
        return archetype.id();
    }
}
