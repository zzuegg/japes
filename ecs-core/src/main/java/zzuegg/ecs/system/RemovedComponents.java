package zzuegg.ecs.system;

import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.Iterator;
import java.util.List;

/**
 * System parameter providing access to every component of type {@code T}
 * that was removed since this system last ran. Each record exposes the
 * {@link Entity} that owned the component and the component's last-known
 * value at the moment of removal (records are immutable, so the reference
 * is safe to retain).
 *
 * <p>Removal sources tracked:
 * <ul>
 *   <li>{@code World.removeComponent(entity, T)}</li>
 *   <li>{@code World.despawn(entity)} — every component the entity had</li>
 *   <li>{@code Commands.remove} / {@code Commands.despawn} (via CommandProcessor)</li>
 * </ul>
 *
 * <p>The iterator is single-use per tick: iterating consumes the current
 * window and advances the system's watermark on return from the system body.
 * Two systems observing the same component each see every removal once.
 */
public interface RemovedComponents<T extends Record> extends Iterable<RemovedComponents.Removal<T>> {

    /**
     * A single removal event: the entity that lost the component and its
     * value at the moment of removal.
     */
    record Removal<T extends Record>(Entity entity, T value) {}

    @Override
    Iterator<Removal<T>> iterator();

    /** Returns the snapshot as a list — useful for eager consumers. */
    List<Removal<T>> asList();

    /** True if no new removals are visible to this reader. */
    boolean isEmpty();

    /**
     * Framework entry point for constructing a reader bound to a specific
     * plan. Not intended for user code; {@code World} calls this during
     * execution-plan build.
     */
    static <T extends Record> RemovedComponents<T> bind(RemovalLog log, ComponentId targetId, SystemExecutionPlan plan) {
        return new RemovedComponentsImpl<>(log, targetId, plan);
    }
}
