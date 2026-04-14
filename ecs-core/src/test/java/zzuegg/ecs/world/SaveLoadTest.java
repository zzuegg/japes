package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.GroupedWorldSerializer;
import zzuegg.ecs.persistence.WorldSerializer;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SaveLoadTest {

    @Persistent record Position(float x, float y) {}
    @Persistent record Health(int hp) {}
    @Persistent record AllPrimitives(byte b, short s, int i, long l, float f, double d, boolean z, char c) {}
    record Transient(int value) {}
    @Persistent record Nested(Position pos, Health hp) {}

    @Test
    void saveAndLoadPreservesEntitiesAndComponents() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1.5f, 2.5f));
        var e2 = world.spawn(new Position(3, 4), new Health(100));

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(2, world2.entityCount());
        assertTrue(world2.isAlive(e1));
        assertTrue(world2.isAlive(e2));

        var pos1 = world2.getComponent(e1, Position.class);
        assertEquals(1.5f, pos1.x());
        assertEquals(2.5f, pos1.y());

        var pos2 = world2.getComponent(e2, Position.class);
        assertEquals(3f, pos2.x());
        var hp2 = world2.getComponent(e2, Health.class);
        assertEquals(100, hp2.hp());
    }

    @Test
    void saveOnlyPersistentComponents() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2), new Transient(42));

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertTrue(world2.isAlive(e));
        assertNotNull(world2.getComponent(e, Position.class));
        assertFalse(world2.hasComponent(e, Transient.class));
    }

    @Test
    void saveAllPrimitiveTypes() throws Exception {
        var world = World.builder().build();
        var orig = new AllPrimitives((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, true, 'A');
        var e = world.spawn(orig);

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        var loaded = world2.getComponent(e, AllPrimitives.class);
        assertEquals(orig, loaded);
    }

    @Test
    void saveLoadNestedRecords() throws Exception {
        var world = World.builder().build();
        var nested = new Nested(new Position(1, 2), new Health(100));
        var e = world.spawn(nested);

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        var loaded = world2.getComponent(e, Nested.class);
        assertEquals(nested, loaded);
    }

    @Test
    void loadClearsWorldFirst() throws Exception {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.spawn(new Position(99, 99)); // pre-existing entity
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        // Should only contain the saved entity, not the pre-existing one
        assertEquals(1, world2.entityCount());
    }

    @Test
    void saveLoadEmptyWorld() throws Exception {
        var world = World.builder().build();

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(0, world2.entityCount());
    }

    @Test
    void saveLoadMultipleArchetypes() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(50));
        var e3 = world.spawn(new Position(3, 4), new Health(100));

        var buf = new ByteArrayOutputStream();
        world.save(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(3, world2.entityCount());
        assertEquals(new Position(1, 2), world2.getComponent(e1, Position.class));
        assertFalse(world2.hasComponent(e1, Health.class));
        assertEquals(new Health(50), world2.getComponent(e2, Health.class));
        assertFalse(world2.hasComponent(e2, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e3, Position.class));
        assertEquals(new Health(100), world2.getComponent(e3, Health.class));
    }

    @Test
    void saveWithCustomFilter() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2), new Health(100));

        var buf = new ByteArrayOutputStream();
        // Only save Position, not Health
        world.save(new DataOutputStream(buf), type -> type == Position.class);

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertTrue(world2.isAlive(e));
        assertNotNull(world2.getComponent(e, Position.class));
        assertFalse(world2.hasComponent(e, Health.class));
    }

    // ---------------------------------------------------------------
    // Grouped (v2) format tests
    // ---------------------------------------------------------------

    @Test
    void saveGroupedAndLoadGroupedPreservesEntitiesAndComponents() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1.5f, 2.5f));
        var e2 = world.spawn(new Position(3, 4), new Health(100));

        var serializer = new WorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(2, world2.entityCount());
        assertTrue(world2.isAlive(e1));
        assertTrue(world2.isAlive(e2));
        assertEquals(new Position(1.5f, 2.5f), world2.getComponent(e1, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e2, Position.class));
        assertEquals(new Health(100), world2.getComponent(e2, Health.class));
    }

    @Test
    void saveGroupedMultipleArchetypes() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(50));
        var e3 = world.spawn(new Position(3, 4), new Health(100));

        var serializer = new WorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(3, world2.entityCount());
        assertEquals(new Position(1, 2), world2.getComponent(e1, Position.class));
        assertFalse(world2.hasComponent(e1, Health.class));
        assertEquals(new Health(50), world2.getComponent(e2, Health.class));
        assertFalse(world2.hasComponent(e2, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e3, Position.class));
        assertEquals(new Health(100), world2.getComponent(e3, Health.class));
    }

    @Test
    void saveGroupedOnlyPersistentComponents() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2), new Transient(42));

        var serializer = new WorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertTrue(world2.isAlive(e));
        assertNotNull(world2.getComponent(e, Position.class));
        assertFalse(world2.hasComponent(e, Transient.class));
    }

    @Test
    void saveGroupedAllPrimitiveTypes() throws Exception {
        var world = World.builder().build();
        var orig = new AllPrimitives((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, true, 'A');
        var e = world.spawn(orig);

        var serializer = new WorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(orig, world2.getComponent(e, AllPrimitives.class));
    }

    @Test
    void saveGroupedBulkEntities() throws Exception {
        var world = World.builder().build();
        var entities = new Entity[1000];
        for (int i = 0; i < 1000; i++) {
            entities[i] = world.spawn(new Position(i, i * 2), new Health(i * 10));
        }

        var serializer = new WorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(1000, world2.entityCount());
        for (int i = 0; i < 1000; i++) {
            assertEquals(new Position(i, i * 2), world2.getComponent(entities[i], Position.class));
            assertEquals(new Health(i * 10), world2.getComponent(entities[i], Health.class));
        }
    }

    // ---------------------------------------------------------------
    // Columnar (v3) format tests
    // ---------------------------------------------------------------

    @Test
    void columnarSaveAndLoadPreservesEntitiesAndComponents() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1.5f, 2.5f));
        var e2 = world.spawn(new Position(3, 4), new Health(100));

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(2, world2.entityCount());
        assertTrue(world2.isAlive(e1));
        assertTrue(world2.isAlive(e2));

        assertEquals(new Position(1.5f, 2.5f), world2.getComponent(e1, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e2, Position.class));
        assertEquals(new Health(100), world2.getComponent(e2, Health.class));
    }

    @Test
    void columnarSaveLoadMultipleArchetypes() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(50));
        var e3 = world.spawn(new Position(3, 4), new Health(100));

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(3, world2.entityCount());
        assertEquals(new Position(1, 2), world2.getComponent(e1, Position.class));
        assertFalse(world2.hasComponent(e1, Health.class));
        assertEquals(new Health(50), world2.getComponent(e2, Health.class));
        assertFalse(world2.hasComponent(e2, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e3, Position.class));
        assertEquals(new Health(100), world2.getComponent(e3, Health.class));
    }

    @Test
    void columnarSaveLoadAllPrimitiveTypes() throws Exception {
        var world = World.builder().build();
        var orig = new AllPrimitives((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, true, 'A');
        var e = world.spawn(orig);

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(orig, world2.getComponent(e, AllPrimitives.class));
    }

    @Test
    void columnarSaveLoadNestedRecords() throws Exception {
        var world = World.builder().build();
        var nested = new Nested(new Position(1, 2), new Health(100));
        var e = world.spawn(nested);

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(nested, world2.getComponent(e, Nested.class));
    }

    @Test
    void columnarSaveLoadManyEntitiesSpanningMultipleChunks() throws Exception {
        var world = World.builder().build();
        int count = 3000; // > 1024 chunk capacity = multiple chunks
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < count; i++) {
            entities.add(world.spawn(new Position(i, i * 2), new Health(i * 10)));
        }

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(count, world2.entityCount());
        for (int i = 0; i < count; i++) {
            assertEquals(new Position(i, i * 2), world2.getComponent(entities.get(i), Position.class));
            assertEquals(new Health(i * 10), world2.getComponent(entities.get(i), Health.class));
        }
    }

    @Test
    void columnarLoadClearsWorldFirst() throws Exception {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));

        var buf = new ByteArrayOutputStream();
        world.saveColumnar(new DataOutputStream(buf));

        var world2 = World.builder().build();
        world2.spawn(new Position(99, 99));
        world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(1, world2.entityCount());
    }

    // ---------------------------------------------------------------
    // Grouped format tests
    // ---------------------------------------------------------------

    @Test
    void groupedSaveLoadPreservesEntitiesAndComponents() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1.5f, 2.5f));
        var e2 = world.spawn(new Position(3, 4), new Health(100));

        var serializer = new GroupedWorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.save(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(2, world2.entityCount());
        assertTrue(world2.isAlive(e1));
        assertTrue(world2.isAlive(e2));
        assertEquals(new Position(1.5f, 2.5f), world2.getComponent(e1, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e2, Position.class));
        assertEquals(new Health(100), world2.getComponent(e2, Health.class));
    }

    @Test
    void groupedSaveLoadMultipleArchetypes() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(50));
        var e3 = world.spawn(new Position(3, 4), new Health(100));

        var serializer = new GroupedWorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.save(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(3, world2.entityCount());
        assertEquals(new Position(1, 2), world2.getComponent(e1, Position.class));
        assertFalse(world2.hasComponent(e1, Health.class));
        assertEquals(new Health(50), world2.getComponent(e2, Health.class));
        assertFalse(world2.hasComponent(e2, Position.class));
        assertEquals(new Position(3, 4), world2.getComponent(e3, Position.class));
        assertEquals(new Health(100), world2.getComponent(e3, Health.class));
    }

    @Test
    void groupedSaveLoadOnlyPersistentComponents() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2), new Transient(42));

        var serializer = new GroupedWorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.save(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertTrue(world2.isAlive(e));
        assertNotNull(world2.getComponent(e, Position.class));
        assertFalse(world2.hasComponent(e, Transient.class));
    }

    @Test
    void groupedSaveLoadAllPrimitiveTypes() throws Exception {
        var world = World.builder().build();
        var orig = new AllPrimitives((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, true, (char)65);
        var e = world.spawn(orig);

        var serializer = new GroupedWorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.save(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(orig, world2.getComponent(e, AllPrimitives.class));
    }

    @Test
    void groupedSaveLoadBulkEntities() throws Exception {
        var world = World.builder().build();
        var entities = new Entity[1000];
        for (int i = 0; i < 1000; i++) {
            entities[i] = world.spawn(new Position(i, i * 2), new Health(i * 10));
        }

        var serializer = new GroupedWorldSerializer();
        var buf = new ByteArrayOutputStream();
        serializer.save(world, new DataOutputStream(buf));

        var world2 = World.builder().build();
        serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));

        assertEquals(1000, world2.entityCount());
        for (int i = 0; i < 1000; i++) {
            assertEquals(new Position(i, i * 2), world2.getComponent(entities[i], Position.class));
            assertEquals(new Health(i * 10), world2.getComponent(entities[i], Health.class));
        }
    }
}
