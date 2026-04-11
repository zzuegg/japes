package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.relation.CleanupPolicy;
import zzuegg.ecs.relation.Relation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link World#despawn} applies the per-relation-type
 * {@link CleanupPolicy} correctly for every incoming pair on the
 * despawned entity, and that every outgoing pair from the despawned
 * entity is dropped unconditionally (the source is gone, so there's
 * nothing to reattach).
 *
 * <p>Policies:
 * <ul>
 *   <li>{@code RELEASE_TARGET} — drop each pair pointing at the
 *       despawned entity, source entities survive.</li>
 *   <li>{@code CASCADE_SOURCE} — every source pointing at the
 *       despawned entity is also despawned, recursively.</li>
 *   <li>{@code IGNORE} — pairs stay in the store with a dangling
 *       target reference; reads return the stale value.</li>
 * </ul>
 */
class WorldDespawnCleanupTest {

    record Position(float x, float y) {}

    // Default policy = RELEASE_TARGET.
    record Targeting(int power) {}

    @Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
    record ChildOf() {}

    @Relation(onTargetDespawn = CleanupPolicy.IGNORE)
    record LooseRef() {}

    // ------------------ RELEASE_TARGET (default) ------------------

    @Test
    void releaseTargetDropsIncomingPairsAndKeepsSourcesAlive() {
        var world = World.builder().build();
        var attacker = world.spawn(new Position(0f, 0f));
        var victim   = world.spawn(new Position(1f, 1f));

        world.setRelation(attacker, victim, new Targeting(42));
        world.despawn(victim);

        assertTrue(world.isAlive(attacker), "attacker must survive");
        assertFalse(world.isAlive(victim));
        assertTrue(world.getRelation(attacker, victim, Targeting.class).isEmpty(),
            "incoming pair must be gone from the store");
    }

    @Test
    void releaseTargetClearsSourceMarkerWhenLastPairDrops() {
        var world = World.builder().build();
        var attacker = world.spawn(new Position(0f, 0f));
        var victim   = world.spawn(new Position(1f, 1f));

        world.setRelation(attacker, victim, new Targeting(42));
        world.despawn(victim);

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertFalse(world.archetypeOf(attacker).id().components().contains(store.markerId()),
            "attacker lost its only pair — marker must be cleared");
    }

    @Test
    void releaseTargetLeavesSourceMarkerWhenOtherPairsSurvive() {
        var world = World.builder().build();
        var attacker = world.spawn(new Position(0f, 0f));
        var victim1  = world.spawn(new Position(1f, 1f));
        var victim2  = world.spawn(new Position(2f, 2f));

        world.setRelation(attacker, victim1, new Targeting(10));
        world.setRelation(attacker, victim2, new Targeting(20));

        world.despawn(victim1);

        var store = world.componentRegistry().relationStore(Targeting.class);
        assertTrue(world.archetypeOf(attacker).id().components().contains(store.markerId()));
        assertEquals(new Targeting(20),
            world.getRelation(attacker, victim2, Targeting.class).orElseThrow());
    }

    // ------------------ CASCADE_SOURCE ------------------

    @Test
    void cascadeDespawnsEverySourcePointingAtTarget() {
        var world = World.builder().build();
        var parent = world.spawn(new Position(0f, 0f));
        var childA = world.spawn(new Position(1f, 1f));
        var childB = world.spawn(new Position(2f, 2f));

        world.setRelation(childA, parent, new ChildOf());
        world.setRelation(childB, parent, new ChildOf());

        world.despawn(parent);

        assertFalse(world.isAlive(parent));
        assertFalse(world.isAlive(childA), "cascade must despawn childA");
        assertFalse(world.isAlive(childB), "cascade must despawn childB");
    }

    @Test
    void cascadeRecursesThroughChainedParents() {
        var world = World.builder().build();
        var grand  = world.spawn(new Position(0f, 0f));
        var parent = world.spawn(new Position(1f, 1f));
        var child  = world.spawn(new Position(2f, 2f));

        world.setRelation(parent, grand,  new ChildOf());
        world.setRelation(child,  parent, new ChildOf());

        world.despawn(grand);

        assertFalse(world.isAlive(grand));
        assertFalse(world.isAlive(parent));
        assertFalse(world.isAlive(child));
    }

    @Test
    void cascadeLeavesUnrelatedEntitiesAlive() {
        var world = World.builder().build();
        var parent  = world.spawn(new Position(0f, 0f));
        var child   = world.spawn(new Position(1f, 1f));
        var bystander = world.spawn(new Position(9f, 9f));

        world.setRelation(child, parent, new ChildOf());
        world.despawn(parent);

        assertFalse(world.isAlive(parent));
        assertFalse(world.isAlive(child));
        assertTrue(world.isAlive(bystander));
    }

    // ------------------ IGNORE ------------------

    @Test
    void ignorePolicyLeavesPairInStoreWithDeadTarget() {
        var world = World.builder().build();
        var watcher = world.spawn(new Position(0f, 0f));
        var target  = world.spawn(new Position(1f, 1f));

        world.setRelation(watcher, target, new LooseRef());
        world.despawn(target);

        assertFalse(world.isAlive(target));
        assertTrue(world.isAlive(watcher));
        // Pair still resolves — the user is responsible for knowing
        // the target is dead.
        assertEquals(new LooseRef(),
            world.getRelation(watcher, target, LooseRef.class).orElseThrow());
    }

    // ------------------ Outgoing pairs ------------------

    @Test
    void despawnDropsOutgoingPairsUnconditionally() {
        // The source itself is dying, so its outgoing pairs are gone
        // regardless of policy. Policy only controls what happens to
        // pairs pointing AT the despawned entity.
        var world = World.builder().build();
        var attacker = world.spawn(new Position(0f, 0f));
        var victim   = world.spawn(new Position(1f, 1f));

        world.setRelation(attacker, victim, new Targeting(42));
        world.despawn(attacker);

        assertTrue(world.getRelation(attacker, victim, Targeting.class).isEmpty());
        // victim still alive.
        assertTrue(world.isAlive(victim));
    }
}
