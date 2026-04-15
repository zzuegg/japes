package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.GroupedWorldSerializer;
import zzuegg.ecs.persistence.WorldSerializer;
import zzuegg.ecs.world.World;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial integration tests for persistence, save/load, and sync features.
 * Each test is a full cycle: spawn entities, mutate, save, load, verify.
 */
class PersistenceBugHuntTest {

    // ---- Component types ----

    @Persistent
    record Position(float x, float y) {}

    @Persistent
    record Health(int hp) {}

    @Persistent
    record Rotation(float angle) {}

    @Persistent
    record Transform(float px, float py, float angle) {}

    /** Zero-field record component -- an empty tag. */
    @Persistent
    record TagMarker() {}

    /** Non-persistent component -- should be excluded from saves. */
    record Velocity(float dx, float dy) {}

    /** Another non-persistent component. */
    record AiState(int mode) {}

    // ---- Helpers ----

    private byte[] saveV1(World world) throws IOException {
        var buf = new ByteArrayOutputStream();
        var out = new DataOutputStream(buf);
        new WorldSerializer().save(world, out);
        out.flush();
        return buf.toByteArray();
    }

    private void loadV1(World world, byte[] data) throws IOException {
        new WorldSerializer().load(world, new DataInputStream(new ByteArrayInputStream(data)));
    }

    private byte[] saveGrouped(World world) throws IOException {
        var buf = new ByteArrayOutputStream();
        var out = new DataOutputStream(buf);
        new GroupedWorldSerializer().save(world, out);
        out.flush();
        return buf.toByteArray();
    }

    private void loadGrouped(World world, byte[] data) throws IOException {
        new GroupedWorldSerializer().load(world, new DataInputStream(new ByteArrayInputStream(data)));
    }

    private byte[] saveColumnar(World world) throws IOException {
        var buf = new ByteArrayOutputStream();
        var out = new DataOutputStream(buf);
        new WorldSerializer().saveColumnar(world, out);
        out.flush();
        return buf.toByteArray();
    }

    private void loadColumnar(World world, byte[] data) throws IOException {
        new WorldSerializer().loadColumnar(world, new DataInputStream(new ByteArrayInputStream(data)));
    }

    private List<Entity> collectEntities(World world) {
        var list = new ArrayList<Entity>();
        for (var e : world.accessor().allEntities()) list.add(e);
        return list;
    }

    // ---- Test 1: Save/load with zero-field record component (empty record) ----

