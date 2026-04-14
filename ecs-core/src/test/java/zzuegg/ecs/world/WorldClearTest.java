package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

class WorldClearTest {

    @Persistent record Position(float x, float y) {}
    @Persistent record Health(int hp) {}

    @Test
    void clearRemovesAllEntities() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));
        world.spawn(new Position(3, 4));
        assertEquals(2, world.entityCount());

        world.clear();
        assertEquals(0, world.entityCount());
    }

    @Test
    void clearAllowsSpawningAfterwards() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));
        world.clear();

        var e = world.spawn(new Position(5, 6));
        assertEquals(1, world.entityCount());
        var pos = world.getComponent(e, Position.class);
        assertEquals(5f, pos.x());
    }

    @Test
    void clearHandlesMultipleArchetypes() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));
        world.spawn(new Position(1, 2), new Health(100));
        world.spawn(new Health(50));
        assertEquals(3, world.entityCount());

        world.clear();
        assertEquals(0, world.entityCount());
    }

    @Test
    void clearIsIdempotent() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));
        world.clear();
        world.clear(); // should not throw
        assertEquals(0, world.entityCount());
    }

    @Test
    void oldEntitiesAreNotAliveAfterClear() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2));
        world.clear();
        assertFalse(world.isAlive(e));
    }
}
