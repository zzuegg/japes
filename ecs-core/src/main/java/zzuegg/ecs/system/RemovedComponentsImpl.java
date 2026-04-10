package zzuegg.ecs.system;

import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.component.ComponentId;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runtime backing for a {@code RemovedComponents<T>} system parameter.
 * Bound to a plan + target component; reads the shared {@link RemovalLog}
 * with the owning plan's watermark whenever the system iterates.
 */
final class RemovedComponentsImpl<T extends Record> implements RemovedComponents<T> {

    private final RemovalLog log;
    private final ComponentId targetId;
    private final SystemExecutionPlan plan;

    RemovedComponentsImpl(RemovalLog log, ComponentId targetId, SystemExecutionPlan plan) {
        this.log = log;
        this.targetId = targetId;
        this.plan = plan;
    }

    ComponentId targetId() { return targetId; }

    @SuppressWarnings("unchecked")
    private List<Removal<T>> snapshot() {
        var raw = log.snapshot(targetId, plan.lastSeenTick());
        if (raw.isEmpty()) return List.of();
        var out = new ArrayList<Removal<T>>(raw.size());
        for (var e : raw) {
            out.add(new Removal<>(e.entity(), (T) e.value()));
        }
        return out;
    }

    @Override
    public Iterator<Removal<T>> iterator() {
        return snapshot().iterator();
    }

    @Override
    public List<Removal<T>> asList() {
        return snapshot();
    }

    @Override
    public boolean isEmpty() {
        return log.snapshot(targetId, plan.lastSeenTick()).isEmpty();
    }
}
