package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-target {@code @Filter} — a single system that
 * observes changes across multiple component types from one
 * method, walking the <em>union</em> of their dirty lists.
 */
class MultiTargetFilterTest {

    public record State(int value) {}
    public record Health(int hp) {}
    public record Mana(int points) {}

    static final AtomicInteger CHANGED_CALLS = new AtomicInteger();

    /**
     * One system, one filter targeting three components. Should fire
     * once per entity where ANY of the three was changed since last
     * tick — not three times per entity, not once per component.
     */
    public static class UnifiedChangedObserver {
        @System
        @Filter(value = Changed.class, target = {State.class, Health.class, Mana.class})
        public void observe(@Read State s, @Read Health h, @Read Mana m) {
            CHANGED_CALLS.incrementAndGet();
        }
    }

    @Test
    void multiTargetChangedFiresOncePerEntityNotOncePerComponent() {
        CHANGED_CALLS.set(0);
        var world = World.builder()
            .addSystem(UnifiedChangedObserver.class)
            .build();

        var e1 = world.spawn(new State(0), new Health(100), new Mana(50));
        var e2 = world.spawn(new State(0), new Health(100), new Mana(50));
        world.tick(); // prime watermarks past initial spawn

        CHANGED_CALLS.set(0);
        // Mutate State on e1, Health on e2 — two different components,
        // two different entities. Each should be visited exactly once.
        world.setComponent(e1, new State(1));
        world.setComponent(e2, new Health(99));
        world.tick();

        assertEquals(2, CHANGED_CALLS.get(),
            "Multi-target @Filter(Changed) must visit each changed entity exactly once");
    }

    @Test
    void multiTargetChangedDeduplicatesWhenMultipleComponentsChangeOnSameEntity() {
        CHANGED_CALLS.set(0);
        var world = World.builder()
            .addSystem(UnifiedChangedObserver.class)
            .build();

        var e1 = world.spawn(new State(0), new Health(100), new Mana(50));
        world.tick();

        CHANGED_CALLS.set(0);
        // Change ALL THREE components on the same entity.
        // The system must still fire exactly once for that entity.
        world.setComponent(e1, new State(1));
        world.setComponent(e1, new Health(99));
        world.setComponent(e1, new Mana(49));
        world.tick();

        assertEquals(1, CHANGED_CALLS.get(),
            "Multi-target filter must deduplicate: one call per entity, "
            + "not one per changed component");
    }

    @Test
    void multiTargetChangedDoesNotFireForUntouchedEntities() {
        CHANGED_CALLS.set(0);
        var world = World.builder()
            .addSystem(UnifiedChangedObserver.class)
            .build();

        var e1 = world.spawn(new State(0), new Health(100), new Mana(50));
        var e2 = world.spawn(new State(0), new Health(100), new Mana(50));
        world.tick();

        CHANGED_CALLS.set(0);
        // Only touch e1 — e2 must not be visited.
        world.setComponent(e1, new State(1));
        world.tick();

        assertEquals(1, CHANGED_CALLS.get(),
            "Untouched entities must not trigger the observer");
    }
}
