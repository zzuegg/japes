package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating a database auto-sync plugin built on
 * top of the persistence/accessor APIs.
 *
 * <p>The plugin is a regular {@code @Exclusive @System} that runs each tick,
 * reads the world through a {@link WorldAccessor}, and pushes only changed
 * persistent entities/components to an in-memory mock database.
 *
 * <p>Delta sync is implemented by snapshot comparison: each tick the system
 * reads the current persistent state and diffs it against what was last
 * written to the DB. Only actual changes (new entities, updated components,
 * removed entities) produce DB writes.
 */
class DbSyncTest {

    // ----------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------

    @Persistent record Position(float x, float y) {}
    @Persistent record Health(int hp) {}
    record Transient(int value) {}  // not persistent, must be ignored

    // ----------------------------------------------------------------
    // Mock database
    // ----------------------------------------------------------------

    /**
     * Simple in-memory "database" that stores a snapshot per entity.
     * Each entity row is a map from component class to its record value.
     */
    static final class MockDatabase {
        final Map<Entity, Map<Class<?>, Record>> rows = new ConcurrentHashMap<>();

        int upsertCount;
        int deleteCount;

        void upsert(Entity entity, Class<?> type, Record value) {
            rows.computeIfAbsent(entity, _ -> new ConcurrentHashMap<>()).put(type, value);
            upsertCount++;
        }

        void deleteEntity(Entity entity) {
            if (rows.remove(entity) != null) {
                deleteCount++;
            }
        }

        void deleteComponent(Entity entity, Class<?> type) {
            var row = rows.get(entity);
            if (row != null) {
                row.remove(type);
                if (row.isEmpty()) rows.remove(entity);
            }
        }

        Record get(Entity entity, Class<?> type) {
            var row = rows.get(entity);
            return row == null ? null : row.get(type);
        }

        boolean hasEntity(Entity entity) {
            return rows.containsKey(entity);
        }

        int entityCount() {
            return rows.size();
        }
    }

    // ----------------------------------------------------------------
    // DbSync system
    // ----------------------------------------------------------------

    /**
     * The sync system. Runs as an {@code @Exclusive} system so it has
     * access to {@code World} (needed for {@code accessor()}).
     * Compares current persistent state against a local snapshot to
     * compute the delta, then pushes only changes to the mock DB.
     */
    static class DbSyncSystem {

        /**
         * Local snapshot of what has been written to the DB.
         * Keyed by entity, value is a map of component type to its last-synced value.
         */
        final Map<Entity, Map<Class<?>, Record>> lastSynced = new HashMap<>();

        @zzuegg.ecs.system.System(stage = "Last")
        @Exclusive
        void sync(World world, Res<MockDatabase> dbRes) {
            var db = dbRes.get();
            var accessor = world.accessor();

            // Collect the current persistent state into a fresh snapshot.
            var currentSnapshot = new HashMap<Entity, Map<Class<?>, Record>>();
            for (var entity : accessor.persistentEntities()) {
                var components = new HashMap<Class<?>, Record>();
                for (var comp : accessor.persistentComponents(entity)) {
                    components.put(comp.getClass(), comp);
                }
                currentSnapshot.put(entity, components);
            }

            // --- Delta: new or updated entities/components ---
            for (var entry : currentSnapshot.entrySet()) {
                var entity = entry.getKey();
                var currentComps = entry.getValue();
                var prevComps = lastSynced.get(entity);

                for (var compEntry : currentComps.entrySet()) {
                    var type = compEntry.getKey();
                    var value = compEntry.getValue();

                    if (prevComps == null || !value.equals(prevComps.get(type))) {
                        db.upsert(entity, type, value);
                    }
                }

                // --- Delta: removed components on an existing entity ---
                if (prevComps != null) {
                    for (var type : prevComps.keySet()) {
                        if (!currentComps.containsKey(type)) {
                            db.deleteComponent(entity, type);
                        }
                    }
                }
            }

            // --- Delta: removed entities ---
            for (var entity : lastSynced.keySet()) {
                if (!currentSnapshot.containsKey(entity)) {
                    db.deleteEntity(entity);
                }
            }

            // Update the local snapshot for next tick's diff.
            lastSynced.clear();
            lastSynced.putAll(currentSnapshot);
        }
    }

