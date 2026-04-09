package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.storage.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-computed execution plan for a system. Avoids per-entity allocation
 * by reusing args array and Mut wrappers.
 */
public final class SystemExecutionPlan {

    public record ParamSlot(int argIndex, ComponentAccess access, boolean isWrite, boolean isValueTracked) {}

    private final Object[] args;
    private final List<ParamSlot> componentSlots;
    private final List<Integer> serviceArgIndices;
    private final Mut<?>[] mutCache;

    public SystemExecutionPlan(int paramCount, List<ParamSlot> componentSlots, List<Integer> serviceArgIndices) {
        this.args = new Object[paramCount];
        this.componentSlots = componentSlots;
        this.serviceArgIndices = serviceArgIndices;
        this.mutCache = new Mut<?>[componentSlots.size()];
    }

    public Object[] args() {
        return args;
    }

    public void setServiceArg(int argIndex, Object value) {
        args[argIndex] = value;
    }

    /**
     * Fill component args for a specific entity slot in a chunk.
     * Reuses the args array — zero allocation for reads, one Mut allocation per write slot (cached).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void fillComponentArgs(Chunk chunk, int slot, long currentTick) {
        for (int i = 0; i < componentSlots.size(); i++) {
            var cs = componentSlots.get(i);
            if (cs.isWrite) {
                var value = chunk.get(cs.access.componentId(), slot);
                var tracker = chunk.changeTracker(cs.access.componentId());
                var mut = new Mut(value, slot, tracker, currentTick, cs.isValueTracked);
                mutCache[i] = mut;
                args[cs.argIndex] = mut;
            } else {
                args[cs.argIndex] = chunk.get(cs.access.componentId(), slot);
            }
        }
    }

    /**
     * Flush Mut values back to chunk after system invocation.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void flushMuts(Chunk chunk) {
        for (int i = 0; i < componentSlots.size(); i++) {
            var cs = componentSlots.get(i);
            if (cs.isWrite) {
                var mut = (Mut) mutCache[i];
                var newValue = mut.flush();
                chunk.set(cs.access.componentId(), mut.slot(), newValue);
            }
        }
    }

    public List<ParamSlot> componentSlots() {
        return componentSlots;
    }
}
