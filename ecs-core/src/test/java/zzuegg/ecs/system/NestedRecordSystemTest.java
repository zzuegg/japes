package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for nested record support in the generated chunk processor
 * and SoA storage. Verifies that nested records like Transform(Position, Rotation)
 * work correctly through the full read/write/SoA pipeline.
 */
class NestedRecordSystemTest {

    record Vec2(float x, float y) {}
    record Vec3(float x, float y, float z) {}
    record Transform(Vec3 pos, Vec3 vel) {}
    record Inner(float a, float b) {}
    record Outer(Inner i1, Inner i2, int extra) {}

    // --- Read-only system with nested records ---

    static volatile Vec3 lastReadPos;
    static volatile Vec3 lastReadVel;

    public static class ReadTransformSystem {
        @System
        void read(@Read Transform t) {
            lastReadPos = t.pos();
            lastReadVel = t.vel();
        }
    }

    @Test
    void readNestedRecordSystem() {
        lastReadPos = null;
        lastReadVel = null;
        var world = World.builder().addSystem(ReadTransformSystem.class).build();
        world.spawn(new Transform(new Vec3(1, 2, 3), new Vec3(4, 5, 6)));
        world.tick();
        assertEquals(new Vec3(1, 2, 3), lastReadPos);
        assertEquals(new Vec3(4, 5, 6), lastReadVel);
    }

    // --- Write system with nested records ---

    public static class MoveSystem {
        @System
        void move(@Write Mut<Transform> t) {
            var cur = t.get();
            t.set(new Transform(
                new Vec3(cur.pos().x() + cur.vel().x(),
                         cur.pos().y() + cur.vel().y(),
                         cur.pos().z() + cur.vel().z()),
                cur.vel()));
        }
    }

    @Test
    void writeNestedRecordSystem() {
        var world = World.builder().addSystem(MoveSystem.class).build();
        var e = world.spawn(new Transform(new Vec3(0, 0, 0), new Vec3(1, 2, 3)));

        world.tick();
        assertEquals(new Transform(new Vec3(1, 2, 3), new Vec3(1, 2, 3)),
            world.getComponent(e, Transform.class));

        world.tick();
        assertEquals(new Transform(new Vec3(2, 4, 6), new Vec3(1, 2, 3)),
            world.getComponent(e, Transform.class));
    }

    // --- Mixed read + write with nested records ---

    public static class AccelerateSystem {
        @System
        void accel(@Read Vec2 accel, @Write Mut<Transform> t) {
            var cur = t.get();
            var newVel = new Vec3(
                cur.vel().x() + accel.x(),
                cur.vel().y() + accel.y(),
                cur.vel().z());
            t.set(new Transform(cur.pos(), newVel));
        }
    }

    @Test
    void mixedReadWriteNestedRecord() {
        var world = World.builder().addSystem(AccelerateSystem.class).build();
        var e = world.spawn(
            new Transform(new Vec3(0, 0, 0), new Vec3(1, 1, 1)),
            new Vec2(0.5f, -0.5f));

        world.tick();
        var t = world.getComponent(e, Transform.class);
        assertEquals(new Vec3(1.5f, 0.5f, 1.0f), t.vel());
    }

    // --- Multiple entities with nested records ---

    @Test
    void multipleEntitiesNestedRecord() {
        var world = World.builder().addSystem(MoveSystem.class).build();
        var e1 = world.spawn(new Transform(new Vec3(0, 0, 0), new Vec3(1, 0, 0)));
        var e2 = world.spawn(new Transform(new Vec3(10, 10, 10), new Vec3(0, -1, 0)));

        world.tick();
        assertEquals(new Transform(new Vec3(1, 0, 0), new Vec3(1, 0, 0)),
            world.getComponent(e1, Transform.class));
        assertEquals(new Transform(new Vec3(10, 9, 10), new Vec3(0, -1, 0)),
            world.getComponent(e2, Transform.class));
    }

    // --- Three-level nested: Outer(Inner, Inner, int) ---

    public static class OuterWriter {
        @System
        void update(@Write Mut<Outer> o) {
            var cur = o.get();
            o.set(new Outer(
                new Inner(cur.i1().a() + 1, cur.i1().b() + 2),
                new Inner(cur.i2().a() * 2, cur.i2().b() * 2),
                cur.extra() + 10));
        }
    }

    @Test
    void nestedRecordWithMixedTypes() {
        var world = World.builder().addSystem(OuterWriter.class).build();
        var e = world.spawn(new Outer(new Inner(1, 1), new Inner(1, 1), 0));

        world.tick();
        assertEquals(new Outer(new Inner(2, 3), new Inner(2, 2), 10),
            world.getComponent(e, Outer.class));

        world.tick();
        assertEquals(new Outer(new Inner(3, 5), new Inner(4, 4), 20),
            world.getComponent(e, Outer.class));
    }
}
