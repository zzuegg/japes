package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.archetype.ArchetypeGraph;
import zzuegg.ecs.archetype.EntityLocation;
import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.entity.Entity;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeTrackingAcrossArchetypeMoveTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    @SuppressWarnings("unchecked")
    private static ChangeTracker trackerOf(World world, Entity entity, Class<? extends Record> type) throws Exception {
        Field locF = World.class.getDeclaredField("entityLocations");
        locF.setAccessible(true);
        var locations = (List<EntityLocation>) locF.get(world);
        var loc = locations.get(entity.index());

        Field graphF = World.class.getDeclaredField("archetypeGraph");
        graphF.setAccessible(true);
        var graph = (ArchetypeGraph) graphF.get(world);

        Field regF = World.class.getDeclaredField("componentRegistry");
        regF.setAccessible(true);
        var registry = (ComponentRegistry) regF.get(world);
        ComponentId compId = registry.getOrRegister(type);

        var archetype = graph.get(loc.archetypeId());
        return archetype.chunks().get(loc.chunkIndex()).changeTracker(compId);
    }

    private static long currentTick(World world) throws Exception {
        Field tf = World.class.getDeclaredField("tick");
        tf.setAccessible(true);
        var tick = tf.get(world);
        return (long) tick.getClass().getDeclaredMethod("current").invoke(tick);
    }

    @Test
    void addComponentMarksNewComponentAsAddedAtCurrentTick() throws Exception {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        world.tick();                    // tick = 1
        world.tick();                    // tick = 2

        long tickBeforeAdd = currentTick(world);
        world.addComponent(entity, new Velocity(3, 4));

        var velTracker = trackerOf(world, entity, Velocity.class);
        // find the entity's new slot
        Field locF = World.class.getDeclaredField("entityLocations");
        locF.setAccessible(true);
        var locations = (List<EntityLocation>) locF.get(world);
        int slot = locations.get(entity.index()).slotIndex();

        assertEquals(tickBeforeAdd, velTracker.addedTick(slot),
            "newly added Velocity must be marked added at the current tick");
    }

    @Test
    void addComponentPreservesExistingComponentAddedHistory() throws Exception {
        var world = World.builder().build();
        // Advance tick *before* spawn so the spawn tick is non-zero; otherwise a fresh
        // tracker would pass the assertion trivially (both default 0 and "preserved 0").
        world.tick();
        world.tick();
        world.tick();
        var entity = world.spawn(new Position(1, 2));
        long posAddedAtSpawn = currentTick(world);
        assertTrue(posAddedAtSpawn > 0, "precondition: spawn tick must be > 0");

        world.tick();
        world.tick();
        long tickBeforeAdd = currentTick(world);
        assertNotEquals(posAddedAtSpawn, tickBeforeAdd);

        world.addComponent(entity, new Velocity(3, 4));

        var posTracker = trackerOf(world, entity, Position.class);
        Field locF = World.class.getDeclaredField("entityLocations");
        locF.setAccessible(true);
        @SuppressWarnings("unchecked")
        var locations = (List<EntityLocation>) locF.get(world);
        int slot = locations.get(entity.index()).slotIndex();

        assertEquals(posAddedAtSpawn, posTracker.addedTick(slot),
            "Position's original addedTick must survive archetype migration");
    }

    @Test
    void removeComponentPreservesRemainingComponentHistory() throws Exception {
        var world = World.builder().build();
        // Force non-zero spawn tick (see note above).
        world.tick();
        world.tick();
        world.tick();
        var entity = world.spawn(new Position(1, 2), new Velocity(3, 4));
        long spawnTick = currentTick(world);
        assertTrue(spawnTick > 0, "precondition: spawn tick must be > 0");

        world.tick();
        world.tick();

        world.removeComponent(entity, Velocity.class);

        var posTracker = trackerOf(world, entity, Position.class);
        Field locF = World.class.getDeclaredField("entityLocations");
        locF.setAccessible(true);
        @SuppressWarnings("unchecked")
        var locations = (List<EntityLocation>) locF.get(world);
        int slot = locations.get(entity.index()).slotIndex();

        assertEquals(spawnTick, posTracker.addedTick(slot),
            "Position's addedTick must survive removeComponent migration");
    }
}
