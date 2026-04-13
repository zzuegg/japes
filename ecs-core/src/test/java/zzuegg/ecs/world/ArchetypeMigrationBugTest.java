package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests targeting archetype migration edge cases: addComponent, removeComponent,
 * spawn, despawn, and SpawnBuilder interactions.
 */
class ArchetypeMigrationBugTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Health(int hp) {}
    record Mana(int mp) {}

    // ---------------------------------------------------------------
    // Bug 1: addComponent on entity that already has the component
    //
    // ArchetypeId.with() returns `this` when the component is already
    // present, so addComponent migrates the entity to the SAME archetype.
    // This creates a duplicate slot, then swap-removes the original,
    // leaving the entity's stored location pointing at an invalid slot
    // (past the chunk's count). A subsequent spawn into the same
    // archetype overwrites the stale slot, corrupting the entity's data.
    // ---------------------------------------------------------------

    @Test
    void addComponent_duplicateComponent_thenSpawnAnother_corruptsData() {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));

        // Add Position which e1 already has — triggers self-migration bug.
        world.addComponent(e1, new Position(99, 99));

        // Spawn a new entity into the same archetype. This occupies the
        // stale slot that e1's location still points to, overwriting e1's
        // data.
        var e2 = world.spawn(new Position(42, 42));

        // e1 must still see its own data, not e2's.
        var pos1 = world.getComponent(e1, Position.class);
        assertEquals(new Position(99, 99), pos1,
            "addComponent with duplicate corrupted e1's data after a subsequent spawn");

        // e2 must see its own data.
        assertEquals(new Position(42, 42), world.getComponent(e2, Position.class));
    }

    @Test
    void addComponent_duplicateComponent_multipleEntities_locationCorruption() {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1));
        var e2 = world.spawn(new Position(2, 2));
        var e3 = world.spawn(new Position(3, 3));

        // Duplicate-add on e1 while other entities share the archetype.
        world.addComponent(e1, new Position(99, 99));

        // All three entities must retain correct values.
        assertEquals(new Position(99, 99), world.getComponent(e1, Position.class),
            "e1 data wrong after duplicate addComponent");
        assertEquals(new Position(2, 2), world.getComponent(e2, Position.class),
            "e2 data corrupted by e1's duplicate addComponent");
        assertEquals(new Position(3, 3), world.getComponent(e3, Position.class),
            "e3 data corrupted by e1's duplicate addComponent");

        // Spawn a 4th entity; must not overwrite anyone.
        var e4 = world.spawn(new Position(4, 4));
        assertEquals(new Position(99, 99), world.getComponent(e1, Position.class),
            "e1 data corrupted after spawn following duplicate addComponent");
        assertEquals(new Position(4, 4), world.getComponent(e4, Position.class));
    }

    // ---------------------------------------------------------------
    // Bug 2: removeComponent on entity that doesn't have the component
    //
    // ArchetypeId.without() returns `this` when the component is absent,
    // so removeComponent migrates the entity to the SAME archetype —
    // same self-migration corruption as Bug 1.
    // ---------------------------------------------------------------

    @Test
    void removeComponent_missingComponent_thenSpawnAnother_corruptsData() {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));

        // Remove Health which e1 doesn't have — triggers self-migration bug.
        world.removeComponent(e1, Health.class);

        // Spawn another entity in the same archetype to overwrite stale slot.
        var e2 = world.spawn(new Position(42, 42));

        assertEquals(new Position(1, 2), world.getComponent(e1, Position.class),
            "removeComponent of absent component corrupted e1's data after subsequent spawn");
        assertEquals(new Position(42, 42), world.getComponent(e2, Position.class));
    }

    @Test
    void removeComponent_missingComponent_multipleEntities_noCorruption() {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1), new Velocity(10, 10));
        var e2 = world.spawn(new Position(2, 2), new Velocity(20, 20));

        // Remove Health (not present) from e1, then spawn to expose stale slot.
        world.removeComponent(e1, Health.class);
        var e3 = world.spawn(new Position(3, 3), new Velocity(30, 30));

        assertEquals(new Position(1, 1), world.getComponent(e1, Position.class));
        assertEquals(new Velocity(10, 10), world.getComponent(e1, Velocity.class));
        assertEquals(new Position(2, 2), world.getComponent(e2, Position.class));
        assertEquals(new Velocity(20, 20), world.getComponent(e2, Velocity.class));
        assertEquals(new Position(3, 3), world.getComponent(e3, Position.class));
    }
}
