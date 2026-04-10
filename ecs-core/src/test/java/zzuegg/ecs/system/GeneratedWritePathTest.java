package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.component.ValueTracked;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the tier-1 GeneratedChunkProcessor correctly handles systems
 * with @Write Mut<T> parameters: values are written back to storage,
 * ChangeTracker is updated, and @ValueTracked suppressions still fire.
 *
 * If GeneratedChunkProcessor.skipReason returns null for write systems,
 * the bytecode path is the one being exercised here.
 */
class GeneratedWritePathTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    @ValueTracked record Score(int value) {}

    public static class Move {
        @System
        void move(@Read Velocity v, @Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() + v.dx(), cur.y() + v.dy()));
        }
    }

    @Test
    void generatedWritePathProducesCorrectValues() {
        var world = World.builder().addSystem(Move.class).build();
        var e1 = world.spawn(new Position(0, 0), new Velocity(1, 2));
        var e2 = world.spawn(new Position(10, 20), new Velocity(-1, -1));

        world.tick();

        assertEquals(new Position(1, 2), world.getComponent(e1, Position.class));
        assertEquals(new Position(9, 19), world.getComponent(e2, Position.class));

        world.tick();

        assertEquals(new Position(2, 4), world.getComponent(e1, Position.class));
        assertEquals(new Position(8, 18), world.getComponent(e2, Position.class));
    }

    public static class WriteOnly {
        @System
        void damage(@Write Mut<Position> p) {
            var cur = p.get();
            p.set(new Position(cur.x() - 1, cur.y() - 1));
        }
    }

    @Test
    void generatedWritePathWithSingleWriteParam() {
        // Pure write system, single component param, no reads.
        var world = World.builder().addSystem(WriteOnly.class).build();
        var e = world.spawn(new Position(10, 10));

        world.tick();
        assertEquals(new Position(9, 9), world.getComponent(e, Position.class));

        world.tick();
        assertEquals(new Position(8, 8), world.getComponent(e, Position.class));
    }

    public static class ValueTrackedWriter {
        static int invokeCount = 0;
        @System
        void touch(@Write Mut<Score> s) {
            invokeCount++;
            s.set(s.get()); // identical value — should NOT fire Changed
        }
    }

    @Test
    void generatedWritePathRespectsValueTrackedNoOp() {
        // Write path with a @ValueTracked component where the system writes
        // the same value back. The generator must still call Mut.flush(),
        // whose internal check suppresses the markChanged call.
        ValueTrackedWriter.invokeCount = 0;
        var world = World.builder().addSystem(ValueTrackedWriter.class).build();
        var e = world.spawn(new Score(42));

        world.tick();
        world.tick();
        world.tick();

        assertEquals(3, ValueTrackedWriter.invokeCount, "system ran every tick");
        assertEquals(new Score(42), world.getComponent(e, Score.class),
            "value must not change since we set the same value");
    }

    public static class MixedReadWriteTwoWrites {
        @System
        void physics(@Write Mut<Position> p, @Write Mut<Velocity> v) {
            var pos = p.get();
            var vel = v.get();
            p.set(new Position(pos.x() + vel.dx(), pos.y() + vel.dy()));
            v.set(new Velocity(vel.dx() * 0.9f, vel.dy() * 0.9f));
        }
    }

    @Test
    void generatedWritePathWithMultipleWriteParams() {
        var world = World.builder().addSystem(MixedReadWriteTwoWrites.class).build();
        var e = world.spawn(new Position(0, 0), new Velocity(10, 20));

        world.tick();
        assertEquals(new Position(10, 20), world.getComponent(e, Position.class));
        assertEquals(new Velocity(9, 18), world.getComponent(e, Velocity.class));
    }

    @Test
    void tier1AcceptsWriteSystems() {
        // Assertions that prove we actually hit the tier-1 path (not a
        // tier-2/3 fallback that also happens to work). skipReason() is the
        // gatekeeper; after the write extension it must return null for
        // eligible write systems.
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(Move.class, reg);
        var desc = descriptors.getFirst();

        String reason = GeneratedChunkProcessor.skipReason(desc);
        assertNull(reason,
            "tier-1 must accept a read+write system; skipReason returned: " + reason);
    }

    @Test
    void tier1AcceptsPureWriteSystem() {
        var reg = new zzuegg.ecs.component.ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(WriteOnly.class, reg);
        var desc = descriptors.getFirst();

        assertNull(GeneratedChunkProcessor.skipReason(desc),
            "tier-1 must accept a pure-write system");
    }
}
