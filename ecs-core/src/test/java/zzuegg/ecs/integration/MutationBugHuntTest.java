package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.SystemSet;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial integration tests targeting World mutation operations:
 * spawn, despawn, addComponent, removeComponent, setComponent, Commands.
 *
 * Each test is a full-cycle scenario with tick(). The goal is to find
 * bugs in ordering, edge cases, and deferred-command semantics.
 */
class MutationBugHuntTest {

    // ---- Component types ----
    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Health(int hp) {}
    record Tag(String label) {}
    record Marker(int id) {}
    record Targeting(int power) {}
    record Score(int value) {}

    // =========================================================================
    // 1. Spawn entity, add component via Commands, tick, verify component exists
    // =========================================================================

    static class AddViaCommands {
        @System
        void addVelocity(@Read Position pos, Entity self, Commands cmds) {
            cmds.add(self, new Velocity(pos.x(), pos.y()));
        }
    }

    @Test
    void spawnThenAddComponentViaCommands() {
        var world = World.builder().addSystem(AddViaCommands.class).build();
        var entity = world.spawn(new Position(3, 4));

        assertFalse(world.hasComponent(entity, Velocity.class),
            "Velocity must not exist before tick");

        world.tick();

        assertTrue(world.hasComponent(entity, Velocity.class),
            "Velocity must exist after tick flushes commands");
        assertEquals(new Velocity(3, 4), world.getComponent(entity, Velocity.class));
    }

    // =========================================================================
    // 2. Despawn via Commands, tick, spawn new entity -- fresh ID
    // =========================================================================

    @Test
    void despawnViaCommandsThenSpawnGetsFreshId() {
        var world = World.builder().build();

        var e1 = world.spawn(new Position(1, 1));
        long oldId = e1.id();

        var cmds = new Commands();
        cmds.despawn(e1);
        cmds.applyTo(world);

        assertFalse(world.isAlive(e1), "Entity must be dead after despawn");

        var e2 = world.spawn(new Position(2, 2));

        // The new entity may reuse the same index but must have a different
        // generation (i.e., a different id() overall). This catches stale-
        // reference bugs where old handles alias new entities.
        assertNotEquals(oldId, e2.id(),
            "New entity must have a different id (different generation)");
        assertTrue(world.isAlive(e2));
        assertEquals(new Position(2, 2), world.getComponent(e2, Position.class));
    }

    // =========================================================================
    // 3. addComponent that entity already has -- should update, not duplicate
    // =========================================================================

