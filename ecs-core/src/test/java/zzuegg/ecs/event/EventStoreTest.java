package zzuegg.ecs.event;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {

    record DamageEvent(int amount) {}

    @Test
    void sendAndReadAfterSwap() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.send(new DamageEvent(20));
        store.swap();
        assertEquals(List.of(new DamageEvent(10), new DamageEvent(20)), store.read());
    }

    @Test
    void readIsEmptyBeforeSwap() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        assertTrue(store.read().isEmpty());
    }

    @Test
    void swapClearsOldReadBuffer() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.swap();
        assertEquals(1, store.read().size());
        store.swap();
        assertTrue(store.read().isEmpty());
    }

    @Test
    void concurrentSendsFromMultipleWriters() {
        var store = new EventStore<DamageEvent>();
        var w1 = store.writer();
        var w2 = store.writer();
        w1.send(new DamageEvent(10));
        w2.send(new DamageEvent(20));
        store.swap();
        assertEquals(2, store.read().size());
    }

    @Test
    void readerReturnsImmutableView() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.swap();
        var reader = store.reader();
        var events = reader.read();
        assertThrows(UnsupportedOperationException.class, () -> events.add(new DamageEvent(99)));
    }
}
