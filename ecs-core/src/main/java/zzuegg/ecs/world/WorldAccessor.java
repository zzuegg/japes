package zzuegg.ecs.world;

import zzuegg.ecs.archetype.Archetype;
import zzuegg.ecs.component.*;
import zzuegg.ecs.entity.Entity;

import java.util.*;
import java.util.function.Consumer;

/**
 * Read-only view over a {@link World}'s entity and component data.
 * Designed for serialization, sync plugins, and debugging — not for
 * use inside system hot loops (use {@code @Read} / {@code @Write}
 * parameters for that).
 *
 * <p>This class is intentionally <b>not</b> on the system iteration
 * hot path and therefore has no impact on escape analysis.
 */
public final class WorldAccessor {

    private final World world;

    WorldAccessor(World world) {
        this.world = world;
    }

    /** Iterate every live entity in the world. */
    public void allEntities(Consumer<Entity> consumer) {
        for (var archetype : world.archetypeGraph().allArchetypes()) {
            for (var chunk : archetype.chunks()) {
                for (int i = 0; i < chunk.count(); i++) {
                    consumer.accept(chunk.entity(i));
                }
            }
        }
    }

    /** Iterate entities that have all of the given component types. */
    @SafeVarargs
    public final void entitiesWith(Consumer<Entity> consumer, Class<? extends Record>... types) {
        var compIds = new HashSet<ComponentId>();
        for (var type : types) {
            compIds.add(world.componentRegistry().getOrRegister(type));
        }
        for (var archetype : world.archetypeGraph().findMatching(compIds)) {
            for (var chunk : archetype.chunks()) {
                for (int i = 0; i < chunk.count(); i++) {
                    consumer.accept(chunk.entity(i));
                }
            }
        }
    }

    /** Convenience: returns an iterable instead of requiring a consumer. */
    @SafeVarargs
    public final Iterable<Entity> entitiesWith(Class<? extends Record>... types) {
        var result = new ArrayList<Entity>();
        entitiesWith(result::add, types);
        return result;
    }

    /** Convenience: returns an iterable of all live entities. */
    public Iterable<Entity> allEntities() {
        var result = new ArrayList<Entity>();
        allEntities(result::add);
        return result;
    }

    /** Get a component for an entity, or null if the entity doesn't have it. */
    @SuppressWarnings("unchecked")
    public <T extends Record> T getComponent(Entity entity, Class<T> type) {
        if (!world.isAlive(entity)) return null;
        if (!world.hasComponent(entity, type)) return null;
        return world.getComponent(entity, type);
    }

    /** Get all component types for an entity. */
    public Set<Class<? extends Record>> componentTypes(Entity entity) {
        var location = world.entityLocation(entity);
        if (location == null) return Set.of();
        var result = new HashSet<Class<? extends Record>>();
        for (var compId : location.archetype().id().components()) {
            result.add(world.componentRegistry().info(compId).type());
        }
        return result;
    }

    /** Iterate entities that have at least one {@link Persistent} component. */
    public Iterable<Entity> persistentEntities() {
        var result = new ArrayList<Entity>();
        for (var archetype : world.archetypeGraph().allArchetypes()) {
            if (!hasPersistentComponent(archetype)) continue;
            for (var chunk : archetype.chunks()) {
                for (int i = 0; i < chunk.count(); i++) {
                    result.add(chunk.entity(i));
                }
            }
        }
        return result;
    }

    /** Get only the {@link Persistent}-annotated components for an entity. */
    public List<Record> persistentComponents(Entity entity) {
        return filteredComponents(entity, Persistent.class);
    }

    /** Get only the {@link NetworkSync}-annotated components for an entity. */
    public List<Record> networkSyncComponents(Entity entity) {
        return filteredComponents(entity, NetworkSync.class);
    }

    private List<Record> filteredComponents(Entity entity, Class<? extends java.lang.annotation.Annotation> annotation) {
        var location = world.entityLocation(entity);
        if (location == null) return List.of();
        var result = new ArrayList<Record>();
        var chunk = location.archetype().chunks().get(location.chunkIndex());
        for (var compId : location.archetype().id().components()) {
            var info = world.componentRegistry().info(compId);
            if (info.type().isAnnotationPresent(annotation)) {
                result.add((Record) chunk.get(compId, location.slotIndex()));
            }
        }
        return result;
    }

    private boolean hasPersistentComponent(Archetype archetype) {
        for (var compId : archetype.id().components()) {
            if (world.componentRegistry().info(compId).type().isAnnotationPresent(Persistent.class)) {
                return true;
            }
        }
        return false;
    }
}
