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
    // Separate map for @ForEachPair systems. They don't go through
    // the chunk-iteration path; instead, the processor walks the
    // relation store's forward index once per tick.
    private final Map<String, zzuegg.ecs.system.PairIterationProcessor> pairIterationProcessors = new HashMap<>();
    // Tier-1 bytecode-generated runner per @ForEachPair system.
    // When present, preferred over the reflective processor above.
    private final Map<String, zzuegg.ecs.system.PairIterationRunner> pairIterationRunners = new HashMap<>();
    private final Map<String, zzuegg.ecs.system.ExclusiveRunner> exclusiveRunners = new HashMap<>();
    private final Set<String> disabledSystems = new HashSet<>();
    private final List<Commands> allCommandBuffers = new ArrayList<>();
    private final RemovalLog removalLog = new RemovalLog();
    // Components observed via RemovedComponents<T> in any plan. GC walks only
    // these each tick.
    private final Set<ComponentId> trackedRemovedComponents = new HashSet<>();
    // Components observed via @Filter(Added/Changed) in any plan. Used to
    // drive per-chunk dirty-list pruning at end of tick.
    private final Set<ComponentId> trackedChangeFilterComponents = new HashSet<>();
    private final boolean useGeneratedProcessors;
    private final boolean useDefaultStorageFactory;

    World(WorldBuilder builder) {
        this.archetypeGraph = new ArchetypeGraph(componentRegistry, builder.chunkSize, builder.storageFactory);
        this.executor = builder.executor;
        this.useGeneratedProcessors = builder.useGeneratedProcessors;
        this.useDefaultStorageFactory = builder.useDefaultStorageFactory;
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
        pairIterationProcessors.clear();
        pairIterationRunners.clear();
        exclusiveRunners.clear();
        // Every rebuild re-resolves service args, which allocates fresh Commands
        // buffers. Drop the old ones so the list doesn't grow unbounded.
        allCommandBuffers.clear();
        // Removal consumers must be re-registered by each plan below.
        removalLog.clearConsumers();
        trackedRemovedComponents.clear();
        trackedChangeFilterComponents.clear();
        this.schedule = new Schedule(allDescriptors, stages);

        for (var entry : schedule.orderedStages()) {
            entry.getValue().buildInvokers();
            for (var node : entry.getValue().nodes()) {
                buildExecutionPlan(node.descriptor());
            }
        }

        // After every system has been processed we know the complete
        // set of "observed" components — those with an @Filter consumer
        // (trackedChangeFilterComponents). Any component outside that
        // set can have its ChangeTracker bookkeeping fully disabled,
        // skipping per-slot tick array writes entirely on every
        // markAdded / markChanged call. Computing this at plan build
        // time means the fast path is active before the first tick.
        var untracked = new HashSet<ComponentId>();
        for (int i = 0; i < componentRegistry.count(); i++) {
            var id = new ComponentId(i);
            if (!trackedChangeFilterComponents.contains(id)) {
                untracked.add(id);
            }
        }
        archetypeGraph.setFullyUntrackedComponents(untracked);

        // Same logic for pair change trackers: if no system declares
        // a change filter on a relation type's marker component, the
        // per-relation PairChangeTracker is completely unused —
        // every markAdded/markChanged/remove on it is wasted
        // bookkeeping. Flag those trackers as fully untracked.
        // Note: this is independent of RemovedRelations<T>, which
        // uses the separate PairRemovalLog, not the PairChangeTracker.
        for (var store : componentRegistry.allRelationStores()) {
            boolean observed = store.sourceMarkerId() != null
                    && trackedChangeFilterComponents.contains(store.sourceMarkerId());
            store.tracker().setFullyUntracked(!observed);
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
            } else if (desc.targetEntityParamSlots().contains(i)) {
                // @FromTarget Entity — bound per-pair by the pair-
                // iteration processor, not a service arg.
            } else if (desc.pairValueParamSlot() == i) {
                // @ForEachPair payload — bound per-pair, not a service arg.
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
            var targetIds = new zzuegg.ecs.component.ComponentId[f.targets().size()];
            for (int i = 0; i < f.targets().size(); i++) {
                var tid = componentRegistry.getOrRegister(f.targets().get(i));
                targetIds[i] = tid;
                trackedChangeFilterComponents.add(tid);
                archetypeGraph.enableDirtyTracking(tid);
            }
            resolvedChangeFilters.add(new SystemExecutionPlan.ResolvedChangeFilter(targetIds, kind));
        }

        var plan = new SystemExecutionPlan(params.length, componentSlots, serviceArgIndices,
            desc.whereFilters(), resolvedChangeFilters);

        // Pre-compute the query sets once; executeSystem used to rebuild them
        // on every tick per system.
        //
        // For @ForEachPair systems the iteration is driven directly by
        // the relation store, not by archetype matching — adding the
        // component ids to `required` would narrow the filter to
        // entities that carry *every* declared component, including
        // target-side ones, which makes no sense for a per-pair driver.
        // Skip the required set entirely in that case.
        var required = new HashSet<ComponentId>();
        boolean isPairIteration = desc.pairIterationType() != null;
        if (!isPairIteration) {
            for (var access : desc.componentAccesses()) {
                required.add(access.componentId());
            }
        }
        for (var withType : desc.withFilters()) {
            required.add(componentRegistry.getOrRegister(withType));
        }
        // @Pair(T.class, role = ...) adds the relation's marker
        // ComponentId to the required-set so the archetype filter
        // narrows to entities that carry at least one pair of type T
        // on the requested side. The role decides which marker:
        //   SOURCE → source-side marker (outgoing pairs)
        //   TARGET → target-side marker (incoming pairs)
        //   EITHER → no marker added; annotation is informational.
        // The stores are allocated eagerly during SystemParser, so
        // they are guaranteed to exist here.
        for (var pairRead : desc.pairReads()) {
            var store = componentRegistry.relationStore(pairRead.type());
            if (store == null) continue;
            switch (pairRead.role()) {
                case SOURCE -> {
                    if (store.sourceMarkerId() != null) required.add(store.sourceMarkerId());
                }
                case TARGET -> {
                    if (store.targetMarkerId() != null) required.add(store.targetMarkerId());
                }
                case EITHER -> { /* no narrowing */ }
            }
        }
        var without = new HashSet<ComponentId>();
        for (var withoutType : desc.withoutFilters()) {
            without.add(componentRegistry.getOrRegister(withoutType));
        }
        plan.setQuerySets(Set.copyOf(required), Set.copyOf(without));
        plan.setConsumedRemovedComponents(Set.copyOf(desc.removedReads()));
        plan.setConsumedRemovedRelations(Set.copyOf(desc.removedRelationReads()));

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
            } else if (p.getType() == zzuegg.ecs.relation.RemovedRelations.class) {
                // Parallel to RemovedComponents — bind to the store's
                // PairRemovalLog and register as a consumer so the log
                // starts retaining entries.
                @SuppressWarnings("unchecked")
                var recType = (Class<? extends Record>) extractTypeArg(p);
                var store = componentRegistry.registerRelation(recType);
                store.removalLog().registerConsumer();
                arg = zzuegg.ecs.relation.RemovedRelations.bind(store.removalLog(), plan);
            } else {
                arg = resolveServiceParam(desc, p, idx);
            }
            resolvedServiceArgs[idx] = arg;
            plan.setServiceArg(idx, arg);
        }

        systemPlans.put(desc.name(), plan);

        // @ForEachPair systems bypass chunk-processor generation and
        // use a dedicated pair-iteration processor that walks the
        // relation store's forward index directly. Prefer the
        // tier-1 bytecode-generated runner when the generator
        // supports the system shape; fall back to the reflective
        // tier-2 processor otherwise.
        if (isPairIteration) {
            if (useGeneratedProcessors) {
                var tier1 = zzuegg.ecs.system.GeneratedPairIterationProcessor
                    .tryGenerate(desc, this, resolvedServiceArgs);
                if (tier1 != null) {
                    pairIterationRunners.put(desc.name(), tier1);
                    return;
                }
            }
            pairIterationProcessors.put(desc.name(),
                new zzuegg.ecs.system.PairIterationProcessor(desc, this, resolvedServiceArgs));
            return;
        }

        // Build generated processor if enabled. Systems with change filters
        // still need the SystemExecutionPlan path — the generated processors
        // don't consult per-target ChangeTrackers. Entity-injected parameters
        // are now supported by the BytecodeChunkProcessor and DirectProcessor
        // tiers (GeneratedChunkProcessor tier-1 still bails via skipReason
        // because its read-only fast path doesn't emit the entity load).
        if (useGeneratedProcessors && !desc.isExclusive() && !desc.componentAccesses().isEmpty()) {
            chunkProcessors.put(desc.name(),
                ChunkProcessorGenerator.generate(desc, resolvedServiceArgs, useDefaultStorageFactory, plan));
        }

        // Tier-1 @Exclusive: service-only systems generate a hidden
        // class whose run() unboxes the pre-resolved args array and
        // calls the user method via direct invokevirtual, bypassing
        // SystemInvoker's MethodHandle spreader.
        if (useGeneratedProcessors && desc.isExclusive()) {
            var tier1 = zzuegg.ecs.system.GeneratedExclusiveProcessor
                .tryGenerate(desc, plan.args());
            if (tier1 != null) {
                exclusiveRunners.put(desc.name(), tier1);
            }
        }
    }

    /**
     * Framework/test hook: return the tier-1 generated exclusive
     * runner for a given system name, or {@code null} if the system
     * is not exclusive or the generator bailed for some reason.
     */
    public zzuegg.ecs.system.ExclusiveRunner generatedExclusiveRunner(String systemName) {
        return exclusiveRunners.get(systemName);
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
                    throw new IllegalStateException(
                        "Cannot instantiate @RunCondition class " + clazz.getName()
                            + "; ensure it has a no-arg constructor", e);
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
        despawnWithCascade(entity);
    }

    /**
     * Despawn the entity if it's still alive, otherwise no-op. Used by the
     * command flush path to avoid a double isAlive check (caller already
     * tests; then {@code despawn} would test again).
     */
    public void despawnIfAlive(zzuegg.ecs.entity.Entity entity) {
        if (!entityAllocator.isAlive(entity)) return;
        despawnWithCascade(entity);
    }

    /**
     * Drive the CleanupPolicy state machine. For each relation store:
     * drop every outgoing pair from the despawning entity, then apply
     * the store's {@code onTargetDespawn} policy to every incoming
     * pair pointing at it. {@code CASCADE_SOURCE} enqueues additional
     * entities to despawn; we drain the queue in FIFO order so every
     * transitive source eventually reaches this method.
     *
     * <p>Entities are de-duplicated by the {@code isAlive} check —
     * an entity already despawned in an earlier iteration silently
     * skips.
     */
    private void despawnWithCascade(zzuegg.ecs.entity.Entity root) {
        // Fast path: worlds with no relations registered (the common
        // benchmark case) skip both the cascade machinery and the
        // allocations it carries.
        if (!componentRegistry.hasAnyRelations()) {
            despawnInternal(root);
            return;
        }
        var queue = new ArrayDeque<zzuegg.ecs.entity.Entity>();
        queue.add(root);
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (!entityAllocator.isAlive(next)) continue;
            applyRelationCleanup(next, queue);
            despawnInternal(next);
        }
    }

    private void applyRelationCleanup(
            zzuegg.ecs.entity.Entity entity,
            ArrayDeque<zzuegg.ecs.entity.Entity> cascadeQueue) {
        var tickNow = tick.current();
        for (var store : componentRegistry.allRelationStores()) {
            // Outgoing pairs: the entity IS the source. Drop each
            // forward entry so reverse cleanup + tracker + removal-log
            // updates happen inside store.remove. No source-marker
            // fix needed on `entity` itself — the despawn is about to
            // free its entire archetype row — but each former target
            // may have just lost its last incoming pair of this type,
            // in which case it loses its target marker.
            if (store.hasSource(entity)) {
                var targets = new java.util.ArrayList<zzuegg.ecs.entity.Entity>();
                for (var e : store.targetsFor(entity)) targets.add(e.getKey());
                for (var t : targets) {
                    store.remove(entity, t, tickNow);
                    if (store.targetMarkerId() != null
                            && entityAllocator.isAlive(t)
                            && !store.hasTarget(t)) {
                        removeMarkerComponent(t, store.targetMarkerId());
                    }
                }
            }
            // Incoming pairs: something points AT entity. Walk a snapshot
            // of sources — the body below mutates the store.
            var sources = new java.util.ArrayList<zzuegg.ecs.entity.Entity>();
            for (var s : store.sourcesFor(entity)) sources.add(s);
            if (sources.isEmpty()) continue;
            var policy = store.onTargetDespawn();
            if (policy == zzuegg.ecs.relation.CleanupPolicy.IGNORE) continue;

            for (var source : sources) {
                // Drop the pair regardless of RELEASE_TARGET vs CASCADE —
                // in both cases the pair is dead. The difference is
                // whether the source entity survives.
                store.remove(source, entity, tickNow);
                if (policy == zzuegg.ecs.relation.CleanupPolicy.CASCADE_SOURCE) {
                    cascadeQueue.add(source);
                } else {
                    // RELEASE_TARGET: source survives. If it lost its
                    // last pair of this type, the source marker goes
                    // too. The target marker on `entity` is freed with
                    // its archetype row by despawnInternal, so no
                    // explicit target-marker removal is needed here.
                    if (entityAllocator.isAlive(source) && !store.hasSource(source)) {
                        removeMarkerComponent(source, store.sourceMarkerId());
                    }
                }
            }
        }
    }

    private void despawnInternal(zzuegg.ecs.entity.Entity entity) {
        var location = removeLocation(entity.index());
        // Caller already confirmed isAlive; a missing location would be an
        // invariant violation. Fail loudly rather than silently leaking.
        if (location == null) {
            throw new IllegalStateException(
                "Invariant violation: alive entity has no location: " + entity);
        }
        var archetype = location.archetype();

        // Log every component the entity is about to lose so RemovedComponents<T>
        // consumers can see them. Skip the whole loop when no component on
        // this entity is being tracked — short-circuits the common case where
        // the system has no @RemovedComponents readers at all.
        if (!trackedRemovedComponents.isEmpty()) {
            var chunk = archetype.chunks().get(location.chunkIndex());
            int slot = location.slotIndex();
            for (var compId : archetype.id().components()) {
                if (trackedRemovedComponents.contains(compId)) {
                    removalLog.append(compId, entity, (Record) chunk.get(compId, slot), tick.current());
                }
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
        return location.archetype().get(compId, location);
    }

    public <T> void setResource(T resource) {
        resourceStore.setDirect(resource.getClass(), resource);
    }

    public void setComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        // One HashMap lookup instead of two — setComponent is called once per
        // mutation and previously paid for both getOrRegister(class) and
        // info(class), which hashed the same Class key twice.
        var info = componentRegistry.getOrRegisterInfo(component.getClass());
        var compId = info.id();
        var location = getLocation(entity.index());
        // No archetypeGraph lookup — EntityLocation already holds a direct
        // Archetype reference. Then one ArrayList.get for the chunk; the
        // JIT CSEs this out of loops where the archetype is stable, so
        // caching the chunk on EntityLocation (which varies per entity)
        // was a regression.
        var archetype = location.archetype();
        var chunk = archetype.chunks().get(location.chunkIndex());
        int slot = location.slotIndex();

        // For @ValueTracked components, skip the change-detection bump when the
        // new value equals the existing one — matches Mut.flush()'s semantics so
        // both mutation paths behave the same way.
        boolean fireChanged = true;
        if (info.isValueTracked()) {
            @SuppressWarnings("unchecked")
            var existing = (Record) chunk.componentStorage(compId).get(slot);
            if (existing != null && existing.equals(component)) {
                fireChanged = false;
            }
        }

        chunk.set(compId, slot, component);

        if (fireChanged) {
            chunk.changeTracker(compId).markChanged(slot, tick.current());
        }
    }

    /**
     * Package-private accessor for the underlying {@link ComponentRegistry}.
     * Used by tests and by the relation-layer wiring (archetype marker
     * id allocation). Not part of the public surface — the registry is
     * a world-lifetime detail that production code should reach
     * through {@code setRelation} / {@code getRelation} etc.
     */
    public ComponentRegistry componentRegistry() {
        return componentRegistry;
    }

    /**
     * Current tick counter. Exposed for pair-iteration processors
     * and other framework-internal consumers that need to stamp
     * per-entity state (Mut writes, change-tracker updates) at the
     * correct tick.
     */
    public long currentTick() {
        return tick.current();
    }

    /**
     * Package-of-framework accessor returning the
     * {@link zzuegg.ecs.archetype.EntityLocation} for an entity by
     * its index, or {@code null} if no live location exists. Used
     * by {@link zzuegg.ecs.system.PairIterationProcessor} to walk
     * the relation store's forward index and resolve source/target
     * components without going through the slower
     * {@link #getComponent} chain.
     */
    public zzuegg.ecs.archetype.EntityLocation entityLocationFor(zzuegg.ecs.entity.Entity entity) {
        int idx = entity.index();
        if (idx < 0 || idx >= entityLocations.size()) return null;
        return entityLocations.get(idx);
    }

    /**
     * Raw view of the world's entity-location list. Framework-
     * internal — exposed for tier-1 generated
     * {@code @ForEachPair} processors to look up entity locations
     * by index without going through the slower
     * {@link #entityLocationFor} wrapper. Do not mutate. The
     * returned reference remains stable across
     * {@code spawn}/{@code despawn} because the {@link ArrayList}
     * grows in place.
     */
    public java.util.List<zzuegg.ecs.archetype.EntityLocation> entityLocationsView() {
        return entityLocations;
    }

    /**
     * Return the archetype an entity currently lives in. Used by tests
     * and diagnostic tools; production code should not need this. An
     * alive entity is always in exactly one archetype.
     */
    public zzuegg.ecs.archetype.Archetype archetypeOf(zzuegg.ecs.entity.Entity entity) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        return getLocation(entity.index()).archetype();
    }

    /**
     * Add a marker component (a {@link ComponentId} that carries no
     * per-entity data) to an entity. Walks the archetype graph exactly
     * like {@link #addComponent} but skips the value-write step — the
     * storage slot stays {@code null}. Used by the relation layer to
     * maintain the "has >= 1 pair of type T" archetype flag.
     *
     * <p>Idempotent: adding a marker the entity already carries is a
     * no-op, matching the usual "first pair transition" contract.
     */
    public void addMarkerComponent(zzuegg.ecs.entity.Entity entity, ComponentId markerId) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var oldLocation = getLocation(entity.index());
        var oldArchetype = oldLocation.archetype();
        if (oldArchetype.id().components().contains(markerId)) return;

        var newArchetypeId = archetypeGraph.addEdge(oldArchetype.id(), markerId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);
        var newLocation = newArchetype.add(entity);

        var oldChunk = oldArchetype.chunks().get(oldLocation.chunkIndex());
        var newChunk = newArchetype.chunks().get(newLocation.chunkIndex());
        int oldSlot = oldLocation.slotIndex();
        int newSlot = newLocation.slotIndex();

        for (var existingCompId : oldArchetype.id().components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
            var src = oldChunk.changeTracker(existingCompId);
            var dst = newChunk.changeTracker(existingCompId);
            dst.markAdded(newSlot, src.addedTick(oldSlot));
            dst.markChanged(newSlot, src.changedTick(oldSlot));
        }
        // Marker slot stays null — it's the archetype membership that
        // matters, not the per-entity value. Mark it added so
        // @Filter(Added) systems that target the marker fire.
        newChunk.changeTracker(markerId).markAdded(newSlot, tick.current());

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            setLocation(swappedEntity.index(), oldLocation)
        );
        setLocation(entity.index(), newLocation);
    }

    /**
     * Remove a marker component from an entity. Mirror of
     * {@link #addMarkerComponent} — walks the archetype graph without
     * touching per-entity storage. A no-op when the marker isn't
     * present on the entity's current archetype.
     */
    public void removeMarkerComponent(zzuegg.ecs.entity.Entity entity, ComponentId markerId) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var oldLocation = getLocation(entity.index());
        var oldArchetype = oldLocation.archetype();
        if (!oldArchetype.id().components().contains(markerId)) return;

        var newArchetypeId = archetypeGraph.removeEdge(oldArchetype.id(), markerId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);
        var newLocation = newArchetype.add(entity);

        var oldChunk = oldArchetype.chunks().get(oldLocation.chunkIndex());
        var newChunk = newArchetype.chunks().get(newLocation.chunkIndex());
        int oldSlot = oldLocation.slotIndex();
        int newSlot = newLocation.slotIndex();

        for (var existingCompId : newArchetypeId.components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
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

    // ---------------------------------------------------------------
    // Relation CRUD
    // ---------------------------------------------------------------

    /**
     * Insert or overwrite the pair payload for
     * {@code (source, target)} under the given relation type. Auto-
     * registers the relation if this is the first time it's seen.
     *
     * <p>On the first pair for {@code source} of this relation type,
     * the source's archetype gains the relation marker — systems
     * filtered on {@code @Pair(T.class)} will start seeing the entity.
     */
    @SuppressWarnings("unchecked")
    public <T extends Record> void setRelation(
            zzuegg.ecs.entity.Entity source,
            zzuegg.ecs.entity.Entity target,
            T value) {
        if (!entityAllocator.isAlive(source)) {
            throw new IllegalArgumentException("Source entity not alive: " + source);
        }
        if (!entityAllocator.isAlive(target)) {
            throw new IllegalArgumentException("Target entity not alive: " + target);
        }
        var store = (zzuegg.ecs.relation.RelationStore<T>)
            componentRegistry.registerRelation((Class<? extends Record>) value.getClass());
        boolean firstPairForSource = !store.hasSource(source);
        boolean firstPairForTarget = !store.hasTarget(target);
        store.set(source, target, value, tick.current());
        if (firstPairForSource) {
            addMarkerComponent(source, store.sourceMarkerId());
        }
        if (firstPairForTarget && store.targetMarkerId() != null) {
            addMarkerComponent(target, store.targetMarkerId());
        }
    }

    /**
     * Look up the pair payload for {@code (source, target)} under the
     * given relation type, or {@link Optional#empty()} if no such
     * pair exists. Returns {@code empty} when the relation type has
     * never been registered.
     */
    public <T extends Record> Optional<T> getRelation(
            zzuegg.ecs.entity.Entity source,
            zzuegg.ecs.entity.Entity target,
            Class<T> type) {
        var store = componentRegistry.relationStore(type);
        if (store == null) return Optional.empty();
        return Optional.ofNullable(store.get(source, target));
    }

    /**
     * Drop the pair for {@code (source, target)} under the given
     * relation type. A no-op if the relation was never registered or
     * the pair does not exist. When this is the source's last pair of
     * this type, the source's archetype also loses the marker.
     */
    public <T extends Record> void removeRelation(
            zzuegg.ecs.entity.Entity source,
            zzuegg.ecs.entity.Entity target,
            Class<T> type) {
        if (!entityAllocator.isAlive(source)) {
            throw new IllegalArgumentException("Source entity not alive: " + source);
        }
        var store = componentRegistry.relationStore(type);
        if (store == null) return;
        var removed = store.remove(source, target, tick.current());
        if (removed == null) return;
        if (!store.hasSource(source)) {
            removeMarkerComponent(source, store.sourceMarkerId());
        }
        if (store.targetMarkerId() != null
                && entityAllocator.isAlive(target)
                && !store.hasTarget(target)) {
            removeMarkerComponent(target, store.targetMarkerId());
        }
    }

    /**
     * Drop every pair of the given relation type whose source is
     * {@code source}. Cheaper than looping {@link #removeRelation} on
     * the caller's side because the marker is removed exactly once.
     */
    public <T extends Record> void removeAllRelations(
            zzuegg.ecs.entity.Entity source,
            Class<T> type) {
        if (!entityAllocator.isAlive(source)) {
            throw new IllegalArgumentException("Source entity not alive: " + source);
        }
        var store = componentRegistry.relationStore(type);
        if (store == null || !store.hasSource(source)) return;
        // Snapshot targets so we can iterate + mutate safely.
        var targets = new ArrayList<zzuegg.ecs.entity.Entity>();
        for (var e : store.targetsFor(source)) targets.add(e.getKey());
        for (var tgt : targets) {
            store.remove(source, tgt, tick.current());
            // Each target may have just lost its last incoming pair
            // of this type — clear its target marker if so.
            if (store.targetMarkerId() != null
                    && entityAllocator.isAlive(tgt)
                    && !store.hasTarget(tgt)) {
                removeMarkerComponent(tgt, store.targetMarkerId());
            }
        }
        removeMarkerComponent(source, store.sourceMarkerId());
    }

    public void addComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var oldLocation = getLocation(entity.index());
        var oldArchetype = oldLocation.archetype();

        var newArchetypeId = archetypeGraph.addEdge(oldArchetype.id(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        var oldChunk = oldArchetype.chunks().get(oldLocation.chunkIndex());
        var newChunk = newArchetype.chunks().get(newLocation.chunkIndex());
        int oldSlot = oldLocation.slotIndex();
        int newSlot = newLocation.slotIndex();

        for (var existingCompId : oldArchetype.id().components()) {
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
        var oldArchetype = oldLocation.archetype();

        // Log the removal BEFORE migration, so the last-known value is still
        // reachable in the source chunk for RemovedComponents<T> consumers.
        if (trackedRemovedComponents.contains(compId)) {
            var oldChunkEarly = oldArchetype.chunks().get(oldLocation.chunkIndex());
            removalLog.append(compId, entity,
                (Record) oldChunkEarly.get(compId, oldLocation.slotIndex()), tick.current());
        }

        var newArchetypeId = archetypeGraph.removeEdge(oldArchetype.id(), compId);
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

        // End-of-tick GC for per-relation-type removal logs. Parallels the
        // component RemovalLog pass above but reads watermarks from each
        // plan's consumedRemovedRelations set. A relation type with no
        // RemovedRelations<T> consumer is silently skipped because its log
        // never retained entries in the first place (registerConsumer
        // short-circuits the append path). Fast-path out for worlds with
        // no relations at all so we don't walk an empty collection every
        // tick in the common benchmark case.
        if (componentRegistry.hasAnyRelations()) {
            for (var store : componentRegistry.allRelationStores()) {
                long minWatermark = Long.MAX_VALUE;
                for (var p : systemPlans.values()) {
                    if (p.consumedRemovedRelations().contains(store.type())
                            && p.lastSeenTick() < minWatermark) {
                        minWatermark = p.lastSeenTick();
                    }
                }
                if (minWatermark != Long.MAX_VALUE) {
                    store.removalLog().collectGarbage(minWatermark);
                }
            }
        }

        // End-of-tick prune of per-chunk change-tracker dirty lists. For each
        // component observed via @Filter(Added/Changed), compute the minimum
        // lastSeenTick across plans that consume it, walk every chunk of every
        // archetype containing that component, and drop dirty-list entries
        // whose ticks are ≤ minWatermark. Systems with older watermarks (e.g.
        // disabled / RunIf-skipped) hold their entries until they catch up.
        if (!trackedChangeFilterComponents.isEmpty()) {
            for (var compId : trackedChangeFilterComponents) {
                long minWatermark = Long.MAX_VALUE;
                for (var p : systemPlans.values()) {
                    if (!p.hasChangeFilters()) continue;
                    for (var f : p.resolvedChangeFilters()) {
                        boolean targets = false;
                        for (var tid : f.targetIds()) {
                            if (tid.equals(compId)) { targets = true; break; }
                        }
                        if (targets && p.lastSeenTick() < minWatermark) {
                            minWatermark = p.lastSeenTick();
                        }
                    }
                }
                if (minWatermark == Long.MAX_VALUE) continue;
                var matching = archetypeGraph.findMatching(Set.of(compId));
                for (var archetype : matching) {
                    for (var chunk : archetype.chunks()) {
                        var tracker = chunk.changeTracker(compId);
                        if (tracker != null) tracker.pruneDirtyList(minWatermark);
                    }
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
            // Exclusive systems get their full service-arg array from
            // the plan, same as non-exclusive zero-component systems.
            // The World slot was already resolved to `this` by
            // resolveServiceParam during plan build, and any extra
            // service params (Res, ResMut, Commands, ...) are also
            // pre-filled.
            //
            // Prefer the tier-1 generated runner — it unboxes the args
            // array and calls the user method via direct invokevirtual,
            // eliminating the SystemInvoker MethodHandle spreader
            // overhead. Fall back to the reflective SystemInvoker when
            // the generator isn't available or bailed.
            var tier1Excl = exclusiveRunners.get(desc.name());
            if (tier1Excl != null) {
                try {
                    tier1Excl.run();
                } catch (Throwable e) {
                    throw new RuntimeException("Exclusive tier-1 failed: " + desc.name(), e);
                }
                return;
            }
            var plan = systemPlans.get(desc.name());
            try {
                invoker.invoke(plan.args());
            } catch (Throwable e) {
                throw new RuntimeException("Exclusive system failed: " + desc.name(), e);
            }
            return;
        }

        // @ForEachPair systems skip the archetype-matching path and
        // walk the relation store directly. Prefer the tier-1
        // generated runner when available; fall back to the tier-2
        // reflective processor.
        var pairRunner = pairIterationRunners.get(desc.name());
        if (pairRunner != null) {
            try {
                pairRunner.run(tick.current());
            } catch (Throwable e) {
                throw new RuntimeException("@ForEachPair tier-1 failed: " + desc.name(), e);
            }
            return;
        }
        var pairProc = pairIterationProcessors.get(desc.name());
        if (pairProc != null) {
            try {
                pairProc.run();
            } catch (Throwable e) {
                throw new RuntimeException("@ForEachPair system failed: " + desc.name(), e);
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
            if (plan.hasChangeFilters() || !desc.removedReads().isEmpty() || !desc.removedRelationReads().isEmpty()) {
                plan.markExecuted(tick.current());
            }
            return;
        }

        // Pull the pre-cached query sets from the plan instead of rebuilding.
        var cachedPlan = systemPlans.get(desc.name());
        var withoutIds = cachedPlan.withoutComponents();

        // Fast path: the plan remembers the last findMatching() result and
        // the archetype-graph generation at which it was cached. If the
        // graph hasn't grown since then, reuse it and skip the per-tick
        // Set<ComponentId> hash lookup entirely.
        long gen = archetypeGraph.generation();
        java.util.List<zzuegg.ecs.archetype.Archetype> matchingArchetypes =
            cachedPlan.cachedMatchingArchetypes(gen);
        if (matchingArchetypes == null) {
            matchingArchetypes = archetypeGraph.findMatching(cachedPlan.requiredComponents());
            cachedPlan.cacheMatchingArchetypes(gen, matchingArchetypes);
        }

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
        if (plan != null && (plan.hasChangeFilters() || !desc.removedReads().isEmpty() || !desc.removedRelationReads().isEmpty())) {
            plan.markExecuted(currentTick);
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolveServiceParam(SystemDescriptor desc, Parameter param, int paramIndex) {
        var paramType = param.getType();
        if (paramType == World.class) {
            // @Exclusive systems can take the owning World directly; the
            // tick-time executor path passes `this` for every exclusive
            // system regardless of the declared param shape, but we still
            // need to populate the plan's service-arg slot so the
            // SystemExecutionPlan-based path (reflection fallback for
            // non-generated systems) sees the same instance.
            return this;
        }
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
        } else if (paramType == zzuegg.ecs.relation.PairReader.class) {
            var recType = (Class<? extends Record>) extractTypeArg(param);
            var store = componentRegistry.registerRelation(recType);
            return new zzuegg.ecs.relation.StorePairReader<>(store);
        } else if (paramType == zzuegg.ecs.component.ComponentReader.class) {
            // Fast cached cross-entity component reader. Direct
            // reference to this.entityLocations avoids the lambda
            // indirection an IntFunction closure would require.
            var recType = (Class<? extends Record>) extractTypeArg(param);
            var compId = componentRegistry.getOrRegister(recType);
            return new zzuegg.ecs.component.ComponentReader<>(compId, entityLocations);
        }
        throw new IllegalArgumentException(
            "System '" + desc.name() + "': unrecognised service parameter type '"
                + paramType.getName() + "' at index " + paramIndex
                + ". Annotate with @Read/@Write for components, or use a supported"
                + " service type (Commands, Res, ResMut, EventReader, EventWriter, Local).");
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
