package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ValueTracked;
import zzuegg.ecs.system.Changed;
import zzuegg.ecs.system.Filter;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * World.setComponent and Commands.set (which routes through setComponent) are
 * legitimate mutation paths outside Mut, but change detection previously only
 * fired on Mut.flush(). Any @Filter(Changed) observer would silently miss
 * writes through these APIs.
 */
class SetComponentMarksChangedTest {

    record Position(float x, float y) {}
    @ValueTracked record Score(int value) {}

    static class ChangedObserver {
        final List<Position> observed = new ArrayList<>();

        @System
        @Filter(value = Changed.class, target = Position.class)
        void watch(@Read Position pos) {
            observed.add(pos);
        }
    }

    @Test
    void worldSetComponentFiresChanged() {
        var observer = new ChangedObserver();
        var world = World.builder().addSystem(observer).build();
        var entity = world.spawn(new Position(1, 1));

        world.tick();                           // observer's lastSeen advances past spawn
        observer.observed.clear();

        world.setComponent(entity, new Position(9, 9));
        world.tick();

        assertEquals(1, observer.observed.size(),
            "setComponent must mark the entity as changed");
        assertEquals(9f, observer.observed.getFirst().x());
    }

    @Test
    void commandSetAlsoFiresChanged() {
        var observer = new ChangedObserver();
        var world = World.builder().addSystem(observer).build();
        var entity = world.spawn(new Position(1, 1));

        world.tick();
        observer.observed.clear();

        // Route through the command buffer — the set is applied at the
        // end-of-stage flush, not inline, but must still mark changed.
        var cmds = new Commands();
        cmds.set(entity, new Position(7, 7));
        cmds.applyTo(world);

        world.tick();
        assertEquals(1, observer.observed.size(),
            "SetCommand routed through world.setComponent must also mark changed");
    }

    static class ScoreObserver {
        final List<Score> observed = new ArrayList<>();

        @System
        @Filter(value = Changed.class, target = Score.class)
        void watch(@Read Score s) {
            observed.add(s);
        }
    }

    @Test
    void valueTrackedSetComponentSuppressesNoOp() {
        var observer = new ScoreObserver();
        var world = World.builder().addSystem(observer).build();
        var entity = world.spawn(new Score(42));

        world.tick();
        observer.observed.clear();

        // Identical value on a @ValueTracked component: no change-detection.
        // Matches Mut.flush() semantics so the two mutation paths are
        // behaviourally consistent.
        world.setComponent(entity, new Score(42));
        world.tick();

        assertEquals(0, observer.observed.size(),
            "identical-value set on a @ValueTracked component must not fire Changed");
    }

    @Test
    void valueTrackedSetComponentWithRealChangeDoesFire() {
        var observer = new ScoreObserver();
        var world = World.builder().addSystem(observer).build();
        var entity = world.spawn(new Score(42));

        world.tick();
        observer.observed.clear();

        world.setComponent(entity, new Score(100));
        world.tick();

        assertEquals(1, observer.observed.size());
        assertEquals(100, observer.observed.getFirst().value());
    }
}
