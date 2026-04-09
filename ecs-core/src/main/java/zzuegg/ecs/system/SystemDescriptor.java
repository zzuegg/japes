package zzuegg.ecs.system;

import zzuegg.ecs.query.ComponentAccess;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

public record SystemDescriptor(
    String name,
    String stage,
    Set<String> after,
    Set<String> before,
    boolean isExclusive,
    List<ComponentAccess> componentAccesses,
    Set<Class<?>> resourceReads,
    Set<Class<?>> resourceWrites,
    Set<Class<?>> eventReads,
    Set<Class<?>> eventWrites,
    Set<Class<? extends Record>> withFilters,
    Set<Class<? extends Record>> withoutFilters,
    List<FilterDescriptor> changeFilters,
    boolean usesCommands,
    boolean usesLocal,
    String runIf,
    Method method,
    Object instance
) {

    public record FilterDescriptor(Class<?> filterType, Class<? extends Record> target) {}
}
