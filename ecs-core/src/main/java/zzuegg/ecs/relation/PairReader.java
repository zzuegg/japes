package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;

import java.util.Optional;

/**
 * World-scoped read-only view over one relation type. Resolved as a
 * service parameter on systems that declare {@code PairReader<T>}, and
 * typically paired with {@code @Pair(T.class)} to narrow the system's
 * archetype filter to entities that carry at least one pair of type
 * {@code T}.
 *
 * <p>The reader is not per-entity — the system body passes its own
 * {@code self} entity into {@link #fromSource} or {@link #withTarget}
 * to get entity-local iteration. This matches the shape of
 * {@code Commands} and {@code Res<T>}: world-scoped handle, per-call
 * narrowing.
 *
 * @param <T> the relation payload record type
 */
public interface PairReader<T extends Record> {

    /** A single relation pair: the source entity, the target entity, and the payload. */
    record Pair<T extends Record>(Entity source, Entity target, T value) {}

    /** Every target {@code source} is currently pointing at with a {@code T} relation. */
    Iterable<Pair<T>> fromSource(Entity source);

    /** Every source currently pointing at {@code target} with a {@code T} relation. */
    Iterable<Pair<T>> withTarget(Entity target);

    /**
     * Cheap existence check: {@code true} iff {@code source} currently
     * has at least one {@code T} pair. Avoids the snapshot allocation
     * that {@link #fromSource(Entity)} would otherwise pay just to
     * check {@code iterator().hasNext()}.
     */
    boolean hasSource(Entity source);

    /**
     * Cheap existence check: {@code true} iff at least one source
     * currently points a {@code T} relation at {@code target}.
     * Avoids the snapshot allocation that {@link #withTarget(Entity)}
     * would otherwise pay.
     */
    boolean hasTarget(Entity target);

    /** Direct lookup: the payload of the {@code (source, target)} pair if it exists. */
    Optional<T> get(Entity source, Entity target);

    /** Total number of {@code T} pairs in the world. */
    int size();
}
