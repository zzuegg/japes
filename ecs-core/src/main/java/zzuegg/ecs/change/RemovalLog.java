package zzuegg.ecs.change;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-component log of removal events. Populated by World on despawn /
 * removeComponent / archetype migration paths, drained by
 * {@code RemovedComponents<T>} system parameters.
 *
 * <p>Each record carries the {@link Entity} that owned the component, the
 * component's last-known value (records are immutable, so the reference is
 * safe to retain), and the tick the removal happened at. Consumers use the
 * tick to advance their own watermark so each system sees every removal
 * exactly once.
 *
 * <p>Garbage collection: after a tick completes, entries whose tick is
 * older than the minimum watermark across all registered consumers of a
 * given component are dropped. Components with no consumers never retain
 * entries — the append path short-circuits.
 */
public final class RemovalLog {

    public record Entry(Entity entity, Record value, long tick) {}

    // componentId → append-ordered list of entries. Stable ordering is
    // intentional so consumers observe removals in the order they occurred.
    private final Map<ComponentId, List<Entry>> byComponent = new HashMap<>();

    // componentId → number of registered consumers. Zero consumers means
    // append() may skip storing anything.
    private final Map<ComponentId, Integer> consumerCount = new HashMap<>();

    // componentId → oldest tick any consumer still wants to see. Updated
    // whenever a consumer advances its watermark via advanceWatermark().
    private final Map<ComponentId, Long> minWatermark = new HashMap<>();

    public void registerConsumer(ComponentId id) {
        consumerCount.merge(id, 1, Integer::sum);
        minWatermark.putIfAbsent(id, 0L);
    }

    public void append(ComponentId id, Entity entity, Record value, long tick) {
        if (!consumerCount.containsKey(id)) return; // nobody cares
        byComponent.computeIfAbsent(id, k -> new ArrayList<>()).add(new Entry(entity, value, tick));
    }

    /**
     * Snapshot every entry for {@code id} whose tick is strictly greater than
     * {@code sinceExclusive}. Returned list is a copy — safe to iterate while
     * callers continue writing.
     */
    public List<Entry> snapshot(ComponentId id, long sinceExclusive) {
        var all = byComponent.get(id);
        if (all == null || all.isEmpty()) return List.of();
        var out = new ArrayList<Entry>();
        for (var e : all) {
            if (e.tick() > sinceExclusive) out.add(e);
        }
        return out;
    }

    /**
     * Advance the global minimum watermark for a component. After every
     * consumer has advanced past a tick, entries at or below that tick can
     * be dropped. This is called from World after each system with a
     * RemovedComponents<T> param finishes, passing the smallest watermark
     * across all current consumers.
     */
    public void collectGarbage(ComponentId id, long newMinWatermark) {
        var current = minWatermark.getOrDefault(id, 0L);
        if (newMinWatermark <= current) return;
        minWatermark.put(id, newMinWatermark);

        var list = byComponent.get(id);
        if (list == null || list.isEmpty()) return;
        // Drop leading entries with tick <= watermark (list is append-ordered).
        int drop = 0;
        while (drop < list.size() && list.get(drop).tick() <= newMinWatermark) {
            drop++;
        }
        if (drop == list.size()) list.clear();
        else if (drop > 0) list.subList(0, drop).clear();
    }

    /** Current minimum watermark for a component, or 0 if untracked. */
    public long minWatermark(ComponentId id) {
        return minWatermark.getOrDefault(id, 0L);
    }

    /** Reset — useful for World.rebuildSchedule to drop stale consumer counts. */
    public void clearConsumers() {
        consumerCount.clear();
        minWatermark.clear();
    }

    /** Drop all stored entries. Consumer registrations are preserved. */
    public void clear() {
        byComponent.clear();
    }
}
