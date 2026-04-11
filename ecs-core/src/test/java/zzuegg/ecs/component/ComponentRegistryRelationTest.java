package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;
import zzuegg.ecs.relation.RelationStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the registry's relation-store side table. The registry owns
 * one {@link RelationStore} per registered relation type (keyed by
 * the record class). Duplicate registrations return the same store,
 * and the lookup method returns {@code null} for unregistered types
 * — matching the behaviour of component registration as closely as
 * possible, but kept on a separate map so relation types can't
 * accidentally collide with component ids.
 */
class ComponentRegistryRelationTest {

    record Distance(float meters) {}
    record Allegiance(int factionId) {}

    // Annotated variants for cleanup-policy propagation tests.
    @Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
    record ChildOf() {}

    @Relation(onTargetDespawn = CleanupPolicy.IGNORE)
    record DanglingRef() {}

    @Test
    void lookupReturnsNullBeforeRegistration() {
        var registry = new ComponentRegistry();
        assertNull(registry.relationStore(Distance.class));
    }

    @Test
    void registerRelationReturnsNonNullStore() {
        var registry = new ComponentRegistry();
        var store = registry.registerRelation(Distance.class);
        assertNotNull(store);
        assertEquals(Distance.class, store.type());
    }

    @Test
    void lookupAfterRegistrationReturnsSameStore() {
        var registry = new ComponentRegistry();
        var registered = registry.registerRelation(Distance.class);
        var looked = registry.relationStore(Distance.class);
        assertSame(registered, looked);
    }

    @Test
    void doubleRegistrationReturnsSameStore() {
        var registry = new ComponentRegistry();
        var first  = registry.registerRelation(Distance.class);
        var second = registry.registerRelation(Distance.class);
        assertSame(first, second,
            "idempotent registration — never replace a live store");
    }

    @Test
    void distinctRelationTypesGetDistinctStores() {
        var registry = new ComponentRegistry();
        var distance   = registry.registerRelation(Distance.class);
        var allegiance = registry.registerRelation(Allegiance.class);
        assertNotSame(distance, allegiance);
    }

    @Test
    void registerRelationAllocatesMarkerComponentId() {
        var registry = new ComponentRegistry();
        var store = registry.registerRelation(Distance.class);

        // The relation type doubles as its own marker: a ComponentId
        // identifying the "has >= 1 pair of this relation type" flag
        // on an entity's archetype. It must be a real, registered
        // component id so the existing findMatching() archetype filter
        // can narrow systems by it.
        assertNotNull(store.markerId());
        assertEquals(store.markerId(), registry.info(Distance.class).id());
    }

    @Test
    void distinctRelationTypesGetDistinctMarkerIds() {
        var registry = new ComponentRegistry();
        var distance   = registry.registerRelation(Distance.class);
        var allegiance = registry.registerRelation(Allegiance.class);
        assertNotEquals(distance.markerId(), allegiance.markerId());
    }

    @Test
    void unannotatedRelationDefaultsToReleaseTarget() {
        var registry = new ComponentRegistry();
        var store = registry.registerRelation(Distance.class);
        assertEquals(CleanupPolicy.RELEASE_TARGET, store.onTargetDespawn(),
            "relations with no @Relation annotation use the default policy");
    }

    @Test
    void annotatedRelationReportsCascade() {
        var registry = new ComponentRegistry();
        var store = registry.registerRelation(ChildOf.class);
        assertEquals(CleanupPolicy.CASCADE_SOURCE, store.onTargetDespawn());
    }

    @Test
    void annotatedRelationReportsIgnore() {
        var registry = new ComponentRegistry();
        var store = registry.registerRelation(DanglingRef.class);
        assertEquals(CleanupPolicy.IGNORE, store.onTargetDespawn());
    }
}
