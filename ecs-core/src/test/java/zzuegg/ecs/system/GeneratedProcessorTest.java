package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedProcessorTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    static final List<Position> readPositions = Collections.synchronizedList(new ArrayList<>());

    static class ReadSystem {
        @zzuegg.ecs.system.System
        void read(@Read Position pos) {
            readPositions.add(pos);
        }
    }

    static class MoveSystem {
        @zzuegg.ecs.system.System
        void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void generatedProcessorProducesSameResultAsReflective() {
        // Reference: existing World (uses MethodHandle)
        var refWorld = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .build();
        var refEntity = refWorld.spawn(new Position(0, 0), new Velocity(10, 20));
        refWorld.tick();
        var refPos = refWorld.getComponent(refEntity, Position.class);

        // Test: World with generated processor
        var genWorld = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .useGeneratedProcessors(true)
            .build();
        var genEntity = genWorld.spawn(new Position(0, 0), new Velocity(10, 20));
        genWorld.tick();
        var genPos = genWorld.getComponent(genEntity, Position.class);

        assertEquals(refPos, genPos);
    }

    @Test
    void generatedProcessorReadOnly() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .useGeneratedProcessors(true)
            .build();
        world.spawn(new Position(1, 2));
        world.spawn(new Position(3, 4));
        world.tick();

        assertEquals(2, readPositions.size());
    }

    @Test
    void generatedProcessorMultipleTicks() {
        var world = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .useGeneratedProcessors(true)
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
