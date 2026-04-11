package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link RelationStore#hasSource} — the
 * "first pair for this source?" / "last pair for this source?"
 * predicate that drives archetype marker maintenance at the World
 * level. Without this predicate, the caller can't tell whether a
 * {@code set} or {@code remove} call crossed the zero-pair boundary,
 * which is exactly the moment the marker must be added or removed.
 */
class RelationStoreHasSourceTest {

    record Distance(float meters) {}

    @Test
    void emptyStoreReportsNoSource() {
        var store = new RelationStore<>(Distance.class);
        assertFalse(store.hasSource(Entity.of(1, 0)));
    }

    @Test
    void firstPairForSourceCrossesIntoHasSource() {
        var store = new RelationStore<>(Distance.class);
        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);

        assertFalse(store.hasSource(src),
            "before any set — caller sees 'first pair' transition");
        store.set(src, tgt, new Distance(5f));
        assertTrue(store.hasSource(src),
            "after set — caller now skips the add-marker path");
    }

    @Test
    void additionalPairsDoNotRepeatTheTransition() {
        var store = new RelationStore<>(Distance.class);
        var src     = Entity.of(1, 0);
        var charlie = Entity.of(3, 0);

        store.set(src, Entity.of(2, 0), new Distance(5f));
        assertTrue(store.hasSource(src));
        store.set(src, charlie, new Distance(6f));
        assertTrue(store.hasSource(src),
            "second pair must not re-enter the 'first pair' path");
    }

    @Test
    void lastPairRemovalLeavesSourceEmpty() {
        var store = new RelationStore<>(Distance.class);
        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);

        store.set(src, tgt, new Distance(5f));
        store.remove(src, tgt);

        assertFalse(store.hasSource(src),
            "after last pair drop — caller now sees 'last pair' transition");
    }

    @Test
    void removingOneOfManyDoesNotCrossTheBoundary() {
        var store = new RelationStore<>(Distance.class);
        var src = Entity.of(1, 0);
        var t1  = Entity.of(2, 0);
        var t2  = Entity.of(3, 0);

        store.set(src, t1, new Distance(5f));
        store.set(src, t2, new Distance(6f));
        store.remove(src, t1);

        assertTrue(store.hasSource(src),
            "source still has one pair — marker must stay on");
    }
}
