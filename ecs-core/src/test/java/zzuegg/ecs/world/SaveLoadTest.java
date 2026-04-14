package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.entity.Entity;

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
}