    @Test
    void saveLoadWithZeroFieldRecordComponent_v1() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new TagMarker(), new Position(1, 2));

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertTrue(world2.isAlive(e));
        assertTrue(world2.hasComponent(e, TagMarker.class));
        assertEquals(new Position(1, 2), world2.getComponent(e, Position.class));
    }

    @Test
    void saveLoadWithZeroFieldRecordComponent_grouped() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new TagMarker(), new Position(1, 2));

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertTrue(world2.isAlive(e));
        assertTrue(world2.hasComponent(e, TagMarker.class));
        assertEquals(new Position(1, 2), world2.getComponent(e, Position.class));
    }

    @Test
    void saveLoadWithZeroFieldRecordComponent_columnar() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new TagMarker(), new Position(1, 2));

        byte[] data = saveColumnar(world);

        var world2 = World.builder().build();
        loadColumnar(world2, data);

        assertTrue(world2.isAlive(e));
        assertTrue(world2.hasComponent(e, TagMarker.class));
        assertEquals(new Position(1, 2), world2.getComponent(e, Position.class));
    }

    // ---- Test 2: Save/load entity that has been despawned ----

    @Test
    void despawnedEntityShouldNotAppearAfterLoad_v1() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1));
        var e2 = world.spawn(new Position(2, 2));
        world.despawn(e1);

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertFalse(world2.isAlive(e1), "Despawned entity should not be alive after load");
        assertTrue(world2.isAlive(e2));
        assertEquals(new Position(2, 2), world2.getComponent(e2, Position.class));
        assertEquals(1, collectEntities(world2).size());
    }

    @Test
    void despawnedEntityShouldNotAppearAfterLoad_grouped() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1));
        var e2 = world.spawn(new Position(2, 2));
        world.despawn(e1);

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertFalse(world2.isAlive(e1), "Despawned entity should not be alive after load");
        assertTrue(world2.isAlive(e2));
        assertEquals(1, collectEntities(world2).size());
    }

    // ---- Test 3: Save, mutate, save again, load -- should get second save ----

    @Test
    void saveMutateSaveAgainLoadGetsSecondState_v1() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(0, 0), new Health(100));

        // First save
        saveV1(world);

        // Mutate
        world.setComponent(e, new Position(99, 99));
        world.setComponent(e, new Health(42));

        // Second save
        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertEquals(new Position(99, 99), world2.getComponent(e, Position.class));
        assertEquals(new Health(42), world2.getComponent(e, Health.class));
    }

    @Test
    void saveMutateSaveAgainLoadGetsSecondState_grouped() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(0, 0), new Health(100));

        saveGrouped(world);

        world.setComponent(e, new Position(99, 99));
        world.setComponent(e, new Health(42));

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertEquals(new Position(99, 99), world2.getComponent(e, Position.class));
        assertEquals(new Health(42), world2.getComponent(e, Health.class));
    }

    // ---- Test 4: Load into a world that already has entities with overlapping IDs ----

    @Test
    void loadIntoWorldWithOverlappingIds_v1() throws Exception {
        var world1 = World.builder().build();
        var e1 = world1.spawn(new Position(10, 20));
        byte[] data = saveV1(world1);

        // world2 already has an entity -- load should clear it
        var world2 = World.builder().build();
        var existingEntity = world2.spawn(new Position(99, 99));

        loadV1(world2, data);

        // The loaded entity should be there
        assertTrue(world2.isAlive(e1));
        assertEquals(new Position(10, 20), world2.getComponent(e1, Position.class));
        // clear() wiped the old world, but since both worlds assigned the same
        // index+generation (0,0), the old ref is bitwise equal to the loaded entity.
        // This is by design — Entity is a value type, not an identity type.
        assertEquals(e1, existingEntity, "Both worlds assigned the same first entity ID");
        assertEquals(1, collectEntities(world2).size());
    }

    @Test
    void loadIntoWorldWithOverlappingIds_grouped() throws Exception {
        var world1 = World.builder().build();
        var e1 = world1.spawn(new Position(10, 20));
        byte[] data = saveGrouped(world1);

        var world2 = World.builder().build();
        var existingEntity = world2.spawn(new Position(99, 99));

        loadGrouped(world2, data);

        assertTrue(world2.isAlive(e1));
        assertEquals(new Position(10, 20), world2.getComponent(e1, Position.class));
        assertEquals(e1, existingEntity, "Both worlds assigned the same first entity ID");
        assertEquals(1, collectEntities(world2).size());
    }

    // ---- Test 5: @Persistent and non-persistent mixed ----

    @Test
    void persistentAndNonPersistentMixed_v1() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(5, 10), new Velocity(1, 2), new Health(50));

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertTrue(world2.isAlive(e), "Entity should still load even if some components are non-persistent");
        assertEquals(new Position(5, 10), world2.getComponent(e, Position.class));
        assertEquals(new Health(50), world2.getComponent(e, Health.class));
        assertFalse(world2.hasComponent(e, Velocity.class),
            "Non-persistent Velocity should NOT be loaded");
    }

    @Test
    void persistentAndNonPersistentMixed_grouped() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new Position(5, 10), new Velocity(1, 2), new Health(50));

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertTrue(world2.isAlive(e), "Entity should still load even if some components are non-persistent");
        assertEquals(new Position(5, 10), world2.getComponent(e, Position.class));
        assertEquals(new Health(50), world2.getComponent(e, Health.class));
        assertFalse(world2.hasComponent(e, Velocity.class),
            "Non-persistent Velocity should NOT be loaded");
    }

    @Test
    void entityWithOnlyNonPersistentComponentsIsExcluded_v1() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Velocity(1, 1));  // only non-persistent
        var e2 = world.spawn(new Position(3, 4));   // has persistent

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertFalse(world2.isAlive(e1), "Entity with only non-persistent components should not be saved");
        assertTrue(world2.isAlive(e2));
        assertEquals(1, collectEntities(world2).size());
    }

    // ---- Test 6: BulkSpawnWithIdBuilder with more entities than chunk capacity ----

    @Test
    void bulkSpawnMoreThanChunkCapacity_grouped() throws Exception {
        int chunkSize = 4; // Very small chunks to force multi-chunk
        var world = World.builder().chunkSize(chunkSize).build();

        // Spawn more entities than fit in one chunk
        int entityCount = chunkSize * 3 + 2; // 14 entities across 4 chunks
        var expectedEntities = new ArrayList<Entity>();
        for (int i = 0; i < entityCount; i++) {
            var e = world.spawn(new Position(i, i * 10));
            expectedEntities.add(e);
        }

        byte[] data = saveGrouped(world);

        var world2 = World.builder().chunkSize(chunkSize).build();
        loadGrouped(world2, data);

        assertEquals(entityCount, collectEntities(world2).size(),
            "All entities should survive multi-chunk save/load");

        for (int i = 0; i < entityCount; i++) {
            var e = expectedEntities.get(i);
            assertTrue(world2.isAlive(e), "Entity " + i + " should be alive");
            var pos = world2.getComponent(e, Position.class);
            assertNotNull(pos, "Entity " + i + " should have Position");
            assertEquals(i, pos.x(), 0.001f, "Entity " + i + " x mismatch");
            assertEquals(i * 10, pos.y(), 0.001f, "Entity " + i + " y mismatch");
        }
    }

    @Test
    void bulkSpawnMoreThanChunkCapacity_columnar() throws Exception {
        int chunkSize = 4;
        var world = World.builder().chunkSize(chunkSize).build();

        int entityCount = chunkSize * 3 + 2;
        var expectedEntities = new ArrayList<Entity>();
        for (int i = 0; i < entityCount; i++) {
            var e = world.spawn(new Position(i, i * 10));
            expectedEntities.add(e);
        }

        byte[] data = saveColumnar(world);

        var world2 = World.builder().chunkSize(chunkSize).build();
        loadColumnar(world2, data);

        assertEquals(entityCount, collectEntities(world2).size());

        for (int i = 0; i < entityCount; i++) {
            var e = expectedEntities.get(i);
            assertTrue(world2.isAlive(e), "Entity " + i + " should be alive");
            var pos = world2.getComponent(e, Position.class);
            assertNotNull(pos, "Entity " + i + " should have Position");
            assertEquals(i, pos.x(), 0.001f, "Entity " + i + " x mismatch");
            assertEquals(i * 10, pos.y(), 0.001f, "Entity " + i + " y mismatch");
        }
    }

    // ---- Test 7: Save/load with nested records ----

    @Persistent
    record InnerPos(float x, float y) {}

    @Persistent
    record InnerRot(float angle) {}

    @Persistent
    record NestedTransform(InnerPos pos, InnerRot rot) {}

    @Test
    void saveLoadWithNestedRecords_v1() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new NestedTransform(new InnerPos(1, 2), new InnerRot(3.14f)));

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        assertTrue(world2.isAlive(e));
        var t = world2.getComponent(e, NestedTransform.class);
        assertNotNull(t);
        assertEquals(1f, t.pos().x(), 0.001f);
        assertEquals(2f, t.pos().y(), 0.001f);
        assertEquals(3.14f, t.rot().angle(), 0.001f);
    }

    @Test
    void saveLoadWithNestedRecords_grouped() throws Exception {
        var world = World.builder().build();
        var e = world.spawn(new NestedTransform(new InnerPos(1, 2), new InnerRot(3.14f)));

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertTrue(world2.isAlive(e));
        var t = world2.getComponent(e, NestedTransform.class);
        assertNotNull(t);
        assertEquals(1f, t.pos().x(), 0.001f);
        assertEquals(2f, t.pos().y(), 0.001f);
        assertEquals(3.14f, t.rot().angle(), 0.001f);
    }

    // ---- Test 8: GroupedWorldSerializer round-trip with multiple archetypes ----

    @Test
    void groupedRoundTripMultipleArchetypes() throws Exception {
        var world = World.builder().build();

        // Archetype 1: Position only
        var e1 = world.spawn(new Position(1, 1));
        // Archetype 2: Position + Health
        var e2 = world.spawn(new Position(2, 2), new Health(100));
        // Archetype 3: Health + Rotation
        var e3 = world.spawn(new Health(75), new Rotation(1.5f));
        // Archetype 4: Position + Health + Rotation
        var e4 = world.spawn(new Position(4, 4), new Health(25), new Rotation(0.5f));

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertEquals(4, collectEntities(world2).size());

        // Verify archetype 1
        assertTrue(world2.isAlive(e1));
        assertEquals(new Position(1, 1), world2.getComponent(e1, Position.class));
        assertFalse(world2.hasComponent(e1, Health.class));

        // Verify archetype 2
        assertTrue(world2.isAlive(e2));
        assertEquals(new Position(2, 2), world2.getComponent(e2, Position.class));
        assertEquals(new Health(100), world2.getComponent(e2, Health.class));

        // Verify archetype 3
        assertTrue(world2.isAlive(e3));
        assertEquals(new Health(75), world2.getComponent(e3, Health.class));
        assertEquals(new Rotation(1.5f), world2.getComponent(e3, Rotation.class));
        assertFalse(world2.hasComponent(e3, Position.class));

        // Verify archetype 4
        assertTrue(world2.isAlive(e4));
        assertEquals(new Position(4, 4), world2.getComponent(e4, Position.class));
        assertEquals(new Health(25), world2.getComponent(e4, Health.class));
        assertEquals(new Rotation(0.5f), world2.getComponent(e4, Rotation.class));
    }

    @Test
    void columnarRoundTripMultipleArchetypes() throws Exception {
        var world = World.builder().build();

        var e1 = world.spawn(new Position(1, 1));
        var e2 = world.spawn(new Position(2, 2), new Health(100));
        var e3 = world.spawn(new Health(75), new Rotation(1.5f));
        var e4 = world.spawn(new Position(4, 4), new Health(25), new Rotation(0.5f));

        byte[] data = saveColumnar(world);

        var world2 = World.builder().build();
        loadColumnar(world2, data);

        assertEquals(4, collectEntities(world2).size());
        assertTrue(world2.isAlive(e1));
        assertEquals(new Position(1, 1), world2.getComponent(e1, Position.class));
        assertTrue(world2.isAlive(e2));
        assertEquals(new Position(2, 2), world2.getComponent(e2, Position.class));
        assertEquals(new Health(100), world2.getComponent(e2, Health.class));
        assertTrue(world2.isAlive(e3));
        assertEquals(new Health(75), world2.getComponent(e3, Health.class));
        assertEquals(new Rotation(1.5f), world2.getComponent(e3, Rotation.class));
        assertTrue(world2.isAlive(e4));
        assertEquals(new Position(4, 4), world2.getComponent(e4, Position.class));
        assertEquals(new Health(25), world2.getComponent(e4, Health.class));
        assertEquals(new Rotation(0.5f), world2.getComponent(e4, Rotation.class));
    }

    // ---- Test 9: Save/load preserves entity generation ----

    @Test
    void saveLoadPreservesEntityGeneration_v1() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1));
        // Despawn and re-spawn at the same index -- generation should increment
        world.despawn(e1);
        var e2 = world.spawn(new Position(2, 2));

        // If allocator reuses the index, e2 has a higher generation
        // (they might also get different indices; the test verifies the
        // loaded entity has the right generation either way)
        assertNotEquals(e1.id(), e2.id(), "After despawn+respawn, entity ID should differ (generation or index)");

        byte[] data = saveV1(world);

        var world2 = World.builder().build();
        loadV1(world2, data);

        // e1 should NOT be alive (was despawned before save)
        assertFalse(world2.isAlive(e1), "Old generation entity should not be alive");
        // e2 (the respawned one) should be alive with correct data
        assertTrue(world2.isAlive(e2), "Respawned entity should survive save/load");
        assertEquals(new Position(2, 2), world2.getComponent(e2, Position.class));
    }

    @Test
    void saveLoadPreservesEntityGeneration_grouped() throws Exception {
        var world = World.builder().build();
        var e1 = world.spawn(new Position(1, 1));
        world.despawn(e1);
        var e2 = world.spawn(new Position(2, 2));

        assertNotEquals(e1.id(), e2.id());

        byte[] data = saveGrouped(world);

        var world2 = World.builder().build();
        loadGrouped(world2, data);

        assertFalse(world2.isAlive(e1), "Old generation entity should not be alive");
        assertTrue(world2.isAlive(e2), "Respawned entity should survive save/load");
        assertEquals(new Position(2, 2), world2.getComponent(e2, Position.class));
    }

    // ---- Test 10: WorldAccessor.persistentEntities() with only non-persistent components ----

    @Test
    void persistentEntitiesExcludesEntitiesWithOnlyNonPersistentComponents() {
        var world = World.builder().build();
        var e1 = world.spawn(new Velocity(1, 1));              // only non-persistent
        var e2 = world.spawn(new Velocity(2, 2), new AiState(1)); // only non-persistent
        var e3 = world.spawn(new Position(3, 3));               // has @Persistent
        var e4 = world.spawn(new Position(4, 4), new Velocity(5, 5)); // mixed

        var persistent = new ArrayList<Entity>();
        for (var e : world.accessor().persistentEntities()) persistent.add(e);

        assertFalse(persistent.contains(e1),
            "Entity with only Velocity (non-persistent) should be excluded");
        assertFalse(persistent.contains(e2),
            "Entity with only non-persistent components should be excluded");
        assertTrue(persistent.contains(e3),
            "Entity with @Persistent Position should be included");
        assertTrue(persistent.contains(e4),
            "Entity with at least one @Persistent component should be included");
        assertEquals(2, persistent.size());
    }

    @Test
    void persistentEntitiesReturnsEmptyForWorldWithOnlyTransientEntities() {
        var world = World.builder().build();
        world.spawn(new Velocity(1, 1));
        world.spawn(new AiState(2));

        var persistent = new ArrayList<Entity>();
        for (var e : world.accessor().persistentEntities()) persistent.add(e);

        assertTrue(persistent.isEmpty(),
            "No entities with @Persistent components should yield empty list");
    }
}
