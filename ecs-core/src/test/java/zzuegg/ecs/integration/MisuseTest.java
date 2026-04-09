package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MisuseTest {

    record Position(float x, float y) {}

    @Test
    void getComponentOnDespawnedEntity() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(e, Position.class));
    }

    @Test
    void despawnTwiceThrows() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.despawn(e));
    }

    @Test
    void staleEntityAfterRecycle() {
        var world = World.builder().build();
        var original = world.spawn(new Position(1, 1));
        world.despawn(original);
        world.spawn(new Position(2, 2));
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(original, Position.class));
    }

    @Test
    void emptyWorldTickIsNoOp() {
        var world = World.builder().build();
        assertDoesNotThrow(world::tick);
    }

    @Test
    void tickWithNoEntitiesNoOp() {
        var world = World.builder()
            .addSystem(ReadSystems.class)
            .build();
        assertDoesNotThrow(world::tick);
    }

    static class ReadSystems {
        @System
        void read(@Read Position pos) {}
    }

    @Test
    void zeroComponentEntity() {
        var world = World.builder().build();
        var entity = world.spawn();
        assertEquals(1, world.entityCount());
        assertDoesNotThrow(() -> world.despawn(entity));
    }
}
