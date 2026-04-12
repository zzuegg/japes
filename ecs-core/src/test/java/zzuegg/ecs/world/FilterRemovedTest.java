package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code @Filter(Removed)} — the third leg of the
 * symmetric Added/Changed/Removed filter API. The system fires
 * once per entity that lost a target component since the last tick,
 * with {@code @Read} params bound to the last-known values from the
 * removal log.
 */
class FilterRemovedTest {

    public record State(int value) {}
    public record Health(int hp) {}
    public record Mana(int points) {}

    // --- Cycle 1: single-target @Filter(Removed) with last value ---

    static final List<Mana> REMOVED_MANA = new ArrayList<>();
    static final List<Entity> REMOVED_ENTITIES = new ArrayList<>();

    public static class SingleRemovedObserver {
        @System
        @Filter(value = Removed.class, target = Mana.class)
        public void onRemoved(@Read Mana lastMana, Entity self) {
            REMOVED_MANA.add(lastMana);
            REMOVED_ENTITIES.add(self);
        }
    }

    @Test
    void singleTargetRemovedFiresWithLastValueOnComponentStrip() {
        REMOVED_MANA.clear();
        REMOVED_ENTITIES.clear();

        var world = World.builder()
            .addSystem(SingleRemovedObserver.class)
            .build();

        var e1 = world.spawn(new State(1), new Health(100), new Mana(42));
        var e2 = world.spawn(new State(2), new Health(200), new Mana(99));
        world.tick(); // prime watermarks

        REMOVED_MANA.clear();
        REMOVED_ENTITIES.clear();

        // Strip Mana from e1 only.
        world.removeComponent(e1, Mana.class);
        world.tick();

        assertEquals(1, REMOVED_MANA.size(), "should fire once for the one stripped entity");
        assertEquals(new Mana(42), REMOVED_MANA.getFirst(),
            "@Read Mana must bind to the LAST value before removal");
        assertEquals(e1, REMOVED_ENTITIES.getFirst());
    }

    @Test
    void singleTargetRemovedFiresOnDespawn() {
        REMOVED_MANA.clear();
        REMOVED_ENTITIES.clear();

        var world = World.builder()
            .addSystem(SingleRemovedObserver.class)
            .build();

        var e1 = world.spawn(new State(1), new Health(100), new Mana(77));
        world.tick(); // prime

        REMOVED_MANA.clear();
        REMOVED_ENTITIES.clear();

        world.despawn(e1);
        world.tick();

        assertEquals(1, REMOVED_MANA.size(), "despawn removes Mana too — observer should fire");
        assertEquals(new Mana(77), REMOVED_MANA.getFirst());
    }

    @Test
    void singleTargetRemovedDoesNotFireWhenNothingRemoved() {
        REMOVED_MANA.clear();

        var world = World.builder()
            .addSystem(SingleRemovedObserver.class)
            .build();

        world.spawn(new State(1), new Health(100), new Mana(50));
        world.tick(); // prime

        REMOVED_MANA.clear();
        // Only mutate — don't remove anything.
        world.tick();

        assertEquals(0, REMOVED_MANA.size(), "no removal → no observer call");
    }

    // --- Cycle 2: multi-target @Filter(Removed) ---

    static final List<String> MULTI_REMOVED = new ArrayList<>();

    public static class MultiRemovedObserver {
        @System
        @Filter(value = Removed.class, target = {State.class, Health.class, Mana.class})
        public void onRemoved(@Read State lastState, @Read Health lastHealth, @Read Mana lastMana, Entity self) {
            MULTI_REMOVED.add("s=" + lastState + " h=" + lastHealth + " m=" + lastMana);
        }
    }

    @Test
    void multiTargetRemovedFiresOncePerEntityWithAllLastValues() {
        MULTI_REMOVED.clear();

        var world = World.builder()
            .addSystem(MultiRemovedObserver.class)
            .build();

        var e1 = world.spawn(new State(10), new Health(200), new Mana(30));
        world.tick();

        MULTI_REMOVED.clear();

        // Despawn e1 — all three components removed. Observer should fire
        // once with all three last values from the removal log.
        world.despawn(e1);
        world.tick();

        assertEquals(1, MULTI_REMOVED.size());
        assertTrue(MULTI_REMOVED.getFirst().contains("s=State[value=10]"),
            "State last value must be present: " + MULTI_REMOVED);
        assertTrue(MULTI_REMOVED.getFirst().contains("h=Health[hp=200]"),
            "Health last value must be present: " + MULTI_REMOVED);
        assertTrue(MULTI_REMOVED.getFirst().contains("m=Mana[points=30]"),
            "Mana last value must be present: " + MULTI_REMOVED);
    }

    @Test
    void multiTargetRemovedOnComponentStripBindsLiveValuesForUnremovedComponents() {
        MULTI_REMOVED.clear();

        var world = World.builder()
            .addSystem(MultiRemovedObserver.class)
            .build();

        var e1 = world.spawn(new State(5), new Health(100), new Mana(25));
        world.tick();

        MULTI_REMOVED.clear();

        // Only strip Mana — entity is still alive with {State, Health}.
        // State and Health should come from the live entity.
        // Mana should come from the removal log.
        world.removeComponent(e1, Mana.class);
        world.tick();

        assertEquals(1, MULTI_REMOVED.size());
        assertTrue(MULTI_REMOVED.getFirst().contains("s=State[value=5]"),
            "Live State value: " + MULTI_REMOVED);
        assertTrue(MULTI_REMOVED.getFirst().contains("h=Health[hp=100]"),
            "Live Health value: " + MULTI_REMOVED);
        assertTrue(MULTI_REMOVED.getFirst().contains("m=Mana[points=25]"),
            "Last Mana from removal log: " + MULTI_REMOVED);
    }

    @Test
    void multiTargetRemovedDeduplicatesOnFullDespawn() {
        MULTI_REMOVED.clear();

        var world = World.builder()
            .addSystem(MultiRemovedObserver.class)
            .build();

        var e1 = world.spawn(new State(1), new Health(2), new Mana(3));
        world.tick();

        MULTI_REMOVED.clear();

        // Despawn puts 3 entries in the removal log (one per component).
        // Observer must fire exactly ONCE for the entity, not three times.
        world.despawn(e1);
        world.tick();

        assertEquals(1, MULTI_REMOVED.size(),
            "Despawn removes 3 components but observer must deduplicate per entity");
    }
}
