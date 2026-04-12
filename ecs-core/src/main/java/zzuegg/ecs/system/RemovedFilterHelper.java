package zzuegg.ecs.system;

import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helper for the tier-1 {@code @Filter(Removed)} bytecode generator.
 * Called once per tick by the generated {@code run()} method; returns
 * the number of entities to visit and fills the reusable
 * {@code entities} / {@code values} arrays.
 *
 * <p>The generated code then iterates these arrays with inline
 * {@code invokevirtual} to the user method — same architecture as
 * the multi-target Added/Changed tier-1 path.
 */
public final class RemovedFilterHelper {

    private RemovedFilterHelper() {}

    /**
     * Resolve all removal events since {@code lastSeen} across the
     * given target types. Deduplicates per entity. For each entity,
     * resolves {@code @Read} param values: removed components from
     * the log, still-live components from the entity.
     *
     * @param log          the world's removal log
     * @param targetIds    ComponentIds to watch in the log
     * @param lastSeen     system's last-seen tick watermark
     * @param world        for live-entity value lookups
     * @param readCompIds  ComponentId for each @Read param
     * @param readTypes    record Class for each @Read param
     * @param entities     reusable output: entities[0..return-1]
     * @param values       reusable output: values[i * stride + j]
     *                     = resolved @Read param j for entity i
     * @param stride       number of @Read params (= values row width)
     * @return number of unique entities to visit
     */
    @SuppressWarnings("unchecked")
    public static int resolve(
            RemovalLog log,
            ComponentId[] targetIds,
            long lastSeen,
            World world,
            ComponentId[] readCompIds,
            Class<? extends Record>[] readTypes,
            Entity[] entities,
            Object[] values,
            int stride) {

        // Collect removal log entries across all target types, grouped by entity.
        // LinkedHashMap preserves insertion order → stable iteration.
        var perEntity = new LinkedHashMap<Entity, Map<ComponentId, Record>>();
        for (var targetId : targetIds) {
            for (var entry : log.snapshot(targetId, lastSeen)) {
                perEntity.computeIfAbsent(entry.entity(), k -> new HashMap<>())
                    .put(targetId, entry.value());
            }
        }

        int write = 0;
        for (var entry : perEntity.entrySet()) {
            if (write >= entities.length) break; // safety cap
            var entity = entry.getKey();
            var removedValues = entry.getValue();
            entities[write] = entity;

            // Resolve each @Read param.
            for (int r = 0; r < stride; r++) {
                var compId = readCompIds[r];
                var value = removedValues.get(compId);
                if (value == null && world.isAlive(entity)) {
                    value = world.getComponent(entity, readTypes[r]);
                }
                values[write * stride + r] = value;
            }
            write++;
        }
        return write;
    }
}
