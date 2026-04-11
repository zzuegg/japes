package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@link PairReader#hasSource} / {@link PairReader#hasTarget}
 * existence-check contract. These are cheap O(1) lookups on the
 * underlying store's forward/reverse index — critical to the
 * "idle predator skip" pattern in the relation scenarios, where
 * iterating {@link PairReader#fromSource} just to peek at its
 * emptiness would materialise a throwaway snapshot on every tick.
 *
 * <p>Also verifies that the iterator variants short-circuit the
 * empty case so no snapshot is constructed when the entity has no
 * pairs.
 */
class StorePairReaderExistenceTest {

    record Distance(float meters) {}

    @Test
    void hasSourceIsFalseForUnknownEntity() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        assertFalse(reader.hasSource(Entity.of(1, 0)));
    }

    @Test
    void hasSourceBecomesTrueAfterSet() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));

        assertTrue(reader.hasSource(alice));
        assertFalse(reader.hasSource(bob),
            "bob is a target, not a source — hasSource must stay false");
    }

    @Test
    void hasSourceReturnsFalseAfterLastPairRemoved() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));
        store.remove(alice, bob);

        assertFalse(reader.hasSource(alice));
    }

    @Test
    void hasTargetIsFalseForUnknownEntity() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        assertFalse(reader.hasTarget(Entity.of(1, 0)));
    }

    @Test
    void hasTargetBecomesTrueAfterSet() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));

        assertTrue(reader.hasTarget(bob));
        assertFalse(reader.hasTarget(alice),
            "alice is a source, not a target — hasTarget must stay false");
    }

    @Test
    void hasTargetReturnsFalseAfterAllSourcesDropTheirPair() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice   = Entity.of(1, 0);
        var bob     = Entity.of(2, 0);
        var charlie = Entity.of(3, 0);

        store.set(alice,   charlie, new Distance(5f));
        store.set(bob,     charlie, new Distance(7f));
        store.remove(alice, charlie);
        assertTrue(reader.hasTarget(charlie),
            "bob still points at charlie — hasTarget must stay true");
        store.remove(bob, charlie);
        assertFalse(reader.hasTarget(charlie));
    }

    @Test
    void fromSourceReturnsEmptyIterableWhenNoPairs() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice = Entity.of(1, 0);

        var iter = reader.fromSource(alice).iterator();
        assertFalse(iter.hasNext());
    }

    @Test
    void withTargetReturnsEmptyIterableWhenNoPairs() {
        var store = new RelationStore<>(Distance.class);
        var reader = new StorePairReader<>(store);
        var alice = Entity.of(1, 0);

        var iter = reader.withTarget(alice).iterator();
        assertFalse(iter.hasNext());
    }
}
