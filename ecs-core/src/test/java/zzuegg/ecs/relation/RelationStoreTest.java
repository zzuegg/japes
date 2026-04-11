package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Core tests for the non-fragmenting relation storage backend — the
 * side table that holds forward + reverse indices for one relation
 * type. No scheduler, no archetype markers, no Commands — this is
 * the lowest layer of PR 1.
 */
class RelationStoreTest {

    record Distance(float meters) {}

    @Test
    void newStoreIsEmpty() {
        var store = new RelationStore<>(Distance.class);
        assertEquals(0, store.size());
    }

    @Test
    void setAddsAnEntry() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));

        assertEquals(1, store.size());
    }

    @Test
    void getReturnsStoredValue() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));

        assertEquals(new Distance(5f), store.get(alice, bob));
    }

    @Test
    void getReturnsNullWhenPairMissing() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        assertNull(store.get(alice, bob));
    }

    @Test
    void setOverwritesExistingValueAndDoesNotGrow() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));
        var previous = store.set(alice, bob, new Distance(9f));

        assertEquals(new Distance(5f), previous);
        assertEquals(new Distance(9f), store.get(alice, bob));
        assertEquals(1, store.size());
    }

    @Test
    void removeReturnsPreviousValueAndShrinks() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        store.set(alice, bob, new Distance(5f));
        var removed = store.remove(alice, bob);

        assertEquals(new Distance(5f), removed);
        assertNull(store.get(alice, bob));
        assertEquals(0, store.size());
    }

    @Test
    void removeReturnsNullWhenPairMissing() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob   = Entity.of(2, 0);

        assertNull(store.remove(alice, bob));
        assertEquals(0, store.size());
    }

    @Test
    void oneSourceManyTargets() {
        var store = new RelationStore<>(Distance.class);
        var alice   = Entity.of(1, 0);
        var bob     = Entity.of(2, 0);
        var charlie = Entity.of(3, 0);
        var dave    = Entity.of(4, 0);

        store.set(alice, bob,     new Distance(1f));
        store.set(alice, charlie, new Distance(2f));
        store.set(alice, dave,    new Distance(3f));

        assertEquals(3, store.size());
        assertEquals(new Distance(1f), store.get(alice, bob));
        assertEquals(new Distance(2f), store.get(alice, charlie));
        assertEquals(new Distance(3f), store.get(alice, dave));
    }

    @Test
    void targetsForIteratesAllTargetsOfOneSource() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);
        var bob = Entity.of(2, 0);
        var charlie = Entity.of(3, 0);

        store.set(alice, bob,     new Distance(1f));
        store.set(alice, charlie, new Distance(2f));

        var seen = new HashSet<Entity>();
        store.targetsFor(alice).forEach(entry -> seen.add(entry.getKey()));

        assertEquals(Set.of(bob, charlie), seen);
    }

    @Test
    void targetsForEmptyWhenSourceHasNoPairs() {
        var store = new RelationStore<>(Distance.class);
        var alice = Entity.of(1, 0);

        var it = store.targetsFor(alice).iterator();
        assertFalse(it.hasNext());
    }

    @Test
    void sourcesForIteratesEverySourceTargetingOneEntity() {
        var store = new RelationStore<>(Distance.class);
        var alice   = Entity.of(1, 0);
        var bob     = Entity.of(2, 0);
        var charlie = Entity.of(3, 0);
        var victim  = Entity.of(99, 0);

        store.set(alice,   victim, new Distance(1f));
        store.set(bob,     victim, new Distance(2f));
        store.set(charlie, victim, new Distance(3f));

        var seen = new HashSet<Entity>();
        store.sourcesFor(victim).forEach(seen::add);

        assertEquals(Set.of(alice, bob, charlie), seen);
    }

    @Test
    void sourcesForEmptyWhenNoOneTargetsThatEntity() {
        var store = new RelationStore<>(Distance.class);
        var nobody = Entity.of(99, 0);

        assertFalse(store.sourcesFor(nobody).iterator().hasNext());
    }

    @Test
    void removeCleansReverseIndex() {
        var store = new RelationStore<>(Distance.class);
        var alice  = Entity.of(1, 0);
        var bob    = Entity.of(2, 0);
        var victim = Entity.of(99, 0);

        store.set(alice, victim, new Distance(1f));
        store.set(bob,   victim, new Distance(2f));
        store.remove(alice, victim);

        var seen = new HashSet<Entity>();
        store.sourcesFor(victim).forEach(seen::add);
        assertEquals(Set.of(bob), seen);
    }

    @Test
    void removeLastTargetOfVictimCleansReverseEntry() {
        var store = new RelationStore<>(Distance.class);
        var alice  = Entity.of(1, 0);
        var victim = Entity.of(99, 0);

        store.set(alice, victim, new Distance(1f));
        store.remove(alice, victim);

        assertFalse(store.sourcesFor(victim).iterator().hasNext());
    }

    @Test
    void entitiesWithSameIndexButDifferentGenerationAreDistinctKeys() {
        // Simulates the entity-allocator slot-reuse case: slot 7 was
        // used by an entity that's since been freed (generation 0),
        // and now slot 7 is reused by a fresh entity (generation 1).
        // Any stale pair from the old entity must not collide with
        // pairs for the new entity.
        var store = new RelationStore<>(Distance.class);
        var oldSlot7  = Entity.of(7, 0);
        var newSlot7  = Entity.of(7, 1);
        var target    = Entity.of(99, 0);

        store.set(oldSlot7, target, new Distance(1f));
        store.set(newSlot7, target, new Distance(2f));

        assertEquals(2, store.size(),
            "pairs from the same slot with different generations must not collide");
        assertEquals(new Distance(1f), store.get(oldSlot7, target));
        assertEquals(new Distance(2f), store.get(newSlot7, target));

        var seenSources = new HashSet<Entity>();
        store.sourcesFor(target).forEach(seenSources::add);
        assertEquals(Set.of(oldSlot7, newSlot7), seenSources);
    }
}
