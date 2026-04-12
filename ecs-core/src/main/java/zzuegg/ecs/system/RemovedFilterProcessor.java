package zzuegg.ecs.system;

import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.world.World;

import java.util.*;

/**
 * Dispatch processor for {@code @Filter(Removed, target = ...)} systems.
 * Driven by the removal log instead of archetype/chunk iteration — the
 * entity that lost a component is no longer in a matching archetype, so
 * the normal chunk-walk path can't find it.
 *
 * <p>Per tick, this processor:
 * <ol>
 *   <li>Walks the removal log entries for all target component types
 *       since the system's last-seen tick.</li>
 *   <li>Groups entries by entity (deduplication — a despawned entity
 *       with 3 target components produces 3 log entries but only 1
 *       observer call).</li>
 *   <li>For each unique entity, resolves {@code @Read} param values:
 *       removed components from the log, still-live components from
 *       the entity (for component-strip cases where the entity is
 *       still alive).</li>
 *   <li>Calls the user method via {@link SystemInvoker}.</li>
 * </ol>
 */
public final class RemovedFilterProcessor {

    private final SystemDescriptor desc;
    private final SystemExecutionPlan plan;
    private final RemovalLog log;
    private final ComponentId[] targetIds;
    private final World world;
    private final Object[] serviceArgs;

    // Per @Read param: which ComponentId it reads, and where to
    // find its value (removal log or live entity).
    private final int[] readParamIndices;     // indices into the method params
    private final ComponentId[] readCompIds;  // ComponentId for each @Read param
    private final Class<? extends Record>[] readTypes; // record class for each
    private final SystemInvoker invoker;
    private final int entityParamIdx;

    @SuppressWarnings("unchecked")
    public RemovedFilterProcessor(SystemDescriptor desc, SystemExecutionPlan plan,
                                   RemovalLog log, ComponentId[] targetIds,
                                   World world, Object[] serviceArgs,
                                   zzuegg.ecs.component.ComponentRegistry registry) {
        this.desc = desc;
        this.plan = plan;
        this.log = log;
        this.targetIds = targetIds;
        this.world = world;
        this.serviceArgs = serviceArgs;

        // Build the @Read param mapping.
        var accesses = desc.componentAccesses();
        var readIdxs = new ArrayList<Integer>();
        var readCids = new ArrayList<ComponentId>();
        var readCls = new ArrayList<Class<? extends Record>>();

        var method = desc.method();
        var params = method.getParameters();
        int accessIdx = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                var access = accesses.get(accessIdx++);
                readIdxs.add(i);
                readCids.add(access.componentId());
                readCls.add(access.type());
            } else if (params[i].isAnnotationPresent(Write.class)) {
                accessIdx++; // skip write params (shouldn't exist on @Filter(Removed))
            }
        }
        this.readParamIndices = readIdxs.stream().mapToInt(Integer::intValue).toArray();
        this.readCompIds = readCids.toArray(ComponentId[]::new);
        this.readTypes = readCls.toArray(new Class[0]);
        this.invoker = SystemInvoker.create(desc);

        // Find Entity param index once.
        int eIdx = -1;
        var mParams = desc.method().getParameters();
        for (int i = 0; i < mParams.length; i++) {
            if (mParams[i].getType() == Entity.class) { eIdx = i; break; }
        }
        this.entityParamIdx = eIdx;
    }

    public void run(long currentTick) {
        long since = plan.lastSeenTick();

        // Collect removal log entries across all target types, grouped by entity.
        var perEntity = new LinkedHashMap<Entity, Map<ComponentId, Record>>();
        for (var targetId : targetIds) {
            for (var entry : log.snapshot(targetId, since)) {
                perEntity.computeIfAbsent(entry.entity(), k -> new HashMap<>())
                    .put(targetId, entry.value());
            }
        }

        if (perEntity.isEmpty()) return;

        // Build the args array for each entity and call the user method.
        // Use the plan's pre-resolved args as a template (service params
        // are already in place); clone once per run so we can mutate slots.
        var args = plan.args().clone();

        for (var entry : perEntity.entrySet()) {
            var entity = entry.getKey();
            var removedValues = entry.getValue();

            // Bind Entity param.
            if (entityParamIdx >= 0) {
                args[entityParamIdx] = entity;
            }

            // Bind @Read params: try removal log first, then live entity.
            for (int r = 0; r < readParamIndices.length; r++) {
                var compId = readCompIds[r];
                var value = removedValues.get(compId);
                if (value == null && world.isAlive(entity)) {
                    // Component wasn't removed — read from live entity.
                    value = world.getComponent(entity, readTypes[r]);
                }
                args[readParamIndices[r]] = value;
            }

            try {
                invoker.invoke(args);
            } catch (Throwable e) {
                throw new RuntimeException(
                    "@Filter(Removed) system failed: " + desc.name(), e);
            }
        }
    }
}
