package zzuegg.ecs.world;

import zzuegg.ecs.archetype.*;
import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.change.Tick;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.*;
import zzuegg.ecs.event.*;
import zzuegg.ecs.executor.Executor;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.resource.*;
import zzuegg.ecs.scheduler.*;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public final class World {

    private final ComponentRegistry componentRegistry = new ComponentRegistry();
    private final ArchetypeGraph archetypeGraph;
    private final zzuegg.ecs.entity.EntityAllocator entityAllocator = new zzuegg.ecs.entity.EntityAllocator();
    // Index-keyed (not boxed). EntityAllocator hands out compact indices with
    // a free-list, so an ArrayList sized to the high-water mark is strictly
    // cheaper than HashMap<Integer, EntityLocation>: zero boxing, zero hash,
    // O(1) get/set, and good cache locality on iteration.
    private final ArrayList<EntityLocation> entityLocations = new ArrayList<>();
    private final ResourceStore resourceStore = new ResourceStore();
    private final EventRegistry eventRegistry = new EventRegistry();
    private final Executor executor;
    private final Tick tick = new Tick();
    private Schedule schedule;
    private final Map<String, Stage> stages;
    private final List<SystemDescriptor> allDescriptors = new ArrayList<>();
    private final Map<String, Local<?>> locals = new HashMap<>();
    private final Map<String, java.util.function.BooleanSupplier> runConditions = new HashMap<>();
    private final Map<String, SystemExecutionPlan> systemPlans = new HashMap<>();
    private final Map<String, ChunkProcessor> chunkProcessors = new HashMap<>();
    private final Set<String> disabledSystems = new HashSet<>();
    private final List<Commands> allCommandBuffers = new ArrayList<>();
    private final RemovalLog removalLog = new RemovalLog();
    // Components observed via RemovedComponents<T> in any plan. GC walks only
    // these each tick.
    private final Set<ComponentId> trackedRemovedComponents = new HashSet<>();
    private final boolean useGeneratedProcessors;

    World(WorldBuilder builder) {
        this.archetypeGraph = new ArchetypeGraph(componentRegistry, builder.chunkSize, builder.storageFactory);
        this.executor = builder.executor;
        this.useGeneratedProcessors = builder.useGeneratedProcessors;
        this.stages = new HashMap<>(builder.stages);

        for (var resource : builder.resources) {
            resourceStore.insert(resource);
        }

        for (var eventType : builder.eventTypes) {
            eventRegistry.register(eventType);
        }

        for (var clazz : builder.systemClasses) {
            allDescriptors.addAll(SystemParser.parse(clazz, componentRegistry));
            parseRunConditions(clazz);
        }

        for (var instance : builder.systemInstances) {
            allDescriptors.addAll(SystemParser.parse(instance, componentRegistry));
            parseRunConditions(instance.getClass());
        }

        rebuildSchedule();
    }

    private void rebuildSchedule() {
        systemPlans.clear();
        chunkProcessors.clear();
        // Every rebuild re-resolves service args, which allocates fresh Commands
        // buffers. Drop the old ones so the list doesn't grow unbounded.
        allCommandBuffers.clear();
        // Removal consumers must be re-registered by each plan below.
        removalLog.clearConsumers();
        trackedRemovedComponents.clear();
        this.schedule = new Schedule(allDescriptors, stages);

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
            } else if (desc.entityParamSlots().contains(i)) {
                // Entity parameters are filled per-iteration from chunk.entity(slot);
                // they are NOT service args (one-shot resolution) and NOT component
                // accesses (no tracker, no storage).
            } else {
                serviceArgIndices.add(i);
            }
        }

        // Resolve change filters (@Filter(Added/Changed, target = X)) into
        // (ComponentId, kind) pairs the execution plan can consult directly.
        var resolvedChangeFilters = new ArrayList<SystemExecutionPlan.ResolvedChangeFilter>();
        for (var f : desc.changeFilters()) {
            var kind = f.filterType() == Added.class ? SystemExecutionPlan.FilterKind.ADDED
                     : f.filterType() == Changed.class ? SystemExecutionPlan.FilterKind.CHANGED
                     : null;
            if (kind == null) continue;  // Removed is unsupported for now
            var targetId = componentRegistry.getOrRegister(f.target());
            resolvedChangeFilters.add(new SystemExecutionPlan.ResolvedChangeFilter(targetId, kind));
        }

        var plan = new SystemExecutionPlan(params.length, componentSlots, serviceArgIndices,
            desc.whereFilters(), resolvedChangeFilters);

        // Pre-compute the query sets once; executeSystem used to rebuild them
        // on every tick per system.
        var required = new HashSet<ComponentId>();
        for (var access : desc.componentAccesses()) {
            required.add(access.componentId());
        }
        for (var withType : desc.withFilters()) {
            required.add(componentRegistry.getOrRegister(withType));
        }
        var without = new HashSet<ComponentId>();
        for (var withoutType : desc.withoutFilters()) {
            without.add(componentRegistry.getOrRegister(withoutType));
        }
        plan.setQuerySets(Set.copyOf(required), Set.copyOf(without));
        plan.setConsumedRemovedComponents(Set.copyOf(desc.removedReads()));

        // Entity injection: flatten the descriptor's entity param slots into
        // an int[] the plan can loop over per iteration.
        var entityIdx = new int[desc.entityParamSlots().size()];
        int ei = 0;
        for (int slot : desc.entityParamSlots()) entityIdx[ei++] = slot;
        plan.setEntitySlotIndices(entityIdx);

        // Resolve service args once per system so the generated-processor path and the
        // SystemExecutionPlan path observe the same Commands/EventWriter/Local instances.
        // Duplicating the call previously leaked a second Commands into allCommandBuffers
        // that could silently swallow user commands when the unused path happened to be
        // the one exposed to the system at runtime.
        var resolvedServiceArgs = new Object[params.length];
        for (int idx : serviceArgIndices) {
            var p = params[idx];
            Object arg;
            if (p.getType() == RemovedComponents.class) {
                // RemovedComponents<T> needs the owning plan to read its watermark,
                // so construct it here where the plan is in scope rather than
                // through the generic resolveServiceParam helper.
                @SuppressWarnings("unchecked")
                var recType = (Class<? extends Record>) extractTypeArg(p);
                var compId = componentRegistry.getOrRegister(recType);
                removalLog.registerConsumer(compId);
                trackedRemovedComponents.add(compId);
                arg = RemovedComponents.bind(removalLog, compId, plan);
            } else {
                arg = resolveServiceParam(desc, p, idx);
            }
            resolvedServiceArgs[idx] = arg;
            plan.setServiceArg(idx, arg);
        }

        systemPlans.put(desc.name(), plan);

        // Build generated processor if enabled. Systems with change filters or
        // Entity-injected parameters must go through the SystemExecutionPlan
        // path — the generated processors don't know how to consult per-target
        // ChangeTrackers or how to fill the current iteration entity.
        if (useGeneratedProcessors && !desc.isExclusive() && !desc.componentAccesses().isEmpty()
                && desc.changeFilters().isEmpty()
                && desc.entityParamSlots().isEmpty()) {
            chunkProcessors.put(desc.name(), ChunkProcessorGenerator.generate(desc, resolvedServiceArgs));
        }
    }

    private void parseRunConditions(Class<?> clazz) {
        Object instance = null;
        for (var method : clazz.getDeclaredMethods()) {
            // Only register methods explicitly opted in via @RunCondition. Previously
            // any no-arg boolean method was registered, so unrelated helpers like
            // isInitialized() polluted the global run-condition namespace and could
            // shadow a real condition defined elsewhere.
            var rcAnnotation = method.getAnnotation(RunCondition.class);
            if (rcAnnotation == null) continue;
            if (method.getReturnType() != boolean.class || method.getParameterCount() != 0) {
                throw new IllegalStateException(
                    "@RunCondition methods must be no-arg and return boolean: " + method);
            }
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
            var name = rcAnnotation.value().isEmpty() ? method.getName() : rcAnnotation.value();
            runConditions.put(name, () -> {
                try {
                    return (boolean) method.invoke(inst);
                } catch (Exception e) {
                    return false;
                }
            });
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
        setLocation(entity.index(), location);

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

        var location = removeLocation(entity.index());
        // isAlive already passed: a missing location would be an invariant
        // violation (entity in allocator but not in the location map). Fail
        // loudly rather than silently leaking the allocation.
        if (location == null) {
            throw new IllegalStateException(
                "Invariant violation: alive entity has no location: " + entity);
        }
        var archetype = archetypeGraph.get(location.archetypeId());

        // Log every component the entity is about to lose so RemovedComponents<T>
        // consumers can see them. Done before archetype.remove() so the values
        // are still readable.
        var chunk = archetype.chunks().get(location.chunkIndex());
        int slot = location.slotIndex();
        for (var compId : location.archetypeId().components()) {
            if (trackedRemovedComponents.contains(compId)) {
                removalLog.append(compId, entity, (Record) chunk.get(compId, slot), tick.current());
            }
        }

        var swapped = archetype.remove(location);
        swapped.ifPresent(swappedEntity ->
            setLocation(swappedEntity.index(), location)
        );

        entityAllocator.free(entity);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T getComponent(zzuegg.ecs.entity.Entity entity, Class<T> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity is not alive: " + entity);
        }
        var location = getLocation(entity.index());
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
        var info = componentRegistry.info(component.getClass());
        var location = getLocation(entity.index());
        var archetype = archetypeGraph.get(location.archetypeId());

        // For @ValueTracked components, skip the change-detection bump when the
        // new value equals the existing one — matches Mut.flush()'s semantics so
        // both mutation paths behave the same way.
        boolean fireChanged = true;
        if (info.isValueTracked()) {
            var existing = archetype.<Record>get(compId, location);
            if (existing != null && existing.equals(component)) {
                fireChanged = false;
            }
        }

        archetype.set(compId, location, component);

        if (fireChanged) {
            var chunk = archetype.chunks().get(location.chunkIndex());
            chunk.changeTracker(compId).markChanged(location.slotIndex(), tick.current());
        }
    }

    public void addComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var oldLocation = getLocation(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        var newArchetypeId = archetypeGraph.addEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        var oldChunk = oldArchetype.chunks().get(oldLocation.chunkIndex());
        var newChunk = newArchetype.chunks().get(newLocation.chunkIndex());
        int oldSlot = oldLocation.slotIndex();
        int newSlot = newLocation.slotIndex();

        for (var existingCompId : oldLocation.archetypeId().components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
            // Preserve added/changed history across the archetype migration.
            var src = oldChunk.changeTracker(existingCompId);
            var dst = newChunk.changeTracker(existingCompId);
            dst.markAdded(newSlot, src.addedTick(oldSlot));
            dst.markChanged(newSlot, src.changedTick(oldSlot));
        }

        newArchetype.set(compId, newLocation, component);
        // The newly added component is "added at the current tick" so @Added filters fire.
        newChunk.changeTracker(compId).markAdded(newSlot, tick.current());

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            setLocation(swappedEntity.index(), oldLocation)
        );

        setLocation(entity.index(), newLocation);
    }

    public void removeComponent(zzuegg.ecs.entity.Entity entity, Class<? extends Record> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(type);
        var oldLocation = getLocation(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        // Log the removal BEFORE migration, so the last-known value is still
        // reachable in the source chunk for RemovedComponents<T> consumers.
        if (trackedRemovedComponents.contains(compId)) {
            var oldChunkEarly = oldArchetype.chunks().get(oldLocation.chunkIndex());
            removalLog.append(compId, entity,
                (Record) oldChunkEarly.get(compId, oldLocation.slotIndex()), tick.current());
        }

        var newArchetypeId = archetypeGraph.removeEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        var oldChunk = oldArchetype.chunks().get(oldLocation.chunkIndex());
        var newChunk = newArchetype.chunks().get(newLocation.chunkIndex());
        int oldSlot = oldLocation.slotIndex();
        int newSlot = newLocation.slotIndex();

        for (var existingCompId : newArchetypeId.components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
            // Preserve added/changed history for components that survive the move.
            var src = oldChunk.changeTracker(existingCompId);
            var dst = newChunk.changeTracker(existingCompId);
            dst.markAdded(newSlot, src.addedTick(oldSlot));
            dst.markChanged(newSlot, src.changedTick(oldSlot));
        }

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            setLocation(swappedEntity.index(), oldLocation)
        );

        setLocation(entity.index(), newLocation);
    }

    public void tick() {
        tick.advance();
        eventRegistry.swapAll();

        for (var entry : schedule.orderedStages()) {
            executeStage(entry.getValue());
        }

        // End-of-tick GC for the removal log: for each tracked component, drop
        // every entry whose tick is ≤ the minimum watermark across the plans
        // that consume it. Plans that never ran this tick (disabled / skipped
        // by RunIf) still hold references via their older watermark, so their
        // entries survive until they catch up.
        if (!trackedRemovedComponents.isEmpty()) {
            for (var compId : trackedRemovedComponents) {
                long minWatermark = Long.MAX_VALUE;
                for (var p : systemPlans.values()) {
                    // Only plans that actually consume this component count.
                    // Descriptors carry the Class, not the ComponentId, so
                    // we re-resolve here — cheap lookups in componentRegistry.
                    boolean consumes = false;
                    for (var type : p.consumedRemovedComponents()) {
                        if (componentRegistry.getOrRegister(type).equals(compId)) {
                            consumes = true;
                            break;
                        }
                    }
                    if (consumes && p.lastSeenTick() < minWatermark) {
                        minWatermark = p.lastSeenTick();
                    }
                }
                if (minWatermark != Long.MAX_VALUE) {
                    removalLog.collectGarbage(compId, minWatermark);
                }
            }
        }
    }

    public int entityCount() {
        return entityAllocator.entityCount();
    }

    public boolean isAlive(zzuegg.ecs.entity.Entity entity) {
        return entityAllocator.isAlive(entity);
    }

    private void setLocation(int index, EntityLocation location) {
        while (entityLocations.size() <= index) entityLocations.add(null);
        entityLocations.set(index, location);
    }

    private EntityLocation getLocation(int index) {
        return index < entityLocations.size() ? entityLocations.get(index) : null;
    }

    private EntityLocation removeLocation(int index) {
        if (index >= entityLocations.size()) return null;
        return entityLocations.set(index, null);
    }

    public void close() {
        executor.shutdown();
    }

    public void setSystemEnabled(String systemName, boolean enabled) {
        if (enabled) {
            disabledSystems.remove(systemName);
        } else {
            disabledSystems.add(systemName);
        }
    }

    public void addSystem(Class<?> systemClass) {
        var newDescriptors = SystemParser.parse(systemClass, componentRegistry);
        allDescriptors.addAll(newDescriptors);
        parseRunConditions(systemClass);
        rebuildSchedule();
    }

    public void addSystem(Object systemInstance) {
        var newDescriptors = SystemParser.parse(systemInstance, componentRegistry);
        allDescriptors.addAll(newDescriptors);
        parseRunConditions(systemInstance.getClass());
        rebuildSchedule();
    }

    public void removeSystem(String systemName) {
        boolean removed = allDescriptors.removeIf(d ->
            d.name().equals(systemName) || d.name().endsWith("." + systemName));
        if (removed) {
            // Drop any per-system Local<?> instances whose descriptor no longer
            // exists, so repeated add/remove cycles don't accumulate orphaned
            // state keyed by "desc.name():paramIndex".
            var surviving = new HashSet<String>();
            for (var d : allDescriptors) surviving.add(d.name());
            locals.keySet().removeIf(k -> {
                int colon = k.lastIndexOf(':');
                if (colon < 0) return false;
                return !surviving.contains(k.substring(0, colon));
            });
            rebuildSchedule();
        }
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
        flushPendingCommands();
    }

    private void flushPendingCommands() {
        for (var cmds : allCommandBuffers) {
            if (!cmds.isEmpty()) {
                zzuegg.ecs.command.CommandProcessor.process(cmds.drain(), this);
            }
        }
    }

    private void executeSystem(ScheduleGraph.SystemNode node) {
        var desc = node.descriptor();
        var invoker = node.invoker();
        if (invoker == null) {
            // A node reached executeSystem without an invoker — rebuildSchedule
            // is expected to have populated one for every method-backed node.
            // Fail loudly so a half-rebuilt schedule can't silently skip work.
            throw new IllegalStateException(
                "System '" + desc.name() + "' has no invoker — schedule not fully built");
        }

        // Check disabled (supports both qualified "Class.method" and simple "method" names)
        if (disabledSystems.contains(desc.name())) {
            return;
        }
        if (desc.name().contains(".")) {
            var simpleName = desc.name().substring(desc.name().lastIndexOf('.') + 1);
            if (disabledSystems.contains(simpleName)) {
                return;
            }
        }

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

        if (desc.componentAccesses().isEmpty()) {
            // No component params — invoke once with the plan's pre-resolved
            // service args (Commands, Res, EventReader, RemovedComponents, ...).
            // buildServiceArgs previously re-resolved from scratch, which
            // returned null for RemovedComponents and leaked fresh Commands
            // buffers on every call.
            var plan = systemPlans.get(desc.name());
            try {
                invoker.invoke(plan.args());
            } catch (Throwable e) {
                throw new RuntimeException("System failed: " + desc.name(), e);
            }
            // Even zero-component systems need their watermark advanced for
            // RemovedComponents/@Filter consumers.
            if (plan.hasChangeFilters() || !desc.removedReads().isEmpty()) {
                plan.markExecuted(tick.current());
            }
            return;
        }

        // Pull the pre-cached query sets from the plan instead of rebuilding.
        var cachedPlan = systemPlans.get(desc.name());
        var requiredComponents = cachedPlan.requiredComponents();
        var withoutIds = cachedPlan.withoutComponents();

        var matchingArchetypes = archetypeGraph.findMatching(requiredComponents);

        var currentTick = tick.current();
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
        // Advance the per-system "last seen" watermark so change filters and
        // RemovedComponents readers on the next tick compare against this
        // tick's boundary.
        var plan = systemPlans.get(desc.name());
        if (plan != null && (plan.hasChangeFilters() || !desc.removedReads().isEmpty())) {
            plan.markExecuted(currentTick);
        }
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
            var cmds = new Commands();
            allCommandBuffers.add(cmds);
            return cmds;
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
