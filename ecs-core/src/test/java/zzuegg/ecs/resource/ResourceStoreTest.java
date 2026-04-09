package zzuegg.ecs.resource;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStoreTest {

    record DeltaTime(float dt) {}
    record Gravity(float g) {}

    @Test
    void insertAndGet() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        var res = store.get(DeltaTime.class);
        assertEquals(new DeltaTime(0.016f), res.get());
    }

    @Test
    void getMutAllowsSetAndReadBack() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        var mut = store.getMut(DeltaTime.class);
        mut.set(new DeltaTime(0.033f));
        assertEquals(new DeltaTime(0.033f), mut.get());
    }

    @Test
    void insertOverwrites() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        store.insert(new DeltaTime(0.033f));
        assertEquals(new DeltaTime(0.033f), store.get(DeltaTime.class).get());
    }

    @Test
    void getMissingResourceThrows() {
        var store = new ResourceStore();
        assertThrows(IllegalArgumentException.class, () -> store.get(DeltaTime.class));
    }

    @Test
    void getMutMissingResourceThrows() {
        var store = new ResourceStore();
        assertThrows(IllegalArgumentException.class, () -> store.getMut(DeltaTime.class));
    }

    @Test
    void containsReturnsTrueAfterInsert() {
        var store = new ResourceStore();
        assertFalse(store.contains(DeltaTime.class));
        store.insert(new DeltaTime(0f));
        assertTrue(store.contains(DeltaTime.class));
    }

    @Test
    void independentResourceTypes() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        store.insert(new Gravity(-9.81f));
        assertEquals(new DeltaTime(0.016f), store.get(DeltaTime.class).get());
        assertEquals(new Gravity(-9.81f), store.get(Gravity.class).get());
    }
}
