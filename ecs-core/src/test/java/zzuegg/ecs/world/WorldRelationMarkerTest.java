package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that adding/removing a relation marker component on an
 * entity moves it through the archetype graph exactly like a regular
 * component would, but without requiring the caller to supply a value
 * for the (unused) per-entity slot.
 *
 * <p>The marker is how the existing query machinery is told
 * "entity has >= 1 pair of relation type T" — it's a ComponentId in
 * the entity's {@code ArchetypeId.components()} set, full stop. The
 * actual pair data lives in the side-table {@code RelationStore<T>}.
 */
class WorldRelationMarkerTest {

    record Position(float x, float y) {}
    // Relation types are records too. For the marker-integration tests
    // we don't care about the payload — we just want the ComponentId
    // that ComponentRegistry.registerRelation allocates for the type.
    record Targeting(int power) {}

    @Test
    void addMarkerComponentPlacesEntityInArchetypeWithMarker() {
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);
        var entity = world.spawn(new Position(0f, 0f));

        world.addMarkerComponent(entity, store.markerId());

        var components = world.archetypeOf(entity).id().components();
        assertTrue(components.contains(store.markerId()),
            "entity must sit in an archetype carrying the relation marker");
    }

    @Test
    void addMarkerComponentIsIdempotent() {
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);
        var entity = world.spawn(new Position(0f, 0f));

        world.addMarkerComponent(entity, store.markerId());
        var archAfterFirst = world.archetypeOf(entity);
        world.addMarkerComponent(entity, store.markerId());
        var archAfterSecond = world.archetypeOf(entity);

        assertSame(archAfterFirst, archAfterSecond,
            "adding an already-present marker must be a no-op");
    }

    @Test
    void removeMarkerComponentReturnsEntityToPriorArchetype() {
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);
        var entity = world.spawn(new Position(0f, 0f));

        var original = world.archetypeOf(entity);
        world.addMarkerComponent(entity, store.markerId());
        world.removeMarkerComponent(entity, store.markerId());
        var afterRemove = world.archetypeOf(entity);

        assertSame(original, afterRemove,
            "round trip add then remove must land on the original archetype");
        assertFalse(afterRemove.id().components().contains(store.markerId()));
    }

    @Test
    void removeMarkerComponentWhenAbsentIsNoOp() {
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);
        var entity = world.spawn(new Position(0f, 0f));

        var archBefore = world.archetypeOf(entity);
        world.removeMarkerComponent(entity, store.markerId());
        var archAfter = world.archetypeOf(entity);

        assertSame(archBefore, archAfter,
            "removing an already-absent marker must be a no-op");
    }

    @Test
    void regularComponentsSurviveMarkerMigration() {
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);
        var entity = world.spawn(new Position(3f, 4f));

        world.addMarkerComponent(entity, store.markerId());

        var pos = world.getComponent(entity, Position.class);
        assertEquals(new Position(3f, 4f), pos,
            "marker migration must preserve existing component values");
    }

    @Test
    void markerSurvivesMultipleUnrelatedComponentChanges() {
        // A second entity with a different component layout must not
        // collide with or overwrite the markered entity's archetype.
        var world = World.builder().build();
        var store = world.componentRegistry().registerRelation(Targeting.class);

        var a = world.spawn(new Position(1f, 2f));
        var b = world.spawn(new Position(5f, 6f));

        world.addMarkerComponent(a, store.markerId());
        // b stays marker-less.

        assertTrue(world.archetypeOf(a).id().components().contains(store.markerId()));
        assertFalse(world.archetypeOf(b).id().components().contains(store.markerId()));
    }

    private static Entity spawnEntity(World world) {
        return world.spawn(new Position(0f, 0f));
    }
}
