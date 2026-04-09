package zzuegg.ecs.event;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventRegistryTest {

    record HitEvent(int damage) {}
    record DeathEvent() {}

    @Test
    void registerAndGetStore() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        assertNotNull(registry.store(HitEvent.class));
    }

    @Test
    void unregisteredEventThrows() {
        var registry = new EventRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.store(HitEvent.class));
    }

    @Test
    void swapAllSwapsAllStores() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        registry.register(DeathEvent.class);

        registry.store(HitEvent.class).send(new HitEvent(10));
        registry.store(DeathEvent.class).send(new DeathEvent());

        registry.swapAll();

        assertEquals(1, registry.store(HitEvent.class).read().size());
        assertEquals(1, registry.store(DeathEvent.class).read().size());
    }

    @Test
    void doubleRegisterIsIdempotent() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        registry.register(HitEvent.class);
        assertNotNull(registry.store(HitEvent.class));
    }
}
