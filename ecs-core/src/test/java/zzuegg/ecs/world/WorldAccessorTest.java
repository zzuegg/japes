package zzuegg.ecs.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldAccessorTest {

    @Persistent record Position(float x, float y) {}
    @Persistent @NetworkSync record Health(int hp) {}
    record Transient(int value) {}

    World world;

    @BeforeEach
    void setUp() {
        world = World.builder().build();
    }

    @Test
    void accessorReturnsAllEntities() {
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Health(100));
        var e3 = world.spawn(new Position(3, 4), new Health(50));

        var accessor = world.accessor();
        var entities = new HashSet<Entity>();
        accessor.allEntities().forEach(entities::add);

        assertEquals(3, entities.size());
        assertTrue(entities.contains(e1));
        assertTrue(entities.contains(e2));
        assertTrue(entities.contains(e3));
    }

    @Test
    void accessorGetComponent() {
        var e = world.spawn(new Position(1, 2), new Health(100));
        var accessor = world.accessor();

        var pos = accessor.getComponent(e, Position.class);
        assertNotNull(pos);
        assertEquals(1f, pos.x());
        assertEquals(2f, pos.y());

        var hp = accessor.getComponent(e, Health.class);
        assertEquals(100, hp.hp());
    }

    @Test
    void accessorGetComponentReturnsNullForMissing() {
        var e = world.spawn(new Position(1, 2));
        var accessor = world.accessor();
        assertNull(accessor.getComponent(e, Health.class));
    }

    @Test
    void accessorComponentTypes() {
        var e = world.spawn(new Position(1, 2), new Health(100));
        var accessor = world.accessor();

        var types = accessor.componentTypes(e);
        assertTrue(types.contains(Position.class));
        assertTrue(types.contains(Health.class));
        assertEquals(2, types.size());
    }

    @Test
    void accessorEntitiesWith() {
        world.spawn(new Position(1, 2));
        world.spawn(new Health(100));
        var both = world.spawn(new Position(3, 4), new Health(50));

        var accessor = world.accessor();
        var entities = new ArrayList<Entity>();
        accessor.entitiesWith(Position.class, Health.class).forEach(entities::add);

        assertEquals(1, entities.size());
        assertEquals(both, entities.getFirst());
    }

    @Test
    void accessorFilterByAnnotation() {
        var e1 = world.spawn(new Position(1, 2));
        var e2 = world.spawn(new Transient(42));
        var e3 = world.spawn(new Position(3, 4), new Health(50));

        var accessor = world.accessor();

        // Get all persistent entities (at least one @Persistent component)
        var persistent = new ArrayList<Entity>();
        accessor.persistentEntities().forEach(persistent::add);
        assertTrue(persistent.contains(e1));
        assertTrue(persistent.contains(e3));
        assertFalse(persistent.contains(e2));
    }

    @Test
    void accessorGetPersistentComponents() {
        var e = world.spawn(new Position(1, 2), new Transient(42), new Health(50));
        var accessor = world.accessor();

        var comps = accessor.persistentComponents(e);
        assertEquals(2, comps.size());

        var types = new HashSet<Class<?>>();
        for (var c : comps) types.add(c.getClass());
        assertTrue(types.contains(Position.class));
        assertTrue(types.contains(Health.class));
    }

    @Test
    void accessorGetNetworkSyncComponents() {
        var e = world.spawn(new Position(1, 2), new Health(50));
        var accessor = world.accessor();

        var comps = accessor.networkSyncComponents(e);
        assertEquals(1, comps.size());
        assertInstanceOf(Health.class, comps.getFirst());
    }
}
