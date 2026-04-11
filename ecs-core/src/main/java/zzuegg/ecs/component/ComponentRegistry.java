package zzuegg.ecs.component;

import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.relation.RelationStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

public final class ComponentRegistry {

    private final Map<Class<?>, ComponentInfo> byType = new ConcurrentHashMap<>();
    private final Map<ComponentId, ComponentInfo> byId = new ConcurrentHashMap<>();
    // Per-relation-type storage. Kept on a separate map so relation types
    // (first-class (source, target) pairs) can never collide with regular
    // component ids, and so the hot component-lookup path stays untouched.
    private final Map<Class<? extends Record>, RelationStore<?>> relationStores = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);
    // Per-class cache that HotSpot special-cases: ClassValue.get has a JIT
    // intrinsic that lets the JIT fold the lookup to a direct field read on
    // the Class object when the call site is monomorphic. Previously the
    // hot path {@code getOrRegisterInfo(component.getClass())} went through
    // a HashMap<Class,?>.get — noticeable on setComponent-heavy workloads.
    // ClassValue's computeValue is called exactly once per Class and the
    // result is cached on the Class itself, thread-safe by construction.
    private final ClassValue<ComponentInfo> infoByClass = new ClassValue<>() {
        @Override
        protected ComponentInfo computeValue(Class<?> type) {
            // Populate the HashMaps as a side-effect of the first resolve —
            // other entry points still read from them, and this also assigns
            // the ComponentId.
            register(type);
            return byType.get(type);
        }
    };

    public ComponentId register(Class<?> type) {
        var existing = byType.get(type);
        if (existing != null) {
            return existing.id();
        }
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Components must be records: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        var recordType = (Class<? extends Record>) type;

        boolean sparse = type.isAnnotationPresent(SparseStorage.class);
        if (sparse) {
            // Sparse storage was scaffolded (annotation + ComponentArray / SparseSet)
            // but never wired into the archetype/chunk pipeline. Silently falling
            // back to table storage would mask the gap; a hard failure is clearer
            // until the sparse path is actually implemented.
            throw new UnsupportedOperationException(
                "@SparseStorage is not yet implemented; mark " + type.getName()
                    + " with @TableStorage (default) instead");
        }
        boolean valueTracked = type.isAnnotationPresent(ValueTracked.class);

        // Use compute to atomically assign an id and populate both maps so
        // concurrent first-registrations of different component types don't
        // produce duplicate ids or leave byId inconsistent with byType.
        ComponentInfo[] result = new ComponentInfo[1];
        byType.compute(type, (k, prev) -> {
            if (prev != null) { result[0] = prev; return prev; }
            var id = new ComponentId(nextId.getAndIncrement());
            var info = new ComponentInfo(id, recordType, true, false, valueTracked);
            byId.put(id, info);
            result[0] = info;
            return info;
        });
        return result[0].id();
    }

    public ComponentId getOrRegister(Class<?> type) {
        return getOrRegisterInfo(type).id();
    }

    /**
     * One-lookup variant used by hot paths like {@code World.setComponent}
     * that need both the {@code ComponentId} and the {@code ComponentInfo}
     * for the same class. Backed by {@link ClassValue}, which HotSpot has
     * a JIT intrinsic for — the JIT can fold this lookup to a direct field
     * load on the {@code Class} object when the call site sees a stable
     * component type.
     */
    public ComponentInfo getOrRegisterInfo(Class<?> type) {
        return infoByClass.get(type);
    }

    public ComponentInfo info(Class<?> type) {
        var info = byType.get(type);
        if (info == null) {
            throw new IllegalArgumentException("Component not registered: " + type.getName());
        }
        return info;
    }

    public ComponentInfo info(ComponentId id) {
        var info = byId.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown component id: " + id);
        }
        return info;
    }

    public int count() {
        return byType.size();
    }

    // ---------------------------------------------------------------
    // Relation-store side table
    // ---------------------------------------------------------------

    /**
     * Register (or fetch) the {@link RelationStore} for a relation
     * record type. Idempotent: repeat calls return the live store.
     *
     * <p>Relation types live on a separate map from component types —
     * a relation record is not assigned a {@link ComponentId} here.
     * The archetype marker that tracks "has ≥1 pair of type T" is
     * allocated later (in PR 2), also as a separate component id
     * derived from the relation type.
     */
    public <T extends Record> RelationStore<T> registerRelation(Class<T> type) {
        @SuppressWarnings("unchecked")
        var store = (RelationStore<T>) relationStores.computeIfAbsent(type, k -> {
            // Two distinct ComponentIds per relation type:
            //
            //   sourceMarkerId — reuses the relation's own class id; set
            //                    on any entity that has >= 1 outgoing
            //                    pair of this relation type. Existing
            //                    behaviour.
            //
            //   targetMarkerId — freshly allocated id backed by the same
            //                    Class for archetype/chunk purposes (one
            //                    wasted reference slot per entity that
            //                    carries it — same deal as the source
            //                    marker). Set on any entity that has
            //                    >= 1 incoming pair of this relation
            //                    type. Enables @Pair(role = TARGET) to
            //                    narrow the archetype filter to "prey
            //                    that are being hunted" without the
            //                    user writing a reverse-index-backed
            //                    With<...> condition by hand.
            var sourceId = register(type);
            var targetId = allocateInternalMarker(type);
            var annotation = type.getAnnotation(Relation.class);
            var policy = annotation != null ? annotation.onTargetDespawn() : CleanupPolicy.RELEASE_TARGET;
            return new RelationStore<>(type, sourceId, targetId, policy);
        });
        return store;
    }

    /**
     * Allocate a fresh {@link ComponentId} that shares a display class
     * with an existing component type. Used by the relation-store
     * target marker: every relation type needs a second marker id
     * distinct from its source id but pointing at the same underlying
     * record class, so the existing archetype/chunk machinery can
     * carry it without a second per-type record declaration.
     *
     * <p>The allocation is recorded only in {@code byId}, never in
     * {@code byType}. {@code register(Class)} and {@code info(Class)}
     * still resolve to the public id for the class. Only lookups via
     * the returned {@code ComponentId} (or through
     * {@code RelationStore.targetMarkerId()}) reach the marker.
     */
    private ComponentId allocateInternalMarker(Class<? extends Record> displayType) {
        var id = new ComponentId(nextId.getAndIncrement());
        // isValueTracked=false, isTableStorage=true — archetype builds a
        // regular storage slice for the marker class. The slice holds
        // null for every entity carrying the marker.
        var info = new ComponentInfo(id, displayType, true, false, false);
        byId.put(id, info);
        return id;
    }

    /**
     * Look up the live {@link RelationStore} for a relation type, or
     * {@code null} if the type has never been registered. Unlike
     * {@link #info(Class)}, missing relations do not throw — callers
     * are expected to branch on presence.
     */
    public <T extends Record> RelationStore<T> relationStore(Class<T> type) {
        @SuppressWarnings("unchecked")
        var store = (RelationStore<T>) relationStores.get(type);
        return store;
    }

    /**
     * Every relation store currently registered. Returns a direct
     * view over the registry's live values — callers must not mutate
     * the registry itself during iteration, though mutating the
     * individual stores' internal indices is always safe. Used on
     * hot paths (despawn cleanup, end-of-tick GC) so we avoid the
     * per-call snapshot allocation.
     */
    public java.util.Collection<RelationStore<?>> allRelationStores() {
        return relationStores.values();
    }

    /**
     * {@code true} when at least one relation type has been registered.
     * Lets hot paths skip the relation-stores iteration entirely in
     * the common case where a world uses no relations.
     */
    public boolean hasAnyRelations() {
        return !relationStores.isEmpty();
    }
}
