package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicSystemTest {

    record Counter(int value) {}
    record Position(float x, float y) {}

    static final List<Integer> ticked = Collections.synchronizedList(new ArrayList<>());

    static class CountSystem {
        @System
        void count(@Write Mut<Counter> counter) {
            var c = counter.get();
            counter.set(new Counter(c.value() + 1));
            ticked.add(c.value() + 1);
        }
    }

    static class DoubleSystem {
        @System
        void doubleIt(@Write Mut<Counter> counter) {
            counter.set(new Counter(counter.get().value() * 2));
        }
    }

    // === Enable/Disable ===

    @Test
    void disableSystemSkipsExecution() {
        ticked.clear();
        var world = World.builder()
            .addSystem(CountSystem.class)
            .build();

        world.spawn(new Counter(0));
        world.tick();
        assertEquals(1, ticked.size());

        world.setSystemEnabled("count", false);
        world.tick();
        assertEquals(1, ticked.size()); // no new tick

        world.setSystemEnabled("count", true);
        world.tick();
        assertEquals(2, ticked.size()); // resumed
    }

    @Test
    void disableNonexistentSystemIsNoOp() {
        var world = World.builder().build();
        assertDoesNotThrow(() -> world.setSystemEnabled("doesntExist", false));
    }

    // === Add System ===

    @Test
    void addSystemAtRuntime() {
        var world = World.builder().build();
        world.spawn(new Counter(5));

        // No systems yet — tick is no-op
        world.tick();

        // Add system at runtime
        world.addSystem(DoubleSystem.class);
        world.tick();

        // Counter should be doubled: 5 * 2 = 10
        var entities = world.findEntities(
            zzuegg.ecs.query.FieldFilter.of(Counter.class, "value").equalTo(10),
            Counter.class
        );
        assertEquals(1, entities.size());
    }

    @Test
    void addMultipleSystemsAtRuntime() {
        ticked.clear();
        var world = World.builder().build();
        world.spawn(new Counter(0));

        world.addSystem(CountSystem.class);
        world.tick();
        assertEquals(1, ticked.size());

        world.addSystem(DoubleSystem.class);
        world.tick();
        // count runs first (0+1=1... wait, counter is now 1 from first tick)
        // Actually order depends on DAG. Both write Counter so they're serialized.
        assertEquals(2, ticked.size());
    }

    // === Remove System ===

    @Test
    void removeSystemAtRuntime() {
        ticked.clear();
        var world = World.builder()
            .addSystem(CountSystem.class)
            .build();

        world.spawn(new Counter(0));
        world.tick();
        assertEquals(1, ticked.size());

        world.removeSystem("count");
        world.tick();
        assertEquals(1, ticked.size()); // no new tick
    }

    @Test
    void removeNonexistentSystemIsNoOp() {
        var world = World.builder().build();
        assertDoesNotThrow(() -> world.removeSystem("doesntExist"));
    }

    @Test
    void addSystemAfterRemove() {
        ticked.clear();
        var world = World.builder()
            .addSystem(CountSystem.class)
            .build();

        world.spawn(new Counter(0));
        world.tick();
        assertEquals(1, ticked.size());

        world.removeSystem("count");
        world.tick();
        assertEquals(1, ticked.size());

        world.addSystem(CountSystem.class);
        world.tick();
        assertEquals(2, ticked.size());
    }
}
