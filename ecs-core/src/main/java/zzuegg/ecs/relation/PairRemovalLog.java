package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-relation-type removal log. Parallels
 * {@code zzuegg.ecs.change.RemovalLog} but keyed only by pair identity
 * — the containing {@link RelationStore} is already per-type, so
 * there's no need for a component id discriminator.
 *
 * <p>Semantics match the component removal log:
 * <ul>
 *   <li>{@link #append} is a no-op unless at least one consumer has
 *       registered — pure-write workloads with no {@code RemovedRelations}
 *       observers never pay for retention.</li>
 *   <li>{@link #snapshot(long)} returns entries whose tick is strictly
 *       greater than the given {@code sinceExclusive} watermark.</li>
 *   <li>{@link #collectGarbage(long)} drops entries with
 *       {@code tick <= newMinWatermark} and advances the minimum
 *       watermark monotonically.</li>
 * </ul>
 */
public final class PairRemovalLog {

    public record Entry(Entity source, Entity target, Record value, long tick) {}

    private final List<Entry> entries = new ArrayList<>();
    private int consumerCount = 0;
    private long minWatermark = 0L;

    /**
     * Register a {@code RemovedRelations<T>} consumer. Must be called
     * before {@link #append} will retain anything.
     */
    public void registerConsumer() {
        consumerCount++;
    }

    /**
     * Append a removal event. Short-circuits when no consumer is
     * registered so we don't grow a list nobody will read.
     */
    public void append(Entity source, Entity target, Record value, long tick) {
        if (consumerCount == 0) return;
        entries.add(new Entry(source, target, value, tick));
    }

    /**
     * Snapshot every entry whose tick is strictly greater than
     * {@code sinceExclusive}. Returned list is a fresh copy, safe to
     * iterate while callers continue writing.
     */
    public List<Entry> snapshot(long sinceExclusive) {
        if (entries.isEmpty()) return List.of();
        var out = new ArrayList<Entry>();
        for (var e : entries) {
            if (e.tick() > sinceExclusive) out.add(e);
        }
        return out;
    }

    /**
     * Advance the minimum watermark. Drops entries whose tick is
     * {@code <= newMinWatermark}. Ignored if {@code newMinWatermark}
     * regresses below the current minimum.
     */
    public void collectGarbage(long newMinWatermark) {
        if (newMinWatermark <= minWatermark) return;
        minWatermark = newMinWatermark;

        int drop = 0;
        while (drop < entries.size() && entries.get(drop).tick() <= newMinWatermark) {
            drop++;
        }
        if (drop == entries.size()) entries.clear();
        else if (drop > 0) entries.subList(0, drop).clear();
    }

    /** Drop all entries. Consumer registrations are preserved. */
    public void clear() {
        entries.clear();
    }

    /** Current minimum watermark — for tests + scheduler bookkeeping. */
    public long minWatermark() {
        return minWatermark;
    }

    /**
     * Drop all consumer registrations. Used by
     * {@code World.rebuildSchedule} when the system plan changes and
     * the old consumer counts no longer reflect reality.
     */
    public void clearConsumers() {
        consumerCount = 0;
        minWatermark = 0L;
        entries.clear();
    }
}
