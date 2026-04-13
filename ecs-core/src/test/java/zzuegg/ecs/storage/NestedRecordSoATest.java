package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SoA storage for nested record types.
 */
class NestedRecordSoATest {

    record Inner(float a, float b) {}
    record Outer(Inner i1, Inner i2, int extra) {}
    record Position(float x, float y, float z) {}
    record Rotation(float x, float y, float z, float w) {}
    record Transform(Position pos, Rotation rot) {}

    @Test
    void nestedRecordIsEligible() {
        assertTrue(SoAComponentStorage.isEligible(Outer.class));
        assertTrue(SoAComponentStorage.isEligible(Transform.class));
    }

    @Test
    void flatRecordStillWorks() {
        var storage = SoAComponentStorage.create(Inner.class, 16);
        storage.set(0, new Inner(1.0f, 2.0f));
        assertEquals(new Inner(1.0f, 2.0f), storage.get(0));
    }

    @Test
    void nestedRecordSetAndGet() {
        var storage = SoAComponentStorage.create(Outer.class, 16);
        var val = new Outer(new Inner(1.0f, 2.0f), new Inner(3.0f, 4.0f), 42);
        storage.set(0, val);
        assertEquals(val, storage.get(0));
    }

    @Test
    void transformSetAndGet() {
        var storage = SoAComponentStorage.create(Transform.class, 16);
        var val = new Transform(
            new Position(1.0f, 2.0f, 3.0f),
            new Rotation(0.0f, 0.0f, 0.707f, 0.707f)
        );
        storage.set(0, val);
        assertEquals(val, storage.get(0));
    }

    @Test
    void multipleSlots() {
        var storage = SoAComponentStorage.create(Transform.class, 16);
        var t1 = new Transform(new Position(1, 2, 3), new Rotation(0, 0, 0, 1));
        var t2 = new Transform(new Position(4, 5, 6), new Rotation(1, 0, 0, 0));
        storage.set(0, t1);
        storage.set(1, t2);
        assertEquals(t1, storage.get(0));
        assertEquals(t2, storage.get(1));
    }

    @Test
    void swapRemoveNested() {
        var storage = SoAComponentStorage.create(Outer.class, 16);
        var v0 = new Outer(new Inner(0, 0), new Inner(0, 0), 0);
        var v1 = new Outer(new Inner(1, 1), new Inner(1, 1), 1);
        var v2 = new Outer(new Inner(2, 2), new Inner(2, 2), 2);
        storage.set(0, v0);
        storage.set(1, v1);
        storage.set(2, v2);
        storage.swapRemove(0, 3);
        assertEquals(v2, storage.get(0));
    }

    @Test
    void copyIntoNested() {
        var src = SoAComponentStorage.create(Transform.class, 16);
        var dst = SoAComponentStorage.create(Transform.class, 16);
        var val = new Transform(new Position(7, 8, 9), new Rotation(0, 1, 0, 0));
        src.set(3, val);
        src.copyInto(3, dst, 0);
        assertEquals(val, dst.get(0));
    }

    @Test
    void soaFieldArraysReturnsCorrectCount() {
        var storage = SoAComponentStorage.create(Transform.class, 16);
        var arrays = storage.soaFieldArrays();
        assertNotNull(arrays);
        // Transform flattens to 7 fields: pos_x, pos_y, pos_z, rot_x, rot_y, rot_z, rot_w
        assertEquals(7, arrays.length);
        for (var arr : arrays) {
            assertTrue(arr instanceof float[]);
            assertEquals(16, ((float[]) arr).length);
        }
    }

    @Test
    void soaFieldArraysMixedTypes() {
        var storage = SoAComponentStorage.create(Outer.class, 8);
        var arrays = storage.soaFieldArrays();
        assertNotNull(arrays);
        // Outer flattens to 5 fields: i1_a(float), i1_b(float), i2_a(float), i2_b(float), extra(int)
        assertEquals(5, arrays.length);
        assertTrue(arrays[0] instanceof float[]);
        assertTrue(arrays[1] instanceof float[]);
        assertTrue(arrays[2] instanceof float[]);
        assertTrue(arrays[3] instanceof float[]);
        assertTrue(arrays[4] instanceof int[]);
    }

    @Test
    void capacityNested() {
        var storage = SoAComponentStorage.create(Transform.class, 1024);
        assertEquals(1024, storage.capacity());
    }

    @Test
    void factoryCreatesNestedSoA() {
        var factory = new SoAComponentStorage.SoAFactory();
        var storage = factory.create(Transform.class, 16);
        assertNotNull(storage.soaFieldArrays(), "should be SoA storage");
        var val = new Transform(new Position(1, 2, 3), new Rotation(0, 0, 0, 1));
        storage.set(0, val);
        assertEquals(val, storage.get(0));
    }
}
