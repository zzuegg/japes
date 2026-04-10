package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    private static final ComponentId POS_ID = new ComponentId(0);
    private static final ComponentId VEL_ID = new ComponentId(1);

    private Chunk createChunk(int capacity) {
        return new Chunk(capacity, Map.of(
            POS_ID, Position.class,
            VEL_ID, Velocity.class
        ), ComponentStorage.defaultFactory(), Set.of());
    }

    @Test
    void addEntityReturnsSlotIndex() {
        var chunk = createChunk(16);
        int slot = chunk.add(Entity.of(0, 0));
        assertEquals(0, slot);
    }

    @Test
    void addMultipleEntities() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        int slot = chunk.add(Entity.of(1, 0));
        assertEquals(1, slot);
        assertEquals(2, chunk.count());
    }

    @Test
    void setAndGetComponent() {
        var chunk = createChunk(16);
        int slot = chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, slot, new Position(1, 2));
        assertEquals(new Position(1, 2), chunk.get(POS_ID, slot));
    }

    @Test
    void getEntityAtSlot() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(42, 3));
        assertEquals(Entity.of(42, 3), chunk.entity(0));
    }

    @Test
    void removeSwapsLastIntoGap() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, 0, new Position(0, 0));
        chunk.add(Entity.of(1, 0));
        chunk.set(POS_ID, 1, new Position(1, 1));
        chunk.add(Entity.of(2, 0));
        chunk.set(POS_ID, 2, new Position(2, 2));

        chunk.remove(0);

        assertEquals(2, chunk.count());
        assertEquals(Entity.of(2, 0), chunk.entity(0));
        assertEquals(new Position(2, 2), chunk.get(POS_ID, 0));
    }

    @Test
    void removeLastElement() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.remove(0);
        assertEquals(0, chunk.count());
    }

    @Test
    void isFull() {
        var chunk = createChunk(2);
        assertFalse(chunk.isFull());
        chunk.add(Entity.of(0, 0));
        assertFalse(chunk.isFull());
        chunk.add(Entity.of(1, 0));
        assertTrue(chunk.isFull());
    }

    @Test
    void isEmpty() {
        var chunk = createChunk(2);
        assertTrue(chunk.isEmpty());
        chunk.add(Entity.of(0, 0));
        assertFalse(chunk.isEmpty());
    }

    @Test
    void capacity() {
        var chunk = createChunk(1024);
        assertEquals(1024, chunk.capacity());
    }

    @Test
    void addToFullChunkThrows() {
        var chunk = createChunk(1);
        chunk.add(Entity.of(0, 0));
        assertThrows(IllegalStateException.class, () -> chunk.add(Entity.of(1, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storageAccess() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, 0, new Position(5, 10));
        var storage = (ComponentStorage<Position>) chunk.componentStorage(POS_ID);
        assertEquals(new Position(5, 10), storage.get(0));
    }
}
