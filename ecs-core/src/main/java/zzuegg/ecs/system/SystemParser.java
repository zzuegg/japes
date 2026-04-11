package zzuegg.ecs.system;

import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.event.EventReader;
import zzuegg.ecs.event.EventWriter;
import zzuegg.ecs.relation.PairReader;
import zzuegg.ecs.relation.RemovedRelations;
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
            var removedRelationReads = new HashSet<Class<? extends Record>>();
            var entityParamSlots = new HashSet<Integer>();
            // Parallel to entityParamSlots but only populated for
            // @ForEachPair systems. Source-side Entity params go in
            // entityParamSlots (the default); @FromTarget Entity
            // params go here so the pair-iteration dispatch knows
            // which param to fill with target.
            var targetEntityParamSlots = new HashSet<Integer>();
            // Slot index for the relation payload parameter. Stays
            // -1 for non-@ForEachPair systems.
            int pairValueParamSlot = -1;
            boolean usesCommands = false;
            boolean usesLocal = false;

            // Read @ForEachPair up front so the parameter loop below
            // can match the payload parameter by type and route
            // @FromTarget Entity params to the target slot set.
            var forEachPair = method.getAnnotation(ForEachPair.class);
            final Class<? extends Record> forEachPairType = forEachPair != null ? forEachPair.value() : null;

            var methodParams = method.getParameters();
            for (int pIdx = 0; pIdx < methodParams.length; pIdx++) {
                var param = methodParams[pIdx];
                var paramType = param.getType();

                if (paramType == World.class) continue;
                if (paramType == zzuegg.ecs.entity.Entity.class) {
                    // Per-iteration current-entity handle. Filled by
                    // SystemExecutionPlan.processChunk from chunk.entity(slot).
                    // For @ForEachPair systems, @FromTarget Entity goes
                    // into a separate slot set — the source side is the
                    // default.
                    if (forEachPairType != null && param.isAnnotationPresent(FromTarget.class)) {
                        targetEntityParamSlots.add(pIdx);
                    } else {
                        entityParamSlots.add(pIdx);
                    }
                    continue;
                }
                // For @ForEachPair systems, a parameter whose type
                // equals the driving relation type IS the payload —
                // bound per-pair from {@code slice.values[i]} by the
                // dispatch path, not resolved as a service.
                if (forEachPairType != null && paramType == forEachPairType) {
                    if (pairValueParamSlot != -1) {
                        throw new IllegalArgumentException(
                            "System '" + clazz.getSimpleName() + "." + method.getName()
                                + "' declares multiple " + forEachPairType.getSimpleName()
                                + " parameters — only one pair payload slot is allowed.");
                    }
                    pairValueParamSlot = pIdx;
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
                if (paramType == PairReader.class) {
                    // PairReader<T> is a world-scoped service; the system
                    // body invokes .fromSource(self)/.withTarget(self). We
                    // register the store here so World.resolveServiceParam
                    // can look it up without a null check. @Pair on the
                    // same method is what narrows the archetype filter —
                    // PairReader alone does not imply a marker requirement.
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) extractTypeArg(param);
                    registry.registerRelation(recType);
                    continue;
                }
                if (paramType == zzuegg.ecs.component.ComponentReader.class) {
                    // ComponentReader<T> is a pre-resolved fast
                    // cross-entity lookup. Register the target type as
                    // a regular component so the World resolver can
                    // look up its ComponentId without an extra hash
                    // lookup on the hot path.
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) extractTypeArg(param);
                    registry.getOrRegister(recType);
                    continue;
                }
                if (paramType == RemovedRelations.class) {
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) extractTypeArg(param);
                    removedRelationReads.add(recType);
                    registry.registerRelation(recType);
                    continue;
                }

                if (param.isAnnotationPresent(Read.class)) {
                    var compId = registry.getOrRegister(paramType);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) paramType;
                    boolean fromTarget = param.isAnnotationPresent(FromTarget.class);
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.READ, fromTarget));
                } else if (param.isAnnotationPresent(Write.class)) {
                    var typeArg = extractTypeArg(param);
                    var compId = registry.getOrRegister(typeArg);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) typeArg;
                    boolean fromTarget = param.isAnnotationPresent(FromTarget.class);
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.WRITE, fromTarget));
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

            var pairReads = new ArrayList<SystemDescriptor.PairRead>();
            for (var p : method.getAnnotationsByType(Pair.class)) {
                pairReads.add(new SystemDescriptor.PairRead(p.value(), p.role()));
                // Ensure the relation type has a store — registering here means
                // the plan-build step can safely look up the marker id.
                registry.registerRelation(p.value());
            }

            // @ForEachPair validation: reject conflict with @Pair and
            // @FromTarget @Write. The annotation type itself was
            // already read into forEachPairType above so the
            // parameter loop could classify payload slots.
            Class<? extends Record> pairIterationType = null;
            if (forEachPairType != null) {
                if (!pairReads.isEmpty()) {
                    throw new IllegalArgumentException(
                        "System '" + clazz.getSimpleName() + "." + method.getName()
                            + "' has both @Pair and @ForEachPair — pick one. "
                            + "@Pair drives per-entity iteration, @ForEachPair "
                            + "drives per-pair iteration; mixing them is ambiguous.");
                }
                pairIterationType = forEachPairType;
                registry.registerRelation(forEachPairType);
                // Per-pair target-side writes are forbidden in v1.
                for (var p : methodParams) {
                    if (p.isAnnotationPresent(FromTarget.class)
                            && p.isAnnotationPresent(Write.class)) {
                        throw new IllegalArgumentException(
                            "System '" + clazz.getSimpleName() + "." + method.getName()
                                + "' uses @FromTarget @Write which is forbidden "
                                + "in v1 @ForEachPair systems — target-side write "
                                + "conflicts have ambiguous semantics. Use @Read "
                                + "on target components, or mutate via Commands.");
                    }
                }
            }

            // Parse @Where filters on component parameters (supports multiple per param)
            var whereFilters = new java.util.HashMap<Integer, zzuegg.ecs.query.FieldFilter>();
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
                changeFilters, removedReads, pairReads, removedRelationReads,
                pairIterationType, pairValueParamSlot, targetEntityParamSlots,
                entityParamSlots,
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
