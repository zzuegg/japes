package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ComponentReader} — specifically the
 * raw-long {@code getById} fast-path used by bulk pair scans
 * that already have packed entity ids and don't want to
 * re-wrap them in {@link Entity} records.
 */
class ComponentReaderTest {

    public record Position(float x, float y) {}

    public static class Systems {
        static final AtomicReference<ComponentReader<Position>> READER = new AtomicReference<>();

        @System
        @Exclusive
        public void capture(World world, ComponentReader<Position> reader) {
            READER.set(reader);
        }
    }

    @Test
    void getByIdReturnsSameValueAsGetEntity() {
        var world = World.builder().addSystem(Systems.class).build();
        var alice = world.spawn(new Position(1f, 2f));
        var bob   = world.spawn(new Position(3f, 4f));
        world.tick();

        var reader = Systems.READER.get();
        assertNotNull(reader, "capture system must have run and set the reader");

        assertEquals(reader.get(alice), reader.getById(alice.id()));
        assertEquals(reader.get(bob),   reader.getById(bob.id()));
        assertEquals(new Position(1f, 2f), reader.getById(alice.id()));
        assertEquals(new Position(3f, 4f), reader.getById(bob.id()));
    }

    @Test
    void getByIdReturnsNullForDespawnedEntity() {
        var world = World.builder().addSystem(Systems.class).build();
        var alice = world.spawn(new Position(1f, 2f));
        world.tick();

        var reader = Systems.READER.get();
        assertNotNull(reader);

        world.despawn(alice);
        // After despawn the entityLocations slot is cleared — getById
        // returns null rather than throwing, matching get(Entity).
        assertNull(reader.getById(alice.id()));
    }

    @Test
    void getByIdReturnsNullForNegativeOrOutOfRangeId() {
        var world = World.builder().addSystem(Systems.class).build();
        world.spawn(new Position(1f, 2f));
        world.tick();

        var reader = Systems.READER.get();
        assertNotNull(reader);

        // Pack index = -1 (same layout as Entity.id): sentinel for "no such entity".
        long bogus = ((long) -1 << 32);
        assertNull(reader.getById(bogus));
        long wayOutOfRange = ((long) 999_999 << 32);
        assertNull(reader.getById(wayOutOfRange));
    }
}