    @Test
    void addComponentAlreadyPresentUpdatesInPlace() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));

        // Add Position again with different values
        world.addComponent(entity, new Position(99, 99));

        // Must overwrite, not create a second Position
        assertEquals(new Position(99, 99), world.getComponent(entity, Position.class));
        assertEquals(1, world.entityCount(), "Entity count must stay the same");
    }

    // =========================================================================
    // 4. removeComponent that entity doesn't have -- should be no-op
    // =========================================================================

    @Test
    void removeComponentEntityDoesNotHaveIsNoOp() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));

        // Entity has Position but not Velocity. Removing Velocity must not throw.
        assertDoesNotThrow(() -> world.removeComponent(entity, Velocity.class),
            "Removing absent component must be a no-op");

        // Original component and entity must be intact
        assertTrue(world.isAlive(entity));
        assertEquals(new Position(1, 1), world.getComponent(entity, Position.class));
    }

    // =========================================================================
    // 5. setComponent on a despawned entity -- should throw or no-op
    // =========================================================================

    @Test
    void setComponentOnDespawnedEntityThrows() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));
        world.despawn(entity);

        assertThrows(IllegalArgumentException.class,
            () -> world.setComponent(entity, new Position(5, 5)),
            "setComponent on a dead entity must throw");
    }

    @Test
    void commandsSetOnDespawnedEntityIsNoOp() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));
        world.despawn(entity);

        // Through Commands, the command processor guards with isAlive
        var cmds = new Commands();
        cmds.set(entity, new Position(5, 5));

        assertDoesNotThrow(() -> cmds.applyTo(world),
            "Commands.set on dead entity must silently skip");
        assertEquals(0, world.entityCount());
    }

    // =========================================================================
    // 6. Commands.spawn + Commands.despawn same entity in same tick
    // =========================================================================

    static class SpawnAndDespawnSameTick {
        final List<Entity> spawned = new ArrayList<>();

        @System
        void trigger(@Read Marker m, Entity self, Commands cmds) {
            // Spawn a new entity and immediately despawn self
            cmds.spawn(new Tag("spawned-by-" + m.id()));
            cmds.despawn(self);
        }
    }

    @Test
    void spawnAndDespawnInSameCommandBatch() {
        var sys = new SpawnAndDespawnSameTick();
        var world = World.builder().addSystem(sys).build();

        world.spawn(new Marker(1));
        world.spawn(new Marker(2));
        assertEquals(2, world.entityCount());

        world.tick();

        // Both markers despawned, 2 Tags spawned
        assertEquals(2, world.entityCount(),
            "Old entities despawned, new entities spawned in same flush");
    }

    @Test
    void despawnThenSpawnSameBufferOrderMatters() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(0, 0));

        var cmds = new Commands();
        cmds.despawn(entity);
        cmds.spawn(new Position(99, 99));
        cmds.applyTo(world);

        // The despawn fires first, then the spawn. We should have exactly 1 entity.
        assertEquals(1, world.entityCount());
        assertFalse(world.isAlive(entity), "Original entity must be dead");
    }

    // =========================================================================
    // 7. Spawn 10k, despawn all, spawn 10k more -- verify count & no stale refs
    // =========================================================================

    @Test
    void bulkSpawnDespawnRespawnNoStaleReferences() {
        var world = World.builder().build();

        // Phase 1: spawn 10k
        var firstBatch = new ArrayList<Entity>(10_000);
        for (int i = 0; i < 10_000; i++) {
            firstBatch.add(world.spawn(new Position(i, 0)));
        }
        assertEquals(10_000, world.entityCount());

        // Phase 2: despawn all via commands (batched)
        var cmds = new Commands();
        for (var e : firstBatch) {
            cmds.despawn(e);
        }
        cmds.applyTo(world);
        assertEquals(0, world.entityCount());

        // All old handles must be dead
        for (var e : firstBatch) {
            assertFalse(world.isAlive(e));
        }

        // Phase 3: spawn 10k more
        var secondBatch = new ArrayList<Entity>(10_000);
        for (int i = 0; i < 10_000; i++) {
            secondBatch.add(world.spawn(new Position(i, 1)));
        }
        assertEquals(10_000, world.entityCount());

        // No new entity should collide with any old handle
        var oldIds = new HashSet<Long>();
        for (var e : firstBatch) oldIds.add(e.id());
        for (var e : secondBatch) {
            assertFalse(oldIds.contains(e.id()),
                "New entity must not reuse stale entity id: " + e);
            assertTrue(world.isAlive(e));
        }
    }

    // =========================================================================
    // 8. addComponent causing archetype migration while system is iterating
    // =========================================================================

    static class MigratorSystem {
        final List<Entity> migrated = new ArrayList<>();

        @System
        void migrate(@Read Position pos, Entity self, Commands cmds) {
            // Add a new component, triggering an archetype migration at flush
            cmds.add(self, new Velocity(pos.x() * 2, pos.y() * 2));
            migrated.add(self);
        }
    }

    static class PostMigrationReader {
        final List<Entity> seen = new ArrayList<>();

        @System
        void read(@Read Position pos, @Read Velocity vel, Entity self) {
            seen.add(self);
        }
    }

    @Test
    void archetypeMigrationViaCommandsDoesNotCorruptIteration() {
        var migrator = new MigratorSystem();
        var reader = new PostMigrationReader();
        var world = World.builder()
            .addSystem(migrator)
            .addSystem(reader)
            .build();

        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Position(3, 4));
        var e3 = world.spawn(new Position(5, 6));

        // Tick 1: migrator adds Velocity via commands. reader should not see
        // them yet (they don't have Velocity before flush).
        world.tick();

        assertEquals(3, migrator.migrated.size(),
            "Migrator must process all 3 entities");

        // After tick, all entities should have both Position and Velocity
        for (var e : List.of(e1, e2, e3)) {
            assertTrue(world.hasComponent(e, Velocity.class),
                "Entity must have Velocity after migration: " + e);
            assertTrue(world.hasComponent(e, Position.class),
                "Entity must still have Position after migration: " + e);
        }

        assertEquals(new Velocity(2, 4), world.getComponent(e1, Velocity.class));
        assertEquals(new Velocity(6, 8), world.getComponent(e2, Velocity.class));
        assertEquals(new Velocity(10, 12), world.getComponent(e3, Velocity.class));

        // Tick 2: now the reader should see all 3 entities (they have both components)
        world.tick();

        assertEquals(3, reader.seen.size(),
            "Reader must see all 3 entities with Position+Velocity after migration");
    }

    // =========================================================================
    // 9. Multiple systems writing to the same component type
    // =========================================================================

    @SystemSet(name = "writers", stage = "Update")
    static class WriterA {
        @System
        void writeA(@Write Mut<Score> score) {
            score.set(new Score(score.get().value() + 10));
        }
    }

    @SystemSet(name = "writers2", stage = "Update")
    static class WriterB {
        @System(after = "writeA")
        void writeB(@Write Mut<Score> score) {
            score.set(new Score(score.get().value() + 100));
        }
    }

    @Test
    void multipleSystemsWritingSameComponentOrderedByAfter() {
        var world = World.builder()
            .addSystem(WriterA.class)
            .addSystem(WriterB.class)
            .build();

        var entity = world.spawn(new Score(0));

        world.tick();

        // WriterA runs first (+10), WriterB runs second (+100) = 110
        assertEquals(new Score(110), world.getComponent(entity, Score.class),
            "Ordered writes must compose: 0 + 10 + 100 = 110");
    }

    // =========================================================================
    // 10. Commands.setRelation + Commands.removeRelation in same tick
    // =========================================================================

    @Test
    void setRelationThenRemoveRelationInSameCommandBatch() {
        var world = World.builder().build();
        var source = world.spawn(new Position(0, 0));
        var target = world.spawn(new Position(1, 1));

        // Set and then remove in the same command batch
        var cmds = new Commands();
        cmds.setRelation(source, target, new Targeting(42));
        cmds.removeRelation(source, target, Targeting.class);
        cmds.applyTo(world);

        // The remove comes after the set, so the relation should be gone
        assertTrue(world.getRelation(source, target, Targeting.class).isEmpty(),
            "Relation must be gone after set+remove in same batch");
    }

    @Test
    void removeRelationThenSetRelationInSameCommandBatch() {
        var world = World.builder().build();
        var source = world.spawn(new Position(0, 0));
        var target = world.spawn(new Position(1, 1));

        // Pre-set a relation
        world.setRelation(source, target, new Targeting(1));

        // Now remove then set in the same batch
        var cmds = new Commands();
        cmds.removeRelation(source, target, Targeting.class);
        cmds.setRelation(source, target, new Targeting(99));
        cmds.applyTo(world);

        // The set comes after the remove, so the relation should exist with new value
        assertEquals(new Targeting(99),
            world.getRelation(source, target, Targeting.class).orElseThrow(),
            "Relation must have the new value after remove+set in same batch");
    }

    // =========================================================================
    // BONUS: Additional edge cases
    // =========================================================================

    /**
     * Double-despawn of the same entity in the same command batch. The second
     * despawn targets a dead entity and must not corrupt internal state.
     */
    @Test
    void doubleDespawnSameEntityInSameBatch() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));

        var cmds = new Commands();
        cmds.despawn(entity);
        cmds.despawn(entity);

        assertDoesNotThrow(() -> cmds.applyTo(world),
            "Double despawn in same batch must not throw");
        assertEquals(0, world.entityCount());
        assertFalse(world.isAlive(entity));
    }

    /**
     * addComponent on an entity that was just despawned in the same command
     * batch. The add targets a dead entity and must be silently skipped.
     */
    @Test
    void addComponentAfterDespawnInSameBatch() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1));

        var cmds = new Commands();
        cmds.despawn(entity);
        cmds.add(entity, new Velocity(5, 5));

        assertDoesNotThrow(() -> cmds.applyTo(world),
            "Add after despawn in same batch must not throw");
        assertEquals(0, world.entityCount());
    }

    /**
     * removeComponent on an entity that was just despawned in the same command
     * batch. Must be silently skipped.
     */
    @Test
    void removeComponentAfterDespawnInSameBatch() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 1), new Velocity(0, 0));

        var cmds = new Commands();
        cmds.despawn(entity);
        cmds.remove(entity, Velocity.class);

        assertDoesNotThrow(() -> cmds.applyTo(world),
            "Remove after despawn in same batch must not throw");
        assertEquals(0, world.entityCount());
    }

    /**
     * setComponent via Commands updates a value, then a second set in the same
     * batch overwrites it. The last write must win.
     */
    @Test
    void multipleSetComponentInSameBatchLastWriteWins() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(0, 0));

        var cmds = new Commands();
        cmds.set(entity, new Position(1, 1));
        cmds.set(entity, new Position(2, 2));
        cmds.set(entity, new Position(3, 3));
        cmds.applyTo(world);

        assertEquals(new Position(3, 3), world.getComponent(entity, Position.class),
            "Last set in same batch must win");
    }

    /**
     * Spawn with zero components: the entity should exist in the empty archetype.
     */
    @Test
    void spawnWithNoComponents() {
        var world = World.builder().build();
        var entity = world.spawn();

        assertTrue(world.isAlive(entity));
        assertEquals(1, world.entityCount());

        // Adding a component afterwards should work
        world.addComponent(entity, new Position(42, 42));
        assertEquals(new Position(42, 42), world.getComponent(entity, Position.class));
    }
}
