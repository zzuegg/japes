package zzuegg.ecs.world;

import zzuegg.ecs.archetype.*;
import zzuegg.ecs.change.Tick;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.*;
import zzuegg.ecs.event.*;
import zzuegg.ecs.executor.Executor;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.resource.*;
import zzuegg.ecs.scheduler.*;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public final class World {

    private final ComponentRegistry componentRegistry = new ComponentRegistry();
    private final ArchetypeGraph archetypeGraph;
    private final zzuegg.ecs.entity.EntityAllocator entityAllocator = new zzuegg.ecs.entity.EntityAllocator();
    private final Map<Integer, EntityLocation> entityLocations = new HashMap<>();
    private final ResourceStore resourceStore = new ResourceStore();
    private final EventRegistry eventRegistry = new EventRegistry();
    private final Executor executor;
    private final Tick tick = new Tick();
    private final Schedule schedule;
    private final Map<String, Local<?>> locals = new HashMap<>();
    private final Map<String, java.util.function.BooleanSupplier> runConditions = new HashMap<>();
    private final Map<String, SystemExecutionPlan> systemPlans = new HashMap<>();
    private final Map<String, ChunkProcessor> chunkProcessors = new HashMap<>();
    private final boolean useGeneratedProcessors;

    World(WorldBuilder builder) {
        this.archetypeGraph = new ArchetypeGraph(componentRegistry, builder.chunkSize, builder.storageFactory);
        this.executor = builder.executor;
        this.useGeneratedProcessors = builder.useGeneratedProcessors;

        for (var resource : builder.resources) {
            resourceStore.insert(resource);
        }

        for (var eventType : builder.eventTypes) {
            eventRegistry.register(eventType);
        }

        var allDescriptors = new ArrayList<SystemDescriptor>();
        for (var clazz : builder.systemClasses) {
            allDescriptors.addAll(SystemParser.parse(clazz, componentRegistry));
            parseRunConditions(clazz);
        }

        this.schedule = new Schedule(allDescriptors, builder.stages);

        for (var entry : schedule.orderedStages()) {
            entry.getValue().buildInvokers();
            for (var node : entry.getValue().nodes()) {
                buildExecutionPlan(node.descriptor());
            }
        }
    }

    private void buildExecutionPlan(SystemDescriptor desc) {
        if (desc.method() == null) return;
        var params = desc.method().getParameters();
        var componentSlots = new ArrayList<SystemExecutionPlan.ParamSlot>();
        var serviceArgIndices = new ArrayList<Integer>();
        int componentIndex = 0;

        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            if (param.isAnnotationPresent(Read.class)) {
                var access = desc.componentAccesses().get(componentIndex++);
                componentSlots.add(new SystemExecutionPlan.ParamSlot(i, access, false, false));
            } else if (param.isAnnotationPresent(Write.class)) {
                var access = desc.componentAccesses().get(componentIndex++);
                var info = componentRegistry.info(access.type());
                componentSlots.add(new SystemExecutionPlan.ParamSlot(i, access, true, info.isValueTracked()));
            } else {
                serviceArgIndices.add(i);
            }
        }

        var plan = new SystemExecutionPlan(params.length, componentSlots, serviceArgIndices, desc.whereFilters());

        // Pre-fill service args (they don't change per entity)
        for (int idx : serviceArgIndices) {
            plan.setServiceArg(idx, resolveServiceParam(desc, params[idx], idx));
        }

        systemPlans.put(desc.name(), plan);

        // Build generated processor if enabled
        if (useGeneratedProcessors && !desc.isExclusive() && !desc.componentAccesses().isEmpty()) {
            var serviceArgsArray = new Object[params.length];
            for (int idx : serviceArgIndices) {
                serviceArgsArray[idx] = resolveServiceParam(desc, params[idx], idx);
            }
            chunkProcessors.put(desc.name(), ChunkProcessorGenerator.generate(desc, serviceArgsArray));
        }
    }

    private void parseRunConditions(Class<?> clazz) {
        Object instance = null;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.getReturnType() == boolean.class && method.getParameterCount() == 0
                    && !method.isAnnotationPresent(zzuegg.ecs.system.System.class)) {
                method.setAccessible(true);
                if (instance == null && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    try {
                        var ctor = clazz.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        instance = ctor.newInstance();
                    } catch (Exception e) {
                        continue;
                    }
                }
                final Object inst = instance;
                runConditions.put(method.getName(), () -> {
                    try {
                        return (boolean) method.invoke(inst);
                    } catch (Exception e) {
                        return false;
                    }
                });
            }
        }
    }

    public static WorldBuilder builder() {
        return new WorldBuilder();
    }

    public zzuegg.ecs.entity.Entity spawn(Record... components) {
        var entity = entityAllocator.allocate();

        var compIds = new HashSet<ComponentId>();
        for (var comp : components) {
            var id = componentRegistry.getOrRegister(comp.getClass());
            var info = componentRegistry.info(comp.getClass());
            if (info.isTableStorage()) {
                compIds.add(id);
            }
        }

        var archetypeId = ArchetypeId.of(compIds);
        var archetype = archetypeGraph.getOrCreate(archetypeId);
        var location = archetype.add(entity);
        entityLocations.put(entity.index(), location);

        for (var comp : components) {
            var info = componentRegistry.info(comp.getClass());
            if (info.isTableStorage()) {
                archetype.set(info.id(), location, comp);
            }
        }

        // Mark added on chunk's change trackers
        var chunk = archetype.chunks().get(location.chunkIndex());
        chunk.markAdded(location.slotIndex(), tick.current());

        return entity;
    }

    public void despawn(zzuegg.ecs.entity.Entity entity) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity is not alive: " + entity);
        }

        var location = entityLocations.remove(entity.index());
        if (location != null) {
            var archetype = archetypeGraph.get(location.archetypeId());
            var swapped = archetype.remove(location);
            swapped.ifPresent(swappedEntity ->
                entityLocations.put(swappedEntity.index(), location)
            );
        }

        entityAllocator.free(entity);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T getComponent(zzuegg.ecs.entity.Entity entity, Class<T> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity is not alive: " + entity);
        }
        var location = entityLocations.get(entity.index());
        if (location == null) {
            throw new IllegalArgumentException("Entity has no location: " + entity);
        }
        var compId = componentRegistry.getOrRegister(type);
        var archetype = archetypeGraph.get(location.archetypeId());
        return archetype.get(compId, location);
    }

    public <T> void setResource(T resource) {
        resourceStore.setDirect(resource.getClass(), resource);
    }

    public void setComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var location = entityLocations.get(entity.index());
        var archetype = archetypeGraph.get(location.archetypeId());
        archetype.set(compId, location, component);
    }

    public void addComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var oldLocation = entityLocations.get(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        var newArchetypeId = archetypeGraph.addEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        for (var existingCompId : oldLocation.archetypeId().components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
        }

        newArchetype.set(compId, newLocation, component);

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            entityLocations.put(swappedEntity.index(), oldLocation)
        );

        entityLocations.put(entity.index(), newLocation);
    }

    public void removeComponent(zzuegg.ecs.entity.Entity entity, Class<? extends Record> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(type);
        var oldLocation = entityLocations.get(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        var newArchetypeId = archetypeGraph.removeEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        for (var existingCompId : newArchetypeId.components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
        }

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            entityLocations.put(swappedEntity.index(), oldLocation)
        );

        entityLocations.put(entity.index(), newLocation);
    }

    public void tick() {
        tick.advance();
        eventRegistry.swapAll();

        for (var entry : schedule.orderedStages()) {
            executeStage(entry.getValue());
        }
    }

    public int entityCount() {
        return entityAllocator.entityCount();
    }

    @SafeVarargs
    public final Snapshot snapshot(Class<? extends Record>... componentTypes) {
        var requiredIds = new HashSet<ComponentId>();
        for (var type : componentTypes) {
            requiredIds.add(componentRegistry.getOrRegister(type));
        }

        var matchingArchetypes = archetypeGraph.findMatching(requiredIds);
        var entries = new ArrayList<Snapshot.SnapshotEntry>();

        for (var archetype : matchingArchetypes) {
            for (var chunk : archetype.chunks()) {
                for (int slot = 0; slot < chunk.count(); slot++) {
                    var entity = chunk.entity(slot);
                    var components = new Record[componentTypes.length];
                    for (int i = 0; i < componentTypes.length; i++) {
                        var compId = componentRegistry.getOrRegister(componentTypes[i]);
                        components[i] = chunk.get(compId, slot);
                    }
                    entries.add(new Snapshot.SnapshotEntry(entity, components));
                }
            }
        }

        return new Snapshot(entries);
    }

    @SafeVarargs
    public final Set<zzuegg.ecs.entity.Entity> findEntities(
            zzuegg.ecs.query.FieldFilter filter, Class<? extends Record>... componentTypes) {
        var requiredIds = new HashSet<ComponentId>();
        var idToType = new HashMap<ComponentId, Class<? extends Record>>();
        for (var type : componentTypes) {
            var id = componentRegistry.getOrRegister(type);
            requiredIds.add(id);
            idToType.put(id, type);
        }

        var matchingArchetypes = archetypeGraph.findMatching(requiredIds);
        var result = new HashSet<zzuegg.ecs.entity.Entity>();

        for (var archetype : matchingArchetypes) {
            for (var chunk : archetype.chunks()) {
                for (int slot = 0; slot < chunk.count(); slot++) {
                    // Build component map for filter evaluation
                    var componentMap = new HashMap<Class<?>, Record>();
                    for (var entry : idToType.entrySet()) {
                        componentMap.put(entry.getValue(), chunk.get(entry.getKey(), slot));
                    }
                    if (filter.test(componentMap)) {
                        result.add(chunk.entity(slot));
                    }
                }
            }
        }

        return result;
    }

    private void executeStage(ScheduleGraph graph) {
        executor.execute(graph, this::executeSystem);
    }

    private void executeSystem(ScheduleGraph.SystemNode node) {
        var desc = node.descriptor();
        var invoker = node.invoker();
        if (invoker == null) return;

        // Check RunIf condition
        if (desc.runIf() != null) {
            var condition = runConditions.get(desc.runIf());
            if (condition != null && !condition.getAsBoolean()) {
                return;
            }
        }

        if (desc.isExclusive()) {
            try {
                invoker.invoke(new Object[]{ this });
            } catch (Throwable e) {
                throw new RuntimeException("Exclusive system failed: " + desc.name(), e);
            }
            return;
        }

        // Collect required component IDs for matching
        var requiredComponents = new HashSet<ComponentId>();
        for (var access : desc.componentAccesses()) {
            requiredComponents.add(access.componentId());
        }
        for (var withType : desc.withFilters()) {
            requiredComponents.add(componentRegistry.getOrRegister(withType));
        }

        if (desc.componentAccesses().isEmpty()) {
            // No component params — invoke once with non-component args
            var args = buildServiceArgs(desc);
            try {
                invoker.invoke(args);
            } catch (Throwable e) {
                throw new RuntimeException("System failed: " + desc.name(), e);
            }
            return;
        }

        var matchingArchetypes = archetypeGraph.findMatching(requiredComponents);

        // Apply @Without filters
        var withoutIds = new HashSet<ComponentId>();
        for (var withoutType : desc.withoutFilters()) {
            withoutIds.add(componentRegistry.getOrRegister(withoutType));
        }

        for (var archetype : matchingArchetypes) {
            boolean skip = false;
            for (var withoutId : withoutIds) {
                if (archetype.id().contains(withoutId)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;

            var processor = chunkProcessors.get(desc.name());
            var currentTick = tick.current();
            if (processor != null) {
                for (var chunk : archetype.chunks()) {
                    processor.process(chunk, currentTick);
                }
            } else {
                var plan = systemPlans.get(desc.name());
                for (var chunk : archetype.chunks()) {
                    plan.processChunk(chunk, invoker, currentTick);
                }
            }
        }
    }

    private Object[] buildEntityArgs(SystemDescriptor desc, Chunk chunk, int slot) {
        var params = desc.method().getParameters();
        var args = new Object[params.length];
        int componentIndex = 0;

        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            var paramType = param.getType();

            if (param.isAnnotationPresent(Read.class)) {
                var access = desc.componentAccesses().get(componentIndex++);
                args[i] = chunk.get(access.componentId(), slot);
            } else if (param.isAnnotationPresent(Write.class)) {
                var access = desc.componentAccesses().get(componentIndex++);
                var value = chunk.get(access.componentId(), slot);
                var info = componentRegistry.info(access.type());
                var tracker = chunk.changeTracker(access.componentId());
                @SuppressWarnings({"unchecked", "rawtypes"})
                var mut = new Mut(value, slot, tracker, tick.current(), info.isValueTracked());
                args[i] = mut;
            } else {
                args[i] = resolveServiceParam(desc, param, i);
            }
        }

        return args;
    }

    private Object[] buildServiceArgs(SystemDescriptor desc) {
        var params = desc.method().getParameters();
        var args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveServiceParam(desc, params[i], i);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private Object resolveServiceParam(SystemDescriptor desc, Parameter param, int paramIndex) {
        var paramType = param.getType();
        if (paramType == Res.class) {
            return resourceStore.get(extractTypeArg(param));
        } else if (paramType == ResMut.class) {
            return resourceStore.getMut(extractTypeArg(param));
        } else if (paramType == Commands.class) {
            return new Commands();
        } else if (paramType == EventWriter.class) {
            var eventType = (Class<? extends Record>) extractTypeArg(param);
            return eventRegistry.store(eventType).writer();
        } else if (paramType == EventReader.class) {
            var eventType = (Class<? extends Record>) extractTypeArg(param);
            return eventRegistry.store(eventType).reader();
        } else if (paramType == Local.class) {
            var key = desc.name() + ":" + paramIndex;
            return locals.computeIfAbsent(key, k -> new Local<>());
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void flushMuts(SystemDescriptor desc, Chunk chunk, int slot, Object[] args) {
        var params = desc.method().getParameters();
        int componentIndex = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].isAnnotationPresent(Read.class)) {
                componentIndex++;
            } else if (params[i].isAnnotationPresent(Write.class)) {
                var access = desc.componentAccesses().get(componentIndex++);
                var mut = (Mut) args[i];
                var newValue = mut.flush();
                chunk.set(access.componentId(), slot, newValue);
            }
        }
    }

    private Class<?> extractTypeArg(Parameter param) {
        var genType = param.getParameterizedType();
        if (genType instanceof ParameterizedType pt) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) {
                return c;
            }
        }
        throw new IllegalArgumentException("Cannot extract type arg from: " + param);
    }
}
