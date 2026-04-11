package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;

/**
 * Identity of one {@code (source, target)} relation pair. Used as
 * the dirty-tracking key by {@link PairChangeTracker} and as the
 * lookup key for per-pair change ticks.
 *
 * <p>Equality is structural on both entity identities (which already
 * carry generation), so stale pairs from a reused slot do not
 * collide with fresh pairs on the same index.
 */
public record PairKey(Entity source, Entity target) {
}
