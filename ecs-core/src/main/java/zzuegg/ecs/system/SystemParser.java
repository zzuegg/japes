package zzuegg.ecs.system;

import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.event.EventReader;
import zzuegg.ecs.event.EventWriter;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.world.World;

import java.lang.reflect.*;
import java.util.*;

public final class SystemParser {

    private SystemParser() {}

    public static List<SystemDescriptor> parse(Object instance, ComponentRegistry registry) {
        return parse(instance.getClass(), registry, instance);
    }

    public static List<SystemDescriptor> parse(Class<?> clazz, ComponentRegistry registry) {
        return parse(clazz, registry, null);
    }

    private static List<SystemDescriptor> parse(Class<?> clazz, ComponentRegistry registry, Object providedInstance) {
        var results = new ArrayList<SystemDescriptor>();

        var setAnnotation = clazz.getAnnotation(SystemSet.class);
        String setStage = setAnnotation != null ? setAnnotation.stage() : null;
        Set<String> setAfter = setAnnotation != null ? Set.of(setAnnotation.after()) : Set.of();
        Set<String> setBefore = setAnnotation != null ? Set.of(setAnnotation.before()) : Set.of();

        Object instance = providedInstance;
        if (instance == null) {
            for (var method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(zzuegg.ecs.system.System.class)) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        try {
                            var ctor = clazz.getDeclaredConstructor();
                            ctor.setAccessible(true);
                            instance = ctor.newInstance();
                        } catch (Exception e) {
                            throw new RuntimeException("Cannot instantiate system class: " + clazz.getName(), e);
                        }
                        break;
                    }
                }
            }
        }

        for (var method : clazz.getDeclaredMethods()) {
            var sysAnnotation = method.getAnnotation(zzuegg.ecs.system.System.class);
            if (sysAnnotation == null) continue;

            method.setAccessible(true);

            // Stage resolution: if the method didn't specify an explicit stage
            // (the "" sentinel), inherit from the enclosing SystemSet or fall
            // back to "Update". A non-empty method stage always wins — even if
            // it happens to equal the set's stage.
            String stage = sysAnnotation.stage();
            if (stage.isEmpty()) {
                stage = setStage != null ? setStage : "Update";
            }

            var after = new HashSet<>(setAfter);
            after.addAll(Set.of(sysAnnotation.after()));
            var before = new HashSet<>(setBefore);
            before.addAll(Set.of(sysAnnotation.before()));

            boolean exclusive = method.isAnnotationPresent(Exclusive.class);

            var componentAccesses = new ArrayList<ComponentAccess>();
            var resourceReads = new HashSet<Class<?>>();
            var resourceWrites = new HashSet<Class<?>>();
            var eventReads = new HashSet<Class<?>>();
            var eventWrites = new HashSet<Class<?>>();
            var removedReads = new HashSet<Class<? extends Record>>();
            var entityParamSlots = new HashSet<Integer>();
            boolean usesCommands = false;
            boolean usesLocal = false;

            var methodParams = method.getParameters();
            for (int pIdx = 0; pIdx < methodParams.length; pIdx++) {
                var param = methodParams[pIdx];
                var paramType = param.getType();

                if (paramType == World.class) continue;
                if (paramType == zzuegg.ecs.entity.Entity.class) {
                    // Per-iteration current-entity handle. Filled by
                    // SystemExecutionPlan.processChunk from chunk.entity(slot).
                    entityParamSlots.add(pIdx);
                    continue;
                }
                if (paramType == Commands.class) { usesCommands = true; continue; }
                if (paramType == Local.class) { usesLocal = true; continue; }

                if (paramType == Res.class) {
                    resourceReads.add(extractTypeArg(param));
                    continue;
                }
                if (paramType == ResMut.class) {
                    resourceWrites.add(extractTypeArg(param));
                    continue;
                }
                if (paramType == EventReader.class) {
                    eventReads.add(extractTypeArg(param));
                    continue;
                }
                if (paramType == EventWriter.class) {
                    eventWrites.add(extractTypeArg(param));
                    continue;
                }
                if (paramType == RemovedComponents.class) {
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) extractTypeArg(param);
                    removedReads.add(recType);
                    registry.getOrRegister(recType);
                    continue;
                }

                if (param.isAnnotationPresent(Read.class)) {
                    var compId = registry.getOrRegister(paramType);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) paramType;
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.READ));
                } else if (param.isAnnotationPresent(Write.class)) {
                    var typeArg = extractTypeArg(param);
                    var compId = registry.getOrRegister(typeArg);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) typeArg;
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.WRITE));
                }
            }

            var withFilters = new HashSet<Class<? extends Record>>();
            var withoutFilters = new HashSet<Class<? extends Record>>();
            var changeFilters = new ArrayList<SystemDescriptor.FilterDescriptor>();

            for (var w : method.getAnnotationsByType(With.class)) {
                withFilters.add(w.value());
            }
            for (var w : method.getAnnotationsByType(Without.class)) {
                withoutFilters.add(w.value());
            }
            for (var f : method.getAnnotationsByType(Filter.class)) {
                changeFilters.add(new SystemDescriptor.FilterDescriptor(f.value(), f.target()));
            }

            // Parse @Where filters on component parameters (supports multiple per param)
            var whereFilters = new java.util.HashMap<Integer, zzuegg.ecs.query.FieldFilter>();
            int compParamIdx = 0;
            for (int i = 0; i < method.getParameters().length; i++) {
                var p = method.getParameters()[i];
                if (p.isAnnotationPresent(Read.class) || p.isAnnotationPresent(Write.class)) {
                    var wheres = p.getAnnotationsByType(Where.class);
                    if (wheres.length > 0) {
                        Class<? extends Record> compType;
                        if (p.isAnnotationPresent(Read.class)) {
                            @SuppressWarnings("unchecked")
                            var t = (Class<? extends Record>) p.getType();
                            compType = t;
                        } else {
                            @SuppressWarnings("unchecked")
                            var t = (Class<? extends Record>) extractTypeArg(p);
                            compType = t;
                        }
                        if (wheres.length == 1) {
                            whereFilters.put(i, zzuegg.ecs.query.FieldFilter.parse(wheres[0].value(), compType));
                        } else {
                            var filters = new zzuegg.ecs.query.FieldFilter[wheres.length];
                            for (int w = 0; w < wheres.length; w++) {
                                filters[w] = zzuegg.ecs.query.FieldFilter.parse(wheres[w].value(), compType);
                            }
                            whereFilters.put(i, zzuegg.ecs.query.FieldFilter.and(filters));
                        }
                    }
                    compParamIdx++;
                }
            }

            var runIfAnnotation = method.getAnnotation(RunIf.class);
            String runIf = runIfAnnotation != null ? runIfAnnotation.value() : null;

            var qualifiedName = clazz.getSimpleName() + "." + method.getName();

            results.add(new SystemDescriptor(
                qualifiedName, stage, after, before, exclusive,
                componentAccesses, whereFilters,
                resourceReads, resourceWrites,
                eventReads, eventWrites, withFilters, withoutFilters,
                changeFilters, removedReads, entityParamSlots,
                usesCommands, usesLocal, runIf,
                method, Modifier.isStatic(method.getModifiers()) ? null : instance
            ));
        }

        return results;
    }

    private static Class<?> extractTypeArg(Parameter param) {
        var genType = param.getParameterizedType();
        if (genType instanceof ParameterizedType pt) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) {
                return c;
            }
        }
        throw new IllegalArgumentException(
            "Cannot extract type argument from parameter: " + param.getName());
    }
}
