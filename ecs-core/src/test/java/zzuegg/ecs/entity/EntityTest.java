package zzuegg.ecs.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTest {

    @Test
    void entityPacksIndexAndGeneration() {
        var entity = Entity.of(42, 7);
        assertEquals(42, entity.index());
        assertEquals(7, entity.generation());
    }

    @Test
    void entityWithSameIndexAndGenerationAreEqual() {
        assertEquals(Entity.of(1, 1), Entity.of(1, 1));
    }

    @Test
    void entityWithDifferentGenerationAreNotEqual() {
        assertNotEquals(Entity.of(1, 1), Entity.of(1, 2));
    }

    @Test
    void entityWithDifferentIndexAreNotEqual() {
        assertNotEquals(Entity.of(1, 1), Entity.of(2, 1));
    }

    @Test
    void entityIdEncodesTo64Bits() {
        var entity = Entity.of(0xFFFFFFFF, 0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, entity.index());
        assertEquals(0xFFFFFFFF, entity.generation());
    }

    @Test
    void nullEntityHasSpecialValue() {
        var nullEntity = Entity.NULL;
        assertEquals(-1, nullEntity.index());
        assertEquals(0, nullEntity.generation());
    }

    @Test
    void entityToStringIsReadable() {
        var entity = Entity.of(42, 3);
        assertEquals("Entity(42v3)", entity.toString());
    }
}
