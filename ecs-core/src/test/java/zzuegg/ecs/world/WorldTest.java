package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    static final List<Position> readPositions = Collections.synchronizedList(new ArrayList<>());

    static class ReadSystem {
        @System
        void read(@Read Position pos) {
            readPositions.add(pos);
        }
    }

    static class MoveSystem {
        @System
        void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void spawnAndQueryEntity() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        world.spawn(new Position(1, 2));
        world.tick();

        assertEquals(1, readPositions.size());
        assertEquals(new Position(1, 2), readPositions.getFirst());
    }

    @Test
    void systemModifiesComponents() {
        var world = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(10, 20));
        world.tick();

        assertEquals(new Position(10, 20), world.getComponent(entity, Position.class));
    }

    @Test
    void multipleEntitiesIterated() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));
        world.spawn(new Position(3, 3));
        world.tick();

        assertEquals(3, readPositions.size());
    }

    @Test
    void despawnEntity() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        var e1 = world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));
        world.despawn(e1);
        world.tick();

        assertEquals(1, readPositions.size());
        assertEquals(new Position(2, 2), readPositions.getFirst());
    }

    @Test
    void setResource() {
        var world = World.builder()
            .addResource(new DeltaTime(0f))
            .addSystem(MoveSystem.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(1, 1));
        world.setResource(new DeltaTime(2.0f));
        world.tick();

        assertEquals(new Position(2, 2), world.getComponent(entity, Position.class));
    }

    @Test
    void entityCountTracksAlive() {
        var world = World.builder().build();
        assertEquals(0, world.entityCount());
        var e = world.spawn(new Position(1, 1));
        assertEquals(1, world.entityCount());
        world.despawn(e);
        assertEquals(0, world.entityCount());
    }

    @Test
    void getComponentOnDespawnedEntityThrows() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(e, Position.class));
    }

    @Test
    void emptyWorldTickIsNoOp() {
        var world = World.builder().build();
        assertDoesNotThrow(world::tick);
    }

    @Test
    void zeroComponentEntity() {
        var world = World.builder().build();
        var entity = world.spawn();
        assertEquals(1, world.entityCount());
        assertDoesNotThrow(() -> world.despawn(entity));
    }

    @Test
    void multipleTicksAccumulate() {
        var world = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(1, 1));

        world.tick();
        world.tick();
        world.tick();

        var pos = world.getComponent(entity, Position.class);
        assertEquals(3f, pos.x(), 0.01f);
        assertEquals(3f, pos.y(), 0.01f);
    }
}
