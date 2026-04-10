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
}
