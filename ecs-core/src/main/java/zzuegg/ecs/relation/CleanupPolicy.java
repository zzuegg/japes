package zzuegg.ecs.relation;

/**
 * What to do with pairs that point AT an entity when that entity is
 * despawned. Set per-relation-type via
 * {@code @Relation(onTargetDespawn = ...)} on the record definition.
 *
 * <p>The default is {@link #RELEASE_TARGET} — individual pairs simply
 * vanish from their sources when the target dies. Dangling references
 * are never observable unless {@link #IGNORE} is explicitly chosen.
 */
public enum CleanupPolicy {

    /**
     * Default. When the target is despawned, drop every pair pointing
     * at it. Each source observes this as a normal pair removal
     * (tracker + removal log entry) and loses its marker if it runs
     * out of pairs of this type. The source entity itself is
     * unaffected.
     */
    RELEASE_TARGET,

    /**
     * When the target is despawned, despawn every source that was
     * pointing at it. Cascades transitively — sources of those sources
     * are cleaned up in the same pass, bounded by the entity count.
     * Use sparingly; it can trigger large chained deletions.
     */
    CASCADE_SOURCE,

    /**
     * Do nothing. Pairs pointing at the despawned target stay in the
     * store. Subsequent reads can return pair data whose target is no
     * longer alive. The user is responsible for checking liveness
     * before acting on a target entity.
     */
    IGNORE
}
