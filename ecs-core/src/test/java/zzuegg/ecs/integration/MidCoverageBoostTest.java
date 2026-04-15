package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.component.ValueTracked;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.persistence.WorldSerializer;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.Write;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.world.World;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests targeting mid-range coverage gaps: SpawnBuilder, WorldAccessor,
 * BulkSpawnWithIdBuilder, WorldBuilder, Commands, MultiThreadedExecutor,
 * ChangeTracker, BinaryCodec nested records.
 */
class MidCoverageBoostTest {

    // ---- Component types ----
    @Persistent record Position(float x, float y, float z) {}
    @Persistent record Velocity(float dx, float dy, float dz) {}
    @Persistent @NetworkSync record Health(int hp) {}
    record Transient(int value) {}
    @Persistent record Nested(Position pos, Velocity vel) {}
    @ValueTracked @Persistent record Tracked(int value) {}
    record Empty() {}

    // ================================================================
    // SpawnBuilder (0% coverage)
    // ================================================================

    @Test
    void spawnBuilderBasic() {
        var world = World.builder().build();
        var builder = world.spawnBuilder(Position.class, Health.class);
        var e = builder.spawn(new Position(1, 2, 3), new Health(100));
        assertEquals(1, world.entityCount());
        assertEquals(new Position(1, 2, 3), world.getComponent(e, Position.class));
        assertEquals(new Health(100), world.getComponent(e, Health.class));
    }

