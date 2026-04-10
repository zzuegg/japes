package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.system.Added;
import zzuegg.ecs.system.Changed;
import zzuegg.ecs.system.Filter;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeFilterWiringTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    static class AddedObserver {
        final List<Position> observed = new ArrayList<>();

        @System
        @Filter(value = Added.class, target = Position.class)
        void watch(@Read Position pos) {
            observed.add(pos);
        }
    }

    @Test
    void addedFilterObservesOnlyNewlySpawnedEntitiesPerTick() {
        var observer = new AddedObserver();
        var world = World.builder().addSystem(observer).build();

        world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));

        world.tick();
        assertEquals(2, observer.observed.size(),
            "first tick after spawn must see both entities as 'added'");

        observer.observed.clear();
        world.tick();
        assertEquals(0, observer.observed.size(),
            "second tick without new spawns must see zero 'added' entities");

        world.spawn(new Position(3, 3));
        world.tick();
        assertEquals(1, observer.observed.size(),
            "only the newly spawned entity must appear as 'added' on the tick after its spawn");
        assertEquals(3f, observer.observed.getFirst().x());
    }

    static class ChangedObserver {
        final List<Position> observed = new ArrayList<>();

        @System
        @Filter(value = Changed.class, target = Position.class)
        void watch(@Read Position pos) {
            observed.add(pos);
        }
    }

    static class Mover {
        @System
        void move(@Read Velocity vel, @Write Mut<Position> pos) {
            var p = pos.get();
            pos.set(new Position(p.x() + vel.dx(), p.y() + vel.dy()));
        }
    }

    @Test
    void changedFilterObservesOnlyMutatedEntities() {
        var observer = new ChangedObserver();
        var world = World.builder()
            // Mover runs before observer so the writes are visible.
            .addSystem(Mover.class)
            .addSystem(observer)
            .build();

        world.spawn(new Position(1, 1), new Velocity(1, 0));
        // Entity with no velocity — its Position should never be "changed".
        world.spawn(new Position(5, 5));

        world.tick();
        // Both are "added" on the first tick from the writer's perspective, but
        // the observer is watching 'Changed', not 'Added'. Mover mutates the
        // first entity, so the observer sees exactly one changed Position.
        assertEquals(1, observer.observed.size(),
            "only the mover-touched entity should appear as 'changed'");
        assertEquals(2f, observer.observed.getFirst().x());

        observer.observed.clear();
        world.tick();
        assertEquals(1, observer.observed.size(),
            "still only the moved entity is changed");
        assertEquals(3f, observer.observed.getFirst().x());
    }
}
