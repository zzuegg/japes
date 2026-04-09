package zzuegg.ecs.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityAllocatorTest {

    @Test
    void allocateReturnsSequentialIndices() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        assertEquals(0, e0.index());
        assertEquals(1, e1.index());
    }

    @Test
    void newEntitiesHaveGenerationZero() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        assertEquals(0, entity.generation());
    }

    @Test
    void recycledEntityHasIncrementedGeneration() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        alloc.free(e0);
        var reused = alloc.allocate();
        assertEquals(e0.index(), reused.index());
        assertEquals(1, reused.generation());
    }

    @Test
    void isAliveReturnsTrueForLiveEntity() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        assertTrue(alloc.isAlive(entity));
    }

    @Test
    void isAliveReturnsFalseForFreedEntity() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        assertFalse(alloc.isAlive(entity));
    }

    @Test
    void staleReferenceIsNotAlive() {
        var alloc = new EntityAllocator();
        var original = alloc.allocate();
        alloc.free(original);
        alloc.allocate(); // reuses slot
        assertFalse(alloc.isAlive(original)); // stale generation
    }

    @Test
    void freeListReusesInLifoOrder() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        alloc.free(e0);
        alloc.free(e1);
        var r0 = alloc.allocate();
        var r1 = alloc.allocate();
        assertEquals(e1.index(), r0.index()); // LIFO
        assertEquals(e0.index(), r1.index());
    }

    @Test
    void doubleFreeSameEntityThrows() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        assertThrows(IllegalArgumentException.class, () -> alloc.free(entity));
    }

    @Test
    void freeStaleEntityThrows() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        alloc.allocate(); // reuses slot
        assertThrows(IllegalArgumentException.class, () -> alloc.free(entity));
    }

    @Test
    void freeNeverAllocatedEntityThrows() {
        var alloc = new EntityAllocator();
        assertThrows(IllegalArgumentException.class, () -> alloc.free(Entity.of(999, 0)));
    }

    @Test
    void allocateManyEntities() {
        var alloc = new EntityAllocator();
        for (int i = 0; i < 10_000; i++) {
            var e = alloc.allocate();
            assertEquals(i, e.index());
        }
    }

    @Test
    void entityCountTracksLiveEntities() {
        var alloc = new EntityAllocator();
        assertEquals(0, alloc.entityCount());
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        assertEquals(2, alloc.entityCount());
        alloc.free(e0);
        assertEquals(1, alloc.entityCount());
    }
}
