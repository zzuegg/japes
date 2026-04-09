package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentArrayTest {

    record Position(float x, float y) {}

    @Test
    void setAndGetAtIndex() {
        var array = new ComponentArray<>(Position.class, 16);
        var pos = new Position(1.0f, 2.0f);
        array.set(0, pos);
        assertEquals(pos, array.get(0));
    }

    @Test
    void independentSlots() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(1, 1));
        array.set(1, new Position(2, 2));
        assertEquals(new Position(1, 1), array.get(0));
        assertEquals(new Position(2, 2), array.get(1));
    }

    @Test
    void swapRemoveMovesLastIntoGap() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(0, 0));
        array.set(1, new Position(1, 1));
        array.set(2, new Position(2, 2));
        array.swapRemove(0, 3);
        assertEquals(new Position(2, 2), array.get(0));
        assertEquals(new Position(1, 1), array.get(1));
    }

    @Test
    void swapRemoveLastElement() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(0, 0));
        array.swapRemove(0, 1);
        assertNull(array.get(0));
    }

    @Test
    void copyInto() {
        var src = new ComponentArray<>(Position.class, 16);
        var dst = new ComponentArray<>(Position.class, 16);
        src.set(3, new Position(5, 5));
        src.copyInto(3, dst, 0);
        assertEquals(new Position(5, 5), dst.get(0));
    }

    @Test
    void capacity() {
        var array = new ComponentArray<>(Position.class, 1024);
        assertEquals(1024, array.capacity());
    }

    @Test
    void getOutOfBoundsThrows() {
        var array = new ComponentArray<>(Position.class, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> array.get(5));
    }
}
