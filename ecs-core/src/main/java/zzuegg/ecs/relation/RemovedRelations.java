package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.SystemExecutionPlan;

import java.util.Iterator;
import java.util.List;

/**
 * System parameter providing access to every pair of relation type
 * {@code T} that was removed since this system last ran. Parallels
 * {@code RemovedComponents<T>} but keyed on the pair identity
 * {@code (source, target)} plus the payload at the moment of removal.
 *
 * <p>Removal sources tracked:
 * <ul>
 *   <li>{@code World.removeRelation(source, target, T)}</li>
 *   <li>{@code World.removeAllRelations(source, T)}</li>
 *   <li>{@code World.despawn(entity)} when a pair touching the
 *       despawned entity is dropped via its cleanup policy</li>
 *   <li>{@code Commands.removeRelation} via CommandProcessor</li>
 * </ul>
 *
 * <p>The iterator is single-use per tick — iterating consumes the
 * current window; the system's watermark advances on return so
 * multiple systems each observe every removal once.
 */
public interface RemovedRelations<T extends Record> extends Iterable<RemovedRelations.Removal<T>> {

    /**
     * A single pair removal event: source, target, and the payload
     * that was in the store at the instant the pair was dropped.
     */
    record Removal<T extends Record>(Entity source, Entity target, T lastValue) {}

    @Override
    Iterator<Removal<T>> iterator();

    /** Snapshot as a list — useful for eager consumers. */
    List<Removal<T>> asList();

    /** {@code true} iff no new removals are visible to this reader. */
    boolean isEmpty();

    /**
     * Framework entry point. Not intended for user code; {@code World}
     * calls this during execution-plan build.
     */
    static <T extends Record> RemovedRelations<T> bind(PairRemovalLog log, SystemExecutionPlan plan) {
        return new RemovedRelationsImpl<>(log, plan);
    }
}
