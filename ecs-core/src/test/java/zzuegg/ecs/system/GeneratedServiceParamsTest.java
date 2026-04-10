package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies tier-1 GeneratedChunkProcessor handles service-style parameters
 * alongside @Read/@Write component params: Res, ResMut, Commands, Entity,
 * and combinations.
 */
class GeneratedServiceParamsTest {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}
    record Health(int hp) {}
    record DeltaTime(float dt) {}
    record FrameCount(long value) {}

    public static class IntegrateWithRes {
        @System
        void integrate(@Read Velocity v, @Write Mut<Position> p, Res<DeltaTime> dt) {
            float d = dt.get().dt();
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx() * d, cur.y() + v.dy() * d, cur.z() + v.dz() * d));
        }
    }

    @Test
    void tier1AcceptsReadWriteWithRes() {
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Velocity.class);
        reg.register(Position.class);
        var desc = SystemParser.parse(IntegrateWithRes.class, reg).getFirst();
        assertNull(GeneratedChunkProcessor.skipReason(desc));
    }

    @Test
    void generatedPathWithResProducesCorrectValues() {
        var world = World.builder()
            .addResource(new DeltaTime(0.5f))
            .addSystem(IntegrateWithRes.class)
            .build();
        var e = world.spawn(new Position(0, 0, 0), new Velocity(10, 20, 30));

        world.tick();
        assertEquals(new Position(5, 10, 15), world.getComponent(e, Position.class));

        world.tick();
        assertEquals(new Position(10, 20, 30), world.getComponent(e, Position.class));
    }

    public static class CountFrames {
        @System
        void tick(@Read Position p, ResMut<FrameCount> fc) {
            fc.set(new FrameCount(fc.get().value() + 1));
        }
    }

    @Test
    void tier1AcceptsResMut() {
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Position.class);
        var desc = SystemParser.parse(CountFrames.class, reg).getFirst();
        assertNull(GeneratedChunkProcessor.skipReason(desc));
    }

    public static class Reaper {
        @System
        void reap(@Read Health h, Entity self, Commands cmds) {
            if (h.hp() <= 0) cmds.despawn(self);
        }
    }

    @Test
    void tier1AcceptsEntityAndCommands() {
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Health.class);
        var desc = SystemParser.parse(Reaper.class, reg).getFirst();
        assertNull(GeneratedChunkProcessor.skipReason(desc));
    }

    @Test
    void generatedPathReaperDespawnsDeadEntities() {
        var world = World.builder().addSystem(Reaper.class).build();
        var alive = world.spawn(new Health(10));
        var dead = world.spawn(new Health(0));
        var alsoAlive = world.spawn(new Health(5));

        world.tick();

        assertTrue(world.isAlive(alive));
        assertFalse(world.isAlive(dead));
        assertTrue(world.isAlive(alsoAlive));
    }

    public static class FullyLoaded {
        static int invocations;
        @System
        void everything(@Read Velocity v, @Write Mut<Position> p,
                        Entity self, Res<DeltaTime> dt) {
            invocations++;
            float d = dt.get().dt();
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx() * d, cur.y() + v.dy() * d, cur.z() + v.dz() * d));
        }
    }

    @Test
    void tier1AcceptsReadWriteEntityRes() {
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Velocity.class);
        reg.register(Position.class);
        var desc = SystemParser.parse(FullyLoaded.class, reg).getFirst();
        assertNull(GeneratedChunkProcessor.skipReason(desc),
            "tier-1 must accept a mixed read/write/entity/resource system");
    }

    @Test
    void generatedPathWithFourParamsRuns() {
        FullyLoaded.invocations = 0;
        var world = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(FullyLoaded.class)
            .build();
        world.spawn(new Position(0, 0, 0), new Velocity(1, 2, 3));
        world.spawn(new Position(10, 10, 10), new Velocity(-1, -2, -3));

        world.tick();

        assertEquals(2, FullyLoaded.invocations);
    }
}
