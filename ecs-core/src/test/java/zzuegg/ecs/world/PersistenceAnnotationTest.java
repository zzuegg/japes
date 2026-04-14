package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.component.Persistent;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceAnnotationTest {

    @Persistent
    record Health(int hp) {}

    record Velocity(float x, float y) {}

    @Persistent
    @NetworkSync
    record Position(float x, float y) {}

    @NetworkSync
    record TransientSynced(int value) {}

    @Test
    void persistentAnnotationIsRetained() {
        assertTrue(Health.class.isAnnotationPresent(Persistent.class));
        assertFalse(Velocity.class.isAnnotationPresent(Persistent.class));
    }

    @Test
    void networkSyncAnnotationIsRetained() {
        assertTrue(Position.class.isAnnotationPresent(NetworkSync.class));
        assertFalse(Health.class.isAnnotationPresent(NetworkSync.class));
    }

    @Test
    void componentCanHaveBothAnnotations() {
        assertTrue(Position.class.isAnnotationPresent(Persistent.class));
        assertTrue(Position.class.isAnnotationPresent(NetworkSync.class));
    }

    @Test
    void networkSyncWithoutPersistent() {
        assertTrue(TransientSynced.class.isAnnotationPresent(NetworkSync.class));
        assertFalse(TransientSynced.class.isAnnotationPresent(Persistent.class));
    }
}
