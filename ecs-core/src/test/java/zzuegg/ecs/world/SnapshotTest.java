package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Sprite(String name) {}

    @Test
    void snapshotCopiesMatchingEntities() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2), new Sprite("player"));
        world.spawn(new Position(3, 4), new Sprite("enemy"));
        world.spawn(new Velocity(1, 1)); // no Position+Sprite, excluded

        var snapshot = world.snapshot(Position.class, Sprite.class);
        assertEquals(2, snapshot.size());
    }

    @Test
    void snapshotIsIndependentOfWorldChanges() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2), new Sprite("player"));

        var snapshot = world.snapshot(Position.class, Sprite.class);

        // Modify world after snapshot
        world.setComponent(entity, new Position(99, 99));

        // Snapshot should still have the old value
        var entries = snapshot.entries();
        assertEquals(1, entries.size());
        var components = entries.getFirst().components();
        assertEquals(new Position(1, 2), components[0]);
    }

    @Test
    void snapshotForEachTyped() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2), new Sprite("a"));
        world.spawn(new Position(3, 4), new Sprite("b"));

        var snapshot = world.snapshot(Position.class, Sprite.class);
        var positions = new ArrayList<Position>();
        snapshot.forEach(Position.class, Sprite.class, (pos, sprite) -> positions.add(pos));

        assertEquals(2, positions.size());
    }

    @Test
    void emptySnapshotWhenNoMatch() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2));

        var snapshot = world.snapshot(Position.class, Sprite.class);
        assertEquals(0, snapshot.size());
    }
}
