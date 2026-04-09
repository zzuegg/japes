package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentStorageTest {

    record Position(float x, float y) {}

    @Test
    void defaultStorageSetAndGet() {
        var storage = ComponentStorage.create(Position.class, 16);
        storage.set(0, new Position(1, 2));
        assertEquals(new Position(1, 2), storage.get(0));
    }

    @Test
    void swapRemove() {
        var storage = ComponentStorage.create(Position.class, 16);
        storage.set(0, new Position(0, 0));
        storage.set(1, new Position(1, 1));
        storage.set(2, new Position(2, 2));
        storage.swapRemove(0, 3);
        assertEquals(new Position(2, 2), storage.get(0));
    }

    @Test
    void copyInto() {
        var src = ComponentStorage.create(Position.class, 16);
        var dst = ComponentStorage.create(Position.class, 16);
        src.set(3, new Position(5, 5));
        src.copyInto(3, dst, 0);
        assertEquals(new Position(5, 5), dst.get(0));
    }

    @Test
    void capacity() {
        var storage = ComponentStorage.create(Position.class, 1024);
        assertEquals(1024, storage.capacity());
    }

    @Test
    void factoryCanBeOverridden() {
        ComponentStorage.Factory factory = new ComponentStorage.Factory() {
            @Override
            public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
                return ComponentStorage.defaultFactory().create(type, capacity);
            }
        };
        var storage = factory.create(Position.class, 8);
        storage.set(0, new Position(9, 9));
        assertEquals(new Position(9, 9), storage.get(0));
    }
}
