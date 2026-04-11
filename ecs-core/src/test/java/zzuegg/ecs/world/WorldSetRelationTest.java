package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the public relation CRUD API on {@link World}
 * — setRelation, getRelation, removeRelation, removeAllRelations.
 *
 * <p>Each call must drive three things in lockstep:
 * <ol>
 *   <li>the underlying {@code RelationStore<T>} (forward + reverse
 *       indices),</li>
 *   <li>the archetype marker for the relation type (added on the
 *       first pair per source, removed on the last),</li>
 *   <li>per-pair change-tracker state tied to the world's tick
 *       counter.</li>
 * </ol>
 */
class WorldSetRelationTest {

    record Position(float x, float y) {}
    record Targeting(int power) {}
    record Distance(float meters) {}

    @Test
    void setRelationStoresTheValueForRetrieval() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));

        assertEquals(new Targeting(42),
            world.getRelation(alice, bob, Targeting.class).orElseThrow());
    }

    @Test
    void getRelationReturnsEmptyWhenPairMissing() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        assertTrue(world.getRelation(alice, bob, Targeting.class).isEmpty());
    }

    @Test
    void setRelationAddsMarkerToSourceArchetype() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertNotNull(store);
        assertTrue(world.archetypeOf(alice).id().components().contains(store.markerId()),
            "source must sit in the markered archetype after the first pair");
    }

    @Test
    void setRelationDoesNotAddMarkerToTargetArchetype() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertFalse(world.archetypeOf(bob).id().components().contains(store.markerId()),
            "target is walked via reverse index, not via an archetype marker");
    }

    @Test
    void setRelationTwiceWithSameSourceDoesNotRepeatMarkerMigration() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        var charlie = world.spawn(new Position(2f, 2f));

        world.setRelation(alice, bob, new Targeting(10));
        var archAfterFirst = world.archetypeOf(alice);
        world.setRelation(alice, charlie, new Targeting(20));
        var archAfterSecond = world.archetypeOf(alice);

        assertSame(archAfterFirst, archAfterSecond,
            "second pair on the same source must not migrate archetypes again");
    }

    @Test
    void removeRelationDropsThePair() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));
        world.removeRelation(alice, bob, Targeting.class);

        assertTrue(world.getRelation(alice, bob, Targeting.class).isEmpty());
    }

    @Test
    void removeRelationLastPairDropsMarker() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));
        world.removeRelation(alice, bob, Targeting.class);

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertFalse(world.archetypeOf(alice).id().components().contains(store.markerId()),
            "dropping the only pair must clear the marker");
    }

    @Test
    void removeRelationOneOfManyDoesNotDropMarker() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        var charlie = world.spawn(new Position(2f, 2f));

        world.setRelation(alice, bob,     new Targeting(10));
        world.setRelation(alice, charlie, new Targeting(20));
        world.removeRelation(alice, bob, Targeting.class);

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertTrue(world.archetypeOf(alice).id().components().contains(store.markerId()),
            "alice still targets charlie — marker stays");
    }

    @Test
    void removeAllRelationsClearsEveryPairForSource() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        var charlie = world.spawn(new Position(2f, 2f));

        world.setRelation(alice, bob,     new Targeting(10));
        world.setRelation(alice, charlie, new Targeting(20));

        world.removeAllRelations(alice, Targeting.class);

        assertTrue(world.getRelation(alice, bob, Targeting.class).isEmpty());
        assertTrue(world.getRelation(alice, charlie, Targeting.class).isEmpty());
        var store = world.componentRegistry().relationStore(Targeting.class);
        assertFalse(world.archetypeOf(alice).id().components().contains(store.markerId()));
    }

    @Test
    void distinctRelationTypesAreIndependent() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));

        world.setRelation(alice, bob, new Targeting(42));
        world.setRelation(alice, bob, new Distance(5f));

        assertEquals(new Targeting(42),
            world.getRelation(alice, bob, Targeting.class).orElseThrow());
        assertEquals(new Distance(5f),
            world.getRelation(alice, bob, Distance.class).orElseThrow());

        var tgt = world.componentRegistry().relationStore(Targeting.class);
        var dist = world.componentRegistry().relationStore(Distance.class);
        var comps = world.archetypeOf(alice).id().components();
        assertTrue(comps.contains(tgt.markerId()));
        assertTrue(comps.contains(dist.markerId()));
    }

    @Test
    void setRelationOnDeadSourceThrows() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.despawn(alice);

        assertThrows(IllegalArgumentException.class,
            () -> world.setRelation(alice, bob, new Targeting(42)));
    }

    @Test
    void setRelationOnDeadTargetThrows() {
        var world = World.builder().build();
        var alice = world.spawn(new Position(0f, 0f));
        var bob   = world.spawn(new Position(1f, 1f));
        world.despawn(bob);

        assertThrows(IllegalArgumentException.class,
            () -> world.setRelation(alice, bob, new Targeting(42)));
    }
}
