package zzuegg.ecs.query;

import zzuegg.ecs.component.ComponentId;

/**
 * One component access declaration pulled from a system parameter.
 *
 * @param componentId resolved component id
 * @param type        concrete record class
 * @param accessType  READ or WRITE
 * @param fromTarget  {@code true} when the parameter was annotated
 *                    {@code @FromTarget} on a {@code @ForEachPair}
 *                    system; the scheduler resolves the component
 *                    against the target side of each pair instead
 *                    of the source side. Always {@code false} for
 *                    regular per-entity systems.
 */
public record ComponentAccess(
    ComponentId componentId,
    Class<? extends Record> type,
    AccessType accessType,
    boolean fromTarget
) {
    /**
     * Backwards-compatible constructor for call sites that predate
     * the {@link #fromTarget} flag — defaults the flag to
     * {@code false}, which is the correct behaviour for every
     * non-{@code @ForEachPair} path.
     */
    public ComponentAccess(ComponentId componentId, Class<? extends Record> type, AccessType accessType) {
        this(componentId, type, accessType, false);
    }
}
