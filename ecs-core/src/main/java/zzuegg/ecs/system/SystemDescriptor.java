package zzuegg.ecs.system;

import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.query.FieldFilter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SystemDescriptor(
    String name,
    String stage,
    Set<String> after,
    Set<String> before,
    boolean isExclusive,
    List<ComponentAccess> componentAccesses,
    Map<Integer, FieldFilter> whereFilters,
    Set<Class<?>> resourceReads,
    Set<Class<?>> resourceWrites,
    Set<Class<?>> eventReads,
    Set<Class<?>> eventWrites,
    Set<Class<? extends Record>> withFilters,
    Set<Class<? extends Record>> withoutFilters,
    List<FilterDescriptor> changeFilters,
    Set<Class<? extends Record>> removedReads,
    // Relation types named by @Pair annotations on this method, paired
    // with the role (SOURCE / TARGET / EITHER) the annotation requested.
    // Plan build walks these to decide which archetype marker to add
    // to the required-components set: SOURCE → relation store's source
    // marker, TARGET → target marker, EITHER → no marker added (the
    // annotation is informational only).
    List<PairRead> pairReads,
    // Relation types consumed via RemovedRelations<T> parameters. Used by
    // the world's end-of-tick GC pass to compute the minimum watermark
    // across plans for each store's removal log.
    Set<Class<? extends Record>> removedRelationReads,
    // Set by {@code @ForEachPair(T.class)}: the relation type that
    // drives per-pair iteration instead of per-entity. {@code null}
    // for regular systems. Mutually exclusive with a non-empty
    // {@code pairReads} set.
    Class<? extends Record> pairIterationType,
    // Parameter index that receives the pair's payload value (the
    // {@code T} instance matching the annotation's value). {@code -1}
    // for regular (non-pair-iteration) systems.
    int pairValueParamSlot,
    // Parameter indices of {@code @FromTarget Entity} params in a
    // {@code @ForEachPair} system. The non-target {@code Entity}
    // params stay in {@link #entityParamSlots}. Empty set for
    // regular systems.
    Set<Integer> targetEntityParamSlots,
    // Parameter indices of Entity arguments — filled per-iteration with
    // chunk.entity(slot). Collected at parse time so the execution plan can
    // populate them without re-scanning the method signature.
    Set<Integer> entityParamSlots,
    boolean usesCommands,
    boolean usesLocal,
    String runIf,
    Method method,
    Object instance
) {

    public record FilterDescriptor(Class<?> filterType, Class<? extends Record> target) {}

    /**
     * One {@code @Pair} annotation parse result — the relation type
     * plus the role the annotation requested. Kept as a separate
     * record (not just a pair of collections) so the plan-build code
     * can walk them in declaration order without relying on set
     * ordering.
     */
    public record PairRead(
        Class<? extends Record> type,
        zzuegg.ecs.system.Pair.Role role
    ) {}
}
