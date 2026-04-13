package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordFlattenerTest {

    record Flat(float x, float y) {}
    record Inner(float a, float b) {}
    record Outer(Inner i1, Inner i2, int extra) {}
    record Position(float x, float y, float z) {}
    record Rotation(float x, float y, float z, float w) {}
    record Transform(Position pos, Rotation rot) {}
    record Deep(Inner inner) {}
    record Deeper(Deep deep, float z) {}
    record NotEligible(String name) {}
    record MixedBad(float x, String s) {}

    @Test
    void flatRecordIsEligible() {
        assertTrue(RecordFlattener.isEligible(Flat.class));
    }

    @Test
    void nestedRecordIsEligible() {
        assertTrue(RecordFlattener.isEligible(Outer.class));
        assertTrue(RecordFlattener.isEligible(Transform.class));
    }

    @Test
    void deeplyNestedIsEligible() {
        assertTrue(RecordFlattener.isEligible(Deeper.class));
    }

    @Test
    void nonPrimitiveNotEligible() {
        assertFalse(RecordFlattener.isEligible(NotEligible.class));
        assertFalse(RecordFlattener.isEligible(MixedBad.class));
    }

    @Test
    void flatRecordFlattensToSelf() {
        var fields = RecordFlattener.flatten(Flat.class);
        assertEquals(2, fields.size());
        assertEquals("x", fields.get(0).flatName());
        assertEquals(float.class, fields.get(0).type());
        assertEquals(1, fields.get(0).accessors().size());
        assertEquals("y", fields.get(1).flatName());
    }

    @Test
    void nestedRecordFlattensCorrectly() {
        var fields = RecordFlattener.flatten(Outer.class);
        assertEquals(5, fields.size());
        assertEquals("i1_a", fields.get(0).flatName());
        assertEquals(float.class, fields.get(0).type());
        assertEquals(2, fields.get(0).accessors().size()); // i1 -> a
        assertEquals("i1_b", fields.get(1).flatName());
        assertEquals("i2_a", fields.get(2).flatName());
        assertEquals("i2_b", fields.get(3).flatName());
        assertEquals("extra", fields.get(4).flatName());
        assertEquals(int.class, fields.get(4).type());
        assertEquals(1, fields.get(4).accessors().size()); // direct
    }

    @Test
    void transformFlattensToSevenFields() {
        var fields = RecordFlattener.flatten(Transform.class);
        assertEquals(7, fields.size());
        assertEquals("pos_x", fields.get(0).flatName());
        assertEquals("pos_y", fields.get(1).flatName());
        assertEquals("pos_z", fields.get(2).flatName());
        assertEquals("rot_x", fields.get(3).flatName());
        assertEquals("rot_y", fields.get(4).flatName());
        assertEquals("rot_z", fields.get(5).flatName());
        assertEquals("rot_w", fields.get(6).flatName());
    }

    @Test
    void deeplyNestedFlattensCorrectly() {
        var fields = RecordFlattener.flatten(Deeper.class);
        assertEquals(3, fields.size());
        assertEquals("deep_inner_a", fields.get(0).flatName());
        assertEquals(3, fields.get(0).accessors().size()); // deep -> inner -> a
        assertEquals("deep_inner_b", fields.get(1).flatName());
        assertEquals("z", fields.get(2).flatName());
        assertEquals(1, fields.get(2).accessors().size());
    }

    @Test
    void hasNestedRecords() {
        assertFalse(RecordFlattener.hasNestedRecords(Flat.class));
        assertTrue(RecordFlattener.hasNestedRecords(Outer.class));
        assertTrue(RecordFlattener.hasNestedRecords(Transform.class));
    }
}
