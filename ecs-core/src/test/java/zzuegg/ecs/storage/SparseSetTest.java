package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import static org.junit.jupiter.api.Assertions.*;

class SparseSetTest {

    record Marker() {}
    record Tag(String name) {}

    @Test
    void insertAndGet() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(5, 0);
        set.insert(entity, new Tag("enemy"));
        assertEquals(new Tag("enemy"), set.get(entity));
    }

    @Test
    void containsReturnsTrueAfterInsert() {
        var set = new SparseSet<Marker>();
        var entity = Entity.of(0, 0);
        assertFalse(set.contains(entity));
        set.insert(entity, new Marker());
        assertTrue(set.contains(entity));
    }

    @Test
    void removeWorks() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("a"));
        set.remove(entity);
        assertFalse(set.contains(entity));
    }

    @Test
    void removeSwapsDenseArray() {
        var set = new SparseSet<Tag>();
        var e0 = Entity.of(0, 0);
        var e1 = Entity.of(1, 0);
        var e2 = Entity.of(2, 0);
        set.insert(e0, new Tag("a"));
        set.insert(e1, new Tag("b"));
        set.insert(e2, new Tag("c"));

        set.remove(e0);

        assertEquals(2, set.size());
        assertFalse(set.contains(e0));
        assertTrue(set.contains(e1));
        assertTrue(set.contains(e2));
        assertEquals(new Tag("b"), set.get(e1));
        assertEquals(new Tag("c"), set.get(e2));
    }

    @Test
    void getNonExistentReturnsNull() {
        var set = new SparseSet<Tag>();
        assertNull(set.get(Entity.of(999, 0)));
    }

    @Test
    void doubleInsertOverwrites() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("old"));
        set.insert(entity, new Tag("new"));
        assertEquals(new Tag("new"), set.get(entity));
        assertEquals(1, set.size());
    }

    @Test
    void doubleRemoveIsNoOp() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("a"));
        set.remove(entity);
        set.remove(entity);
        assertEquals(0, set.size());
    }

    @Test
    void largeEntityIndex() {
        var set = new SparseSet<Marker>();
        var entity = Entity.of(100_000, 0);
        set.insert(entity, new Marker());
        assertTrue(set.contains(entity));
    }

    @Test
    void sizeTracksInsertAndRemove() {
        var set = new SparseSet<Marker>();
        assertEquals(0, set.size());
        set.insert(Entity.of(0, 0), new Marker());
        set.insert(Entity.of(1, 0), new Marker());
        assertEquals(2, set.size());
        set.remove(Entity.of(0, 0));
        assertEquals(1, set.size());
    }
}
