package zzuegg.ecs.persistence.h2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class H2PersistencePluginTest {

    // ----------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------

    @Persistent public record Position(float x, float y) {}
    @Persistent public record Health(int hp) {}
    @Persistent public record AllPrimitives(byte b, short s, int i, long l, float f, double d, boolean flag, char c) {}
    public record Transient(int value) {}  // NOT persistent

    private World world;
    private H2PersistencePlugin plugin;
    private static int dbCounter;

    @BeforeEach
    void setUp() {
        world = World.builder().build();
        // Use a unique in-memory DB per test to avoid cross-contamination
        plugin = H2PersistencePlugin.create(world, "jdbc:h2:mem:test" + (dbCounter++) + ";DB_CLOSE_DELAY=-1");
    }

    @AfterEach
    void tearDown() {
        plugin.close();
        world.close();
    }

    // ----------------------------------------------------------------
    // saveAll / loadAll round-trip
    // ----------------------------------------------------------------

    @Test
    void saveAndLoadRoundTrip() {
        var e1 = world.spawn(new Position(1.5f, 2.5f), new Health(100));
        var e2 = world.spawn(new Position(3.0f, 4.0f));
        world.tick();

        plugin.saveAll();

        // Clear and reload
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(1.5f, 2.5f), accessor.getComponent(e1, Position.class));
        assertEquals(new Health(100), accessor.getComponent(e1, Health.class));
        assertEquals(new Position(3.0f, 4.0f), accessor.getComponent(e2, Position.class));
    }

    @Test
    void entityIdsArePreserved() {
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Position(3, 4), new Health(50));
        world.tick();

        long id1 = e1.id();
        long id2 = e2.id();

        plugin.saveAll();
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        // The exact entity IDs must be preserved
        assertEquals(new Position(1, 2), accessor.getComponent(Entity.of(e1.index(), e1.generation()), Position.class));
        assertEquals(new Position(3, 4), accessor.getComponent(Entity.of(e2.index(), e2.generation()), Position.class));
        assertEquals(new Health(50), accessor.getComponent(Entity.of(e2.index(), e2.generation()), Health.class));
    }

    @Test
    void nonPersistentComponentsAreExcluded() {
        var e = world.spawn(new Position(1, 2), new Transient(42));
        world.tick();

        plugin.saveAll();
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        // Position is persistent and should be restored
        assertEquals(new Position(1, 2), accessor.getComponent(e, Position.class));
        // Transient should NOT be restored
        assertNull(accessor.getComponent(e, Transient.class));
    }

    @Test
    void allPrimitiveTypesRoundTrip() {
        var comp = new AllPrimitives((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, true, 'Z');
        var e = world.spawn(comp);
        world.tick();

        plugin.saveAll();
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(comp, accessor.getComponent(e, AllPrimitives.class));
    }

    // ----------------------------------------------------------------
    // Multiple archetypes
    // ----------------------------------------------------------------

    @Test
    void multipleArchetypesRoundTrip() {
        // Three different archetypes
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(100));
        var e3 = world.spawn(new Position(3, 4), new Health(50));
        world.tick();

        plugin.saveAll();
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(1, 2), accessor.getComponent(e1, Position.class));
        assertNull(accessor.getComponent(e1, Health.class));

        assertNull(accessor.getComponent(e2, Position.class));
        assertEquals(new Health(100), accessor.getComponent(e2, Health.class));

        assertEquals(new Position(3, 4), accessor.getComponent(e3, Position.class));
        assertEquals(new Health(50), accessor.getComponent(e3, Health.class));
    }

    // ----------------------------------------------------------------
    // Delta sync
    // ----------------------------------------------------------------

    @Test
    void syncChangedOnlyWritesModifiedEntities() throws SQLException {
        var e1 = world.spawn(new Position(1, 2), new Health(100));
        var e2 = world.spawn(new Position(3, 4));
        world.tick();

        // Initial full save
        plugin.saveAll();

        // Mutate only e1's position
        world.setComponent(e1, new Position(5, 6));
        world.tick();

        plugin.syncChanged();

        // Verify the change was persisted
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(5, 6), accessor.getComponent(e1, Position.class));
        assertEquals(new Health(100), accessor.getComponent(e1, Health.class));
        assertEquals(new Position(3, 4), accessor.getComponent(e2, Position.class));
    }

    @Test
    void syncChangedHandlesNewEntities() {
        var e1 = world.spawn(new Position(1, 2));
        world.tick();
        plugin.saveAll();

        // Spawn a new entity after the initial save
        var e2 = world.spawn(new Position(7, 8));
        world.tick();
        plugin.syncChanged();

        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(1, 2), accessor.getComponent(e1, Position.class));
        assertEquals(new Position(7, 8), accessor.getComponent(e2, Position.class));
    }

    @Test
    void syncChangedHandlesDespawnedEntities() {
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Position(3, 4));
        world.tick();
        plugin.saveAll();

        world.despawn(e1);
        world.tick();
        plugin.syncChanged();

        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertNull(accessor.getComponent(e1, Position.class));
        assertEquals(new Position(3, 4), accessor.getComponent(e2, Position.class));
    }

    @Test
    void syncChangedNoChangesIsNoOp() {
        var e = world.spawn(new Position(1, 2));
        world.tick();
        plugin.saveAll();

        // No changes - sync should be a no-op but not break anything
        plugin.syncChanged();

        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(1, 2), accessor.getComponent(e, Position.class));
    }

    // ----------------------------------------------------------------
    // Entities with only non-persistent components are excluded
    // ----------------------------------------------------------------

    @Test
    void entitiesWithOnlyTransientComponentsAreNotSaved() {
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Transient(42)); // only transient
        world.tick();

        plugin.saveAll();
        world.clear();
        plugin.loadAll();

        var accessor = world.accessor();
        assertEquals(new Position(1, 2), accessor.getComponent(e1, Position.class));
        // e2 should not exist at all after load
        var allEntities = new ArrayList<Entity>();
        accessor.allEntities(allEntities::add);
        assertEquals(1, allEntities.size());
    }
}