    // ----------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------

    @Test
    void initialSpawnSyncsToDb() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        var e1 = world.spawn(new Position(1, 2), new Health(100));
        var e2 = world.spawn(new Position(3, 4));

        world.tick();

        // Both entities should be in the DB.
        assertEquals(2, db.entityCount());
        assertEquals(new Position(1, 2), db.get(e1, Position.class));
        assertEquals(new Health(100), db.get(e1, Health.class));
        assertEquals(new Position(3, 4), db.get(e2, Position.class));
    }

    @Test
    void transientComponentsAreNotSynced() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        var e = world.spawn(new Position(1, 2), new Transient(42));
        world.tick();

        // Position is persistent and should be synced.
        assertEquals(new Position(1, 2), db.get(e, Position.class));
        // Transient should NOT appear in the DB.
        assertNull(db.get(e, Transient.class));
    }

    @Test
    void deltaSyncOnlyPushesChanges() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        var e = world.spawn(new Position(1, 2), new Health(100));
        world.tick();

        int upsertsBefore = db.upsertCount;

        // Tick again without any mutations — delta sync should produce zero DB writes.
        world.tick();
        assertEquals(upsertsBefore, db.upsertCount,
                "no mutations means zero DB upserts on the second tick");

        // Now mutate only the position.
        world.setComponent(e, new Position(5, 6));
        world.tick();

        assertEquals(upsertsBefore + 1, db.upsertCount,
                "only the changed Position should be upserted");
        assertEquals(new Position(5, 6), db.get(e, Position.class));
        assertEquals(new Health(100), db.get(e, Health.class),
                "unchanged Health must remain in DB");
    }

    @Test
    void despawnedEntityIsRemovedFromDb() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        var e = world.spawn(new Position(1, 2));
        world.tick();
        assertTrue(db.hasEntity(e));

        world.despawn(e);
        world.tick();

        assertFalse(db.hasEntity(e),
                "despawned entity must be removed from DB");
        assertEquals(1, db.deleteCount);
    }

    @Test
    void multiTickMutationSequence() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        // Tick 1: spawn two entities.
        var e1 = world.spawn(new Position(0, 0), new Health(100));
        var e2 = world.spawn(new Position(10, 10));
        world.tick();
        assertEquals(2, db.entityCount());

        // Tick 2: mutate e1's position, spawn e3.
        world.setComponent(e1, new Position(1, 1));
        var e3 = world.spawn(new Health(50));
        world.tick();
        assertEquals(3, db.entityCount());
        assertEquals(new Position(1, 1), db.get(e1, Position.class));
        assertEquals(new Health(50), db.get(e3, Health.class));

        // Tick 3: despawn e2, mutate e3.
        world.despawn(e2);
        world.setComponent(e3, new Health(25));
        world.tick();
        assertEquals(2, db.entityCount());
        assertFalse(db.hasEntity(e2));
        assertEquals(new Health(25), db.get(e3, Health.class));

        // Tick 4: no changes — DB is stable.
        int upsertsBefore = db.upsertCount;
        int deletesBefore = db.deleteCount;
        world.tick();
        assertEquals(upsertsBefore, db.upsertCount, "idle tick: no upserts");
        assertEquals(deletesBefore, db.deleteCount, "idle tick: no deletes");
    }

    @Test
    void newEntityOnLaterTickIsSynced() {
        var db = new MockDatabase();
        var syncSystem = new DbSyncSystem();
        var world = World.builder()
                .addResource(db)
                .addSystem(syncSystem)
                .build();

        world.tick(); // empty tick
        assertEquals(0, db.entityCount());

        var e = world.spawn(new Position(7, 8));
        world.tick();
        assertEquals(1, db.entityCount());
        assertEquals(new Position(7, 8), db.get(e, Position.class));
    }
}
