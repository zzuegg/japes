package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRegistryTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    @SparseStorage record Marker() {}
    @ValueTracked record Health(int hp) {}

    @Test
    void registerReturnsUniqueIds() {
        var registry = new ComponentRegistry();
        var posId = registry.register(Position.class);
        var velId = registry.register(Velocity.class);
        assertNotEquals(posId, velId);
    }

    @Test
    void registerSameTypeTwiceReturnsSameId() {
        var registry = new ComponentRegistry();
        var id1 = registry.register(Position.class);
        var id2 = registry.register(Position.class);
        assertEquals(id1, id2);
    }

    @Test
    void getOrRegisterAutoRegisters() {
        var registry = new ComponentRegistry();
        var id = registry.getOrRegister(Position.class);
        assertEquals(id, registry.getOrRegister(Position.class));
    }

    @Test
    void infoReflectsTableStorageByDefault() {
        var registry = new ComponentRegistry();
        registry.register(Position.class);
        var info = registry.info(Position.class);
        assertTrue(info.isTableStorage());
        assertFalse(info.isSparseStorage());
    }

    @Test
    void sparseAnnotationRejectedUntilImplemented() {
        // @SparseStorage is scaffolded but not yet wired through the archetype
        // pipeline. Silently falling back to table storage would mask the gap;
        // registration throws until the sparse path exists.
        var registry = new ComponentRegistry();
        assertThrows(UnsupportedOperationException.class,
            () -> registry.register(Marker.class));
    }

    @Test
    void infoReflectsValueTrackedAnnotation() {
        var registry = new ComponentRegistry();
        registry.register(Health.class);
        var info = registry.info(Health.class);
        assertTrue(info.isValueTracked());
    }

    @Test
    void lookupByIdReturnsCorrectInfo() {
        var registry = new ComponentRegistry();
        var id = registry.register(Position.class);
        var info = registry.info(id);
        assertEquals(Position.class, info.type());
    }

    @Test
    void unregisteredTypeThrows() {
        var registry = new ComponentRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.info(Position.class));
    }

    @Test
    void nonRecordComponentThrows() {
        var registry = new ComponentRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(String.class));
    }

    @Test
    void componentCount() {
        var registry = new ComponentRegistry();
        assertEquals(0, registry.count());
        registry.register(Position.class);
        registry.register(Velocity.class);
        assertEquals(2, registry.count());
    }
}