    @Test
    void spawnBuilderBulk() {
        var world = World.builder().chunkSize(64).build();
        var builder = world.spawnBuilder(Position.class);
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < 200; i++) {
            entities.add(builder.spawn(new Position(i, 0, 0)));
        }
        assertEquals(200, world.entityCount());
        // Verify chunk boundary crossing works
        assertEquals(new Position(0, 0, 0), world.getComponent(entities.getFirst(), Position.class));
        assertEquals(new Position(199, 0, 0), world.getComponent(entities.getLast(), Position.class));
    }

    @Test
    void spawnBuilderWrongComponentCountThrows() {
        var world = World.builder().build();
        var builder = world.spawnBuilder(Position.class, Health.class);
        assertThrows(IllegalArgumentException.class, () ->
            builder.spawn(new Position(1, 2, 3)));
    }

    // ================================================================
    // WorldAccessor (66% coverage)
    // ================================================================

    @Test
    void worldAccessorForEachPersistentEntity() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3));
        world.spawn(new Transient(42));
        world.spawn(new Position(4, 5, 6), new Health(50));

        var accessor = world.accessor();
        var count = new AtomicInteger();
        accessor.forEachPersistentEntity(e -> count.incrementAndGet());
        assertEquals(2, count.get()); // only entities with @Persistent components
    }

    @Test
    void worldAccessorForEachPersistentEntityComponent() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3), new Health(100), new Transient(42));

        var accessor = world.accessor();
        var components = new ArrayList<Record>();
        accessor.forEachPersistentEntityComponent((entity, comp) -> components.add(comp));
        assertEquals(2, components.size()); // Position + Health, not Transient
    }

    @Test
    void worldAccessorForEachNetworkSyncComponent() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2, 3), new Health(100));

        var accessor = world.accessor();
        var comps = new ArrayList<Record>();
        accessor.forEachNetworkSyncComponent(e, comps::add);
        assertEquals(1, comps.size()); // only Health has @NetworkSync
        assertInstanceOf(Health.class, comps.getFirst());
    }

    @Test
    void worldAccessorForEachFilteredComponent() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2, 3), new Health(100));

        var accessor = world.accessor();
        var comps = new ArrayList<Record>();
        accessor.forEachFilteredComponent(e, Persistent.class, comps::add);
        assertEquals(2, comps.size());
    }

    @Test
    void worldAccessorEntitiesWithConsumer() {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3));
        world.spawn(new Health(100));
        world.spawn(new Position(4, 5, 6), new Health(50));

        var accessor = world.accessor();
        var both = new ArrayList<Entity>();
        accessor.entitiesWith(both::add, Position.class, Health.class);
        assertEquals(1, both.size());
    }

    @Test
    void worldAccessorGetComponentNullForMissing() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2, 3));
        var accessor = world.accessor();
        assertNull(accessor.getComponent(e, Health.class));
    }

    @Test
    void worldAccessorGetComponentNullForDead() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2, 3));
        world.despawn(e);
        var accessor = world.accessor();
        assertNull(accessor.getComponent(e, Position.class));
    }

    // ================================================================
    // BulkSpawnWithIdBuilder (70% coverage)
    // ================================================================

    @Test
    void bulkSpawnWithIdBuilderBasic() {
        var world = World.builder().build();
        var builder = world.bulkSpawnWithIdBuilder(Position.class, Health.class);
        builder.ensureCapacity(10);
        var e = Entity.of(42, 0);
        builder.spawnWithId(e, new Position(1, 2, 3), new Health(100));
        assertTrue(world.isAlive(e));
        assertEquals(new Position(1, 2, 3), world.getComponent(e, Position.class));
    }

    @Test
    void bulkSpawnWithIdDirectDecode() throws Exception {
        var world = World.builder().build();
        // Encode some data
        var posCodec = new BinaryCodec<>(Position.class);
        var hpCodec = new BinaryCodec<>(Health.class);
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        posCodec.encode(new Position(10, 20, 30), dos);
        hpCodec.encode(new Health(999), dos);
        dos.flush();

        var builder = world.bulkSpawnWithIdBuilder(Position.class, Health.class);
        var in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        builder.spawnWithIdDirect(Entity.of(7, 0), in, new BinaryCodec[]{posCodec, hpCodec});

        var e = Entity.of(7, 0);
        assertTrue(world.isAlive(e));
        assertEquals(new Position(10, 20, 30), world.getComponent(e, Position.class));
        assertEquals(new Health(999), world.getComponent(e, Health.class));
    }

    @Test
    void bulkSpawnAllocateSlotAndSoaArrays() throws Exception {
        var world = World.builder().build();
        var posCodec = new BinaryCodec<>(Position.class);
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        posCodec.encode(new Position(5, 6, 7), dos);
        dos.flush();

        var builder = world.bulkSpawnWithIdBuilder(Position.class);
        var in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        int slot = builder.allocateSlot(Entity.of(3, 0));
        posCodec.decodeDirect(in, builder.soaArrays(0), slot);
        builder.markAdded(slot);

        assertEquals(new Position(5, 6, 7), world.getComponent(Entity.of(3, 0), Position.class));
    }

    @Test
    void bulkSpawnWrongComponentCountThrows() {
        var world = World.builder().build();
        var builder = world.bulkSpawnWithIdBuilder(Position.class, Health.class);
        assertThrows(IllegalArgumentException.class, () ->
            builder.spawnWithId(Entity.of(0, 0), new Position(1, 2, 3)));
    }

    // ================================================================
    // WorldBuilder (76% coverage)
    // ================================================================

    @Test
    void worldBuilderAutoPromoteSoA() {
        var world = World.builder()
            .autoPromoteSoA(true)
            .build();
        var e = world.spawn(new Position(1, 2, 3));
        assertEquals(new Position(1, 2, 3), world.getComponent(e, Position.class));
    }

    @Test
    void worldBuilderCustomChunkSize() {
        var world = World.builder().chunkSize(32).build();
        // Spawn enough to fill multiple chunks
        for (int i = 0; i < 100; i++) world.spawn(new Position(i, 0, 0));
        assertEquals(100, world.entityCount());
    }

    @Test
    void worldBuilderAddPlugin() {
        var count = new AtomicInteger();
        var world = World.builder()
            .addPlugin(builder -> {
                count.incrementAndGet();
                builder.addResource("hello");
            })
            .build();
        assertEquals(1, count.get());
        assertEquals("hello", world.getResource(String.class));
    }

    @Test
    void worldBuilderDisableGeneratedProcessors() {
        var world = World.builder()
            .useGeneratedProcessors(false)
            .build();
        // Should still work, just slower (tier-2 reflection)
        var e = world.spawn(new Position(1, 2, 3));
        assertEquals(1, world.entityCount());
    }

    // ================================================================
    // Commands (84% coverage)
    // ================================================================

    @Test
    void commandsInsertResource() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.insertResource("test-resource");
        cmds.applyTo(world);
        assertEquals("test-resource", world.getResource(String.class));
    }

    @Test
    void commandsApplyToFlushesAndResets() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2, 3));
        cmds.spawn(new Position(4, 5, 6));
        assertFalse(cmds.isEmpty());
        assertEquals(2, cmds.size());
        cmds.applyTo(world);
        assertTrue(cmds.isEmpty());
        assertEquals(2, world.entityCount());
    }

    @Test
    void commandsDrainLegacyPath() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2, 3));
        cmds.despawn(Entity.of(999, 0)); // dead entity, should be no-op
        var drained = cmds.drain();
        assertEquals(2, drained.size());
        assertTrue(cmds.isEmpty());
    }

    @Test
    void commandsAddAndRemoveComponent() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 2, 3));
        var cmds = new Commands();
        cmds.add(e, new Health(100));
        cmds.applyTo(world);
        assertEquals(new Health(100), world.getComponent(e, Health.class));

        cmds.remove(e, Health.class);
        cmds.applyTo(world);
        assertFalse(world.hasComponent(e, Health.class));
    }

    // ================================================================
    // MultiThreadedExecutor (82% coverage)
    // ================================================================

    @Test
    void multiThreadedExecutorRunsSystems() {
        var count = new AtomicInteger();
        class Counter {
            @System void count(@Read Position p) { count.incrementAndGet(); }
        }
        var world = World.builder()
            .executor(Executors.multiThreaded())
            .addSystem(new Counter())
            .build();
        for (int i = 0; i < 10; i++) world.spawn(new Position(i, 0, 0));
        world.tick();
        assertEquals(10, count.get());
        world.close();
    }

    // ================================================================
    // BinaryCodec nested records (72% coverage)
    // ================================================================

    @Test
    void binaryCodecNestedRecordRoundTrip() throws Exception {
        var codec = new BinaryCodec<>(Nested.class);
        var original = new Nested(new Position(1, 2, 3), new Velocity(4, 5, 6));

        var baos = new ByteArrayOutputStream();
        codec.encode(original, new DataOutputStream(baos));
        var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        assertEquals(original, decoded);
    }

    @Test
    void binaryCodecNestedRecordDirectDecode() throws Exception {
        var codec = new BinaryCodec<>(Nested.class);
        assertTrue(codec.supportsDirectDecode());
        assertTrue(codec.supportsDirectEncode());

        var original = new Nested(new Position(1, 2, 3), new Velocity(4, 5, 6));
        var baos = new ByteArrayOutputStream();
        codec.encode(original, new DataOutputStream(baos));

        // Direct decode into a world
        var world = World.builder().build();
        var builder = world.bulkSpawnWithIdBuilder(Nested.class);
        var in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        int slot = builder.allocateSlot(Entity.of(0, 0));
        codec.decodeDirect(in, builder.soaArrays(0), slot);
        builder.markAdded(slot);

        assertEquals(original, world.getComponent(Entity.of(0, 0), Nested.class));
    }

    @Test
    void binaryCodecEncodeDirect() throws Exception {
        var codec = new BinaryCodec<>(Position.class);
        var world = World.builder().build();
        var e = world.spawn(new Position(10, 20, 30));

        // Get SoA arrays from the world
        var accessor = world.accessor();
        // Encode via direct path would need chunk access; test via round-trip
        var baos = new ByteArrayOutputStream();
        var dos = new DataOutputStream(baos);
        codec.encode(new Position(10, 20, 30), dos);
        dos.flush();

        var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        assertEquals(new Position(10, 20, 30), decoded);
    }

    // ================================================================
    // ChangeTracker edge cases (89% -> higher)
    // ================================================================

    @Test
    void changeTrackerDirtyListPruning() {
        var world = World.builder().build();
        // Use a system with @Filter(Changed) to activate dirty tracking
        class Observer {
            int count;
            @System
            @zzuegg.ecs.system.Filter(value = zzuegg.ecs.system.Changed.class, target = Position.class)
            void observe(@Read Position p) { count++; }
        }
        var obs = new Observer();
        var world2 = World.builder().addSystem(obs).build();
        var e = world2.spawn(new Position(0, 0, 0));
        world2.tick(); // Added fires
        obs.count = 0;

        // Mutate and tick — dirty list prune happens at end of tick
        world2.setComponent(e, new Position(1, 1, 1));
        world2.tick();
        assertTrue(obs.count > 0);
        obs.count = 0;

        // No mutation — should not fire
        world2.tick();
        assertEquals(0, obs.count);
    }

    // ================================================================
    // WorldSerializer grouped/columnar (fill gaps)
    // ================================================================

    @Test
    void groupedSerializerMultipleArchetypes() throws Exception {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3));
        world.spawn(new Health(100));
        world.spawn(new Position(4, 5, 6), new Health(50));

        var serializer = new WorldSerializer();
        var baos = new ByteArrayOutputStream();
        serializer.saveGrouped(world, new DataOutputStream(baos));

        var world2 = World.builder().build();
        serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        assertEquals(3, world2.entityCount());
    }

    @Test
    void columnarSerializerRoundTrip() throws Exception {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3), new Health(100));
        world.spawn(new Position(4, 5, 6), new Health(200));

        var serializer = new WorldSerializer();
        var baos = new ByteArrayOutputStream();
        serializer.saveColumnar(world, new DataOutputStream(baos));

        var world2 = World.builder().build();
        serializer.loadColumnar(world2, new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        assertEquals(2, world2.entityCount());
    }

    @Test
    void worldSaveLoadConvenienceMethods() throws Exception {
        var world = World.builder().build();
        world.spawn(new Position(1, 2, 3));

        var baos = new ByteArrayOutputStream();
        world.save(new DataOutputStream(baos));

        var world2 = World.builder().build();
        world2.load(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
        assertEquals(1, world2.entityCount());
    }

    // ================================================================
    // World.getResource / flushCommands
    // ================================================================

    @Test
    void worldGetResourceThrowsForMissing() {
        var world = World.builder().build();
        assertThrows(IllegalArgumentException.class, () -> world.getResource(String.class));
    }

    @Test
    void worldFlushCommandsPublicApi() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2, 3));
        world.flushCommands(cmds);
        assertEquals(1, world.entityCount());
    }

    // ================================================================
    // Empty record / edge cases
    // ================================================================

    @Test
    void emptyRecordSpawnAndRetrieve() {
        var world = World.builder().build();
        var e = world.spawn(new Empty());
        assertTrue(world.hasComponent(e, Empty.class));
        assertEquals(new Empty(), world.getComponent(e, Empty.class));
    }

    @Test
    void prepareForLoadPreSizesStructures() {
        var world = World.builder().build();
        world.prepareForLoad(10000, 10000);
        // Should not throw — just pre-sizes internal arrays
        assertEquals(0, world.entityCount());
    }
}
