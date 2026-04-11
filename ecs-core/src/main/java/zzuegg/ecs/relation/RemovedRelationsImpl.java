package zzuegg.ecs.relation;

import zzuegg.ecs.system.SystemExecutionPlan;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runtime backing for a {@code RemovedRelations<T>} system parameter.
 * Bound to a {@link PairRemovalLog} + an owning plan; each iteration
 * reads the log with {@code plan.lastSeenTick()} as the exclusive
 * lower bound so consecutive ticks see disjoint removal windows.
 */
final class RemovedRelationsImpl<T extends Record> implements RemovedRelations<T> {

    private final PairRemovalLog log;
    private final SystemExecutionPlan plan;

    RemovedRelationsImpl(PairRemovalLog log, SystemExecutionPlan plan) {
        this.log = log;
        this.plan = plan;
    }

    @SuppressWarnings("unchecked")
    private List<Removal<T>> snapshot() {
        var raw = log.snapshot(plan.lastSeenTick());
        if (raw.isEmpty()) return List.of();
        var out = new ArrayList<Removal<T>>(raw.size());
        for (var e : raw) {
            out.add(new Removal<>(e.source(), e.target(), (T) e.value()));
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
        return log.snapshot(plan.lastSeenTick()).isEmpty();
    }
}
