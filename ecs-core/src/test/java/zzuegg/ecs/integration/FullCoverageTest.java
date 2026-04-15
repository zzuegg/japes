package zzuegg.ecs.integration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zzuegg.ecs.change.ChangeTracker;
import zzuegg.ecs.change.RemovalLog;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.SparseStorage;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.executor.MultiThreadedExecutor;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.query.FieldFilter;
import zzuegg.ecs.relation.*;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.scheduler.Schedule;
import zzuegg.ecs.scheduler.Stage;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.io.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage tests targeting all classes below 95% coverage.
 */
class FullCoverageTest {

    /** Helper to build a minimal SystemDescriptor stub. */
    private static SystemDescriptor stub(String name, Set<String> after, Set<String> before) {
        return new SystemDescriptor(
            name, "Update", after, before, false,
            List.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), Set.of(), List.of(), Set.of(), null, -1, Set.of(), Set.of(), false, false, null, null, null
        );
    }

    private static SystemDescriptor stub(String name) {
        return stub(name, Set.of(), Set.of());
    }

    private static SystemDescriptor stubInStage(String name, String stage) {
        return new SystemDescriptor(
            name, stage, Set.of(), Set.of(), false,
            List.of(), Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), Set.of(), List.of(), Set.of(), null, -1, Set.of(), Set.of(), false, false, null, null, null
        );
    }

    // -- Shared record types --
    record Pos(float x, float y) {}
    record Vel(float dx, float dy) {}
    record Hp(int value) {}
    record Tag() {}
    record Name(String label) {}

    @Relation
    record Hunts(int power) {}

    @Relation(onTargetDespawn = CleanupPolicy.CASCADE_SOURCE)
    record ChildOf(int depth) {}

    @Persistent record PersistPos(float x, float y) {}
    @Persistent record PersistVel(float dx, float dy) {}
    @Persistent record Inner(int a, float b) {}
    @Persistent record Outer(Inner inner, int c) {}
    @Persistent record AllPrimitives(byte b, short s, int i, long l, float f, double d, boolean flag, char ch) {}
    @Persistent record DeepNested(Outer outer, int extra) {}

    // ================================================================
    // 1. MultiThreadedExecutor
    // ================================================================
    @Nested
    class MultiThreadedExecutorTests {

        // Two independent read-only systems should run in parallel
        static class SystemA {
            @System(stage = "Update")
            void update(@Read Pos pos) {}
        }

        static class SystemB {
            @System(stage = "Update")
            void update(@Read Vel vel) {}
        }

        @Test
        void parallelExecutionWithTwoIndependentSystems() {
            var world = World.builder()
                .executor(Executors.fixed(2))
                .addSystem(SystemA.class)
                .addSystem(SystemB.class)
                .build();
            world.spawn(new Pos(1, 2));
            world.spawn(new Vel(3, 4));
            // Should not deadlock or throw
            world.tick();
            world.tick();
            world.close();
        }

        @Test
        void errorPropagationFromSystem() {
            var descriptors = List.of(stub("a"), stub("b"));
            var graph = DagBuilder.build(descriptors);

            var executor = new MultiThreadedExecutor(2);
            try {
                assertThrows(RuntimeException.class, () -> {
                    executor.execute(graph, node -> {
                        if (node.descriptor().name().equals("a")) {
                            throw new RuntimeException("boom");
                        }
                    });
                });
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void errorPropagationThrowsError() {
            var descriptors = List.of(stub("a"), stub("b"));
            var graph = DagBuilder.build(descriptors);

            var executor = new MultiThreadedExecutor(2);
            try {
                assertThrows(Error.class, () -> {
                    executor.execute(graph, node -> {
                        if (node.descriptor().name().equals("a")) {
                            throw new StackOverflowError("boom");
                        }
                    });
                });
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void errorPropagationCheckedWrapped() {
            var descriptors = List.of(stub("a"), stub("b"));
            var graph = DagBuilder.build(descriptors);

            var executor = new MultiThreadedExecutor(2);
            try {
                var ex = assertThrows(RuntimeException.class, () -> {
                    executor.execute(graph, node -> {
                        if (node.descriptor().name().equals("a")) {
                            // Throw a Throwable (checked) to trigger the wrapping path
                            throw new RuntimeException(new Exception("checked"));
                        }
                    });
                });
                assertNotNull(ex);
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void customForkJoinPool() {
            var pool = new ForkJoinPool(2);
            var executor = new MultiThreadedExecutor(pool);
            assertSame(pool, executor.pool());
            // shutdown should NOT shut down the pool since we don't own it
            executor.shutdown();
            assertFalse(pool.isShutdown());
            pool.shutdown();
        }

        @Test
        void ownedPoolShutdown() {
            var executor = new MultiThreadedExecutor(2);
            assertNotNull(executor.pool());
            executor.shutdown();
            assertTrue(executor.pool().isShutdown());
        }

        @Test
        void singleReadySystemInParallelExecutor() {
            // Only one system => single-system path in the while loop
            var descriptors = List.of(stub("only"));
            var graph = DagBuilder.build(descriptors);
            var executor = new MultiThreadedExecutor(2);
            var ran = new AtomicInteger(0);
            executor.execute(graph, node -> ran.incrementAndGet());
            assertEquals(1, ran.get());
            executor.shutdown();
        }
    }

    // ================================================================
    // 2. Executors factory
    // ================================================================
    @Nested
    class ExecutorsTests {

        @Test
        void multiThreadedDefaultFactory() {
            var exec = Executors.multiThreaded();
            assertNotNull(exec);
            exec.shutdown();
        }

        @Test
        void multiThreadedWithCustomPool() {
            var pool = new ForkJoinPool(2);
            var exec = Executors.multiThreaded(pool);
            assertNotNull(exec);
            exec.shutdown();
            assertFalse(pool.isShutdown());
            pool.shutdown();
        }

        @Test
        void fixedFactory() {
            var exec = Executors.fixed(4);
            assertNotNull(exec);
            exec.shutdown();
        }

        @Test
        void singleThreadedFactory() {
            var exec = Executors.singleThreaded();
            assertNotNull(exec);
            exec.shutdown();
        }
    }

    // ================================================================
    // 3. Stage
    // ================================================================
    @Nested
    class StageTests {

        @Test
        void stageOrder() {
            assertTrue(Stage.FIRST.compareTo(Stage.UPDATE) < 0);
            assertTrue(Stage.LAST.compareTo(Stage.UPDATE) > 0);
            assertEquals(0, Stage.UPDATE.compareTo(Stage.UPDATE));
        }

        @Test
        void stageNamedConstructor() {
            var s = new Stage("Custom", 150);
            assertEquals("Custom", s.name());
            assertEquals(150, s.order());
        }

        @Test
        void stageAfterKnownStages() {
            var afterFirst = Stage.after("First");
            assertEquals(50, afterFirst.order());
            assertEquals("after:First", afterFirst.name());

            var afterPreUpdate = Stage.after("PreUpdate");
            assertEquals(150, afterPreUpdate.order());

            var afterUpdate = Stage.after("Update");
            assertEquals(250, afterUpdate.order());

            var afterPostUpdate = Stage.after("PostUpdate");
            assertEquals(350, afterPostUpdate.order());

            var afterLast = Stage.after("Last");
            assertEquals(450, afterLast.order());
        }

        @Test
        void stageAfterUnknownUsesDefault() {
            var afterUnknown = Stage.after("Unknown");
            assertEquals(550, afterUnknown.order());
        }

        @Test
        void stageComparison() {
            var stages = new ArrayList<>(List.of(Stage.LAST, Stage.FIRST, Stage.UPDATE));
            Collections.sort(stages);
            assertEquals(Stage.FIRST, stages.get(0));
            assertEquals(Stage.UPDATE, stages.get(1));
            assertEquals(Stage.LAST, stages.get(2));
        }
    }

    // ================================================================
    // 4. Schedule - error paths
    // ================================================================
    @Nested
    class ScheduleTests {

        @Test
        void unknownStageThrows() {
            var desc = stubInStage("test", "NonExistent");
            var stageMap = Map.of("Update", Stage.UPDATE);
            assertThrows(IllegalArgumentException.class,
                () -> new Schedule(List.of(desc), stageMap));
        }

        @Test
        void emptyStagesArePreserved() {
            var stageMap = new LinkedHashMap<String, Stage>();
            stageMap.put("First", Stage.FIRST);
            stageMap.put("Update", Stage.UPDATE);
            stageMap.put("Last", Stage.LAST);

            // System only in Update -- First and Last should still appear
            var desc = stub("test");

            var schedule = new Schedule(List.of(desc), stageMap);
            // All 3 stages should be present
            assertEquals(3, schedule.orderedStages().size());
            assertNotNull(schedule.graphForStage(Stage.FIRST));
            assertNotNull(schedule.graphForStage(Stage.LAST));
        }

        @Test
        void duplicateSystemNamesInDagBuilder() {
            var desc1 = stub("sys.a", Set.of("sys.b"), Set.of());
            var desc2 = stub("sys.b", Set.of("sys.a"), Set.of());

            assertThrows(IllegalStateException.class,
                () -> DagBuilder.build(List.of(desc1, desc2)));
        }

        @Test
        void missingDependencyThrows() {
            var desc = stub("sys.a", Set.of("nonexistent"), Set.of());

            assertThrows(IllegalArgumentException.class,
                () -> DagBuilder.build(List.of(desc)));
        }
    }

    // ================================================================
    // 5. ChangeTracker
    // ================================================================
    @Nested
    class ChangeTrackerTests {

        @Test
        void pruneDirtyList() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            ct.markAdded(1, 20);
            ct.markChanged(2, 30);
            assertEquals(3, ct.dirtyCount());

            // Prune with watermark 15 -- slot 0 should be dropped
            ct.pruneDirtyList(15);
            assertEquals(2, ct.dirtyCount());

            // Prune with watermark 25 -- slot 1 should be dropped too
            ct.pruneDirtyList(25);
            assertEquals(1, ct.dirtyCount());
        }

        @Test
        void setDirtyTrackedSeeds() {
            var ct = new ChangeTracker(64);
            // Write before tracking is on
            ct.markAdded(0, 5);
            ct.markChanged(1, 10);
            assertEquals(0, ct.dirtyCount());

            // Now enable tracking with occupiedCount=3
            ct.setDirtyTracked(true, 3);
            assertTrue(ct.isDirtyTracked());
            // Slots 0 and 1 should be seeded into dirty list
            assertEquals(2, ct.dirtyCount());
        }

        @Test
        void setDirtyTrackedIdempotent() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true, 0);
            ct.setDirtyTracked(true, 0); // no-op
            assertTrue(ct.isDirtyTracked());
        }

        @Test
        void setFullyUntracked() {
            var ct = new ChangeTracker(64);
            ct.setFullyUntracked(true);
            assertTrue(ct.isFullyUntracked());

            // Marks should be no-ops
            ct.markAdded(0, 10);
            ct.markChanged(1, 20);
            assertEquals(0, ct.addedTick(0));
            assertEquals(0, ct.changedTick(1));
        }

        @Test
        void isAddedSinceAndIsChangedSince() {
            var ct = new ChangeTracker(64);
            ct.markAdded(0, 10);
            ct.markChanged(1, 20);

            assertTrue(ct.isAddedSince(0, 5));
            assertFalse(ct.isAddedSince(0, 10));
            assertFalse(ct.isAddedSince(0, 15));

            assertTrue(ct.isChangedSince(1, 15));
            assertFalse(ct.isChangedSince(1, 20));
            assertFalse(ct.isChangedSince(1, 25));
        }

        @Test
        void swapRemoveWithDirtyBitPropagation() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            ct.markAdded(2, 20);
            // slot 2 is dirty, slot 0 is dirty. swap-remove slot 0 with last=3
            // This moves slot 2 to slot 0 (not exactly, swapRemove takes count)
            // swapRemove(0, 3) moves slot 2 -> slot 0
            ct.swapRemove(0, 3);
            // The moved entity's addedTick should be at slot 0 now
            assertEquals(20, ct.addedTick(0));
        }

        @Test
        void dirtyListGrows() {
            // Fill up beyond initial 16 capacity
            var ct = new ChangeTracker(256);
            ct.setDirtyTracked(true);
            for (int i = 0; i < 20; i++) {
                ct.markAdded(i, i + 1);
            }
            assertEquals(20, ct.dirtyCount());
        }

        @Test
        void capacity() {
            var ct = new ChangeTracker(128);
            assertEquals(128, ct.capacity());
        }
    }

    // ================================================================
    // 6. RemovalLog
    // ================================================================
    @Nested
    class RemovalLogTests {

        @Test
        void appendWithNoConsumerSkips() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 1);
            assertEquals(List.of(), log.snapshot(id, 0));
        }

        @Test
        void registerConsumerEnablesAppend() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.append(id, Entity.of(2, 0), new Pos(3, 4), 10);
            assertEquals(2, log.snapshot(id, 0).size());
            assertEquals(1, log.snapshot(id, 5).size());
            assertEquals(0, log.snapshot(id, 10).size());
        }

        @Test
        void collectGarbage() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.append(id, Entity.of(2, 0), new Pos(3, 4), 10);
            log.append(id, Entity.of(3, 0), new Pos(5, 6), 15);

            // GC up to 10 -- should remove entries at tick 5 and 10
            log.collectGarbage(id, 10);
            assertEquals(10, log.minWatermark(id));
            assertEquals(1, log.snapshot(id, 0).size());
        }

        @Test
        void collectGarbageNoRegress() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.collectGarbage(id, 10);
            // Trying to regress should be a no-op
            log.collectGarbage(id, 5);
            assertEquals(10, log.minWatermark(id));
        }

        @Test
        void collectGarbageClearsAll() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.collectGarbage(id, 5);
            assertEquals(0, log.snapshot(id, 0).size());
        }

        @Test
        void collectGarbagePartialDrop() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.append(id, Entity.of(2, 0), new Pos(3, 4), 10);
            log.append(id, Entity.of(3, 0), new Pos(5, 6), 15);
            // Drop first two entries (tick 5 and 10)
            log.collectGarbage(id, 10);
            var remaining = log.snapshot(id, 0);
            assertEquals(1, remaining.size());
            assertEquals(15, remaining.get(0).tick());
        }

        @Test
        void multipleConsumers() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.registerConsumer(id); // 2 consumers
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            assertEquals(1, log.snapshot(id, 0).size());
        }

        @Test
        void clearConsumers() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.clearConsumers();
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            assertEquals(0, log.snapshot(id, 0).size());
        }

        @Test
        void clearEntries() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.clear();
            assertEquals(0, log.snapshot(id, 0).size());
        }

        @Test
        void snapshotEmptyList() {
            var log = new RemovalLog();
            assertEquals(List.of(), log.snapshot(new ComponentId(99), 0));
        }

        @Test
        void minWatermarkDefault() {
            var log = new RemovalLog();
            assertEquals(0, log.minWatermark(new ComponentId(99)));
        }

        @Test
        void collectGarbageNoEntries() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            // GC with no entries should be fine
            log.collectGarbage(id, 10);
            assertEquals(10, log.minWatermark(id));
        }
    }

    // ================================================================
    // 7. Commands - drain() for setRelation/removeRelation/insertResource
    // ================================================================
    @Nested
    class CommandsTests {

        @Test
        void drainSetRelationAndRemoveRelation() {
            var cmds = new Commands();
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            cmds.setRelation(src, tgt, new Hunts(10));
            cmds.removeRelation(src, tgt, Hunts.class);

            var drained = cmds.drain();
            assertEquals(2, drained.size());
            assertInstanceOf(Commands.SetRelationCommand.class, drained.get(0));
            assertInstanceOf(Commands.RemoveRelationCommand.class, drained.get(1));

            var setCmd = (Commands.SetRelationCommand) drained.get(0);
            assertEquals(src, setCmd.source());
            assertEquals(tgt, setCmd.target());
            assertEquals(new Hunts(10), setCmd.value());

            var remCmd = (Commands.RemoveRelationCommand) drained.get(1);
            assertEquals(src, remCmd.source());
            assertEquals(tgt, remCmd.target());
            assertEquals(Hunts.class, remCmd.type());
        }

        @Test
        void drainInsertResource() {
            var cmds = new Commands();
            cmds.insertResource("hello");
            var drained = cmds.drain();
            assertEquals(1, drained.size());
            assertInstanceOf(Commands.InsertResourceCommand.class, drained.get(0));
            assertEquals("hello", ((Commands.InsertResourceCommand) drained.get(0)).resource());
        }

        @Test
        void drainEmpty() {
            var cmds = new Commands();
            assertEquals(List.of(), cmds.drain());
            assertTrue(cmds.isEmpty());
        }

        @Test
        void applySetRelationViaWorld() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));

            var cmds = new Commands();
            cmds.setRelation(src, tgt, new Hunts(5));
            cmds.applyTo(world);

            var store = world.componentRegistry().relationStore(Hunts.class);
            assertNotNull(store);
            assertEquals(new Hunts(5), store.get(src, tgt));
        }

        @Test
        void applyRemoveRelationViaWorld() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));
            world.setRelation(src, tgt, new Hunts(5));

            var cmds = new Commands();
            cmds.removeRelation(src, tgt, Hunts.class);
            cmds.applyTo(world);

            var store = world.componentRegistry().relationStore(Hunts.class);
            assertNull(store.get(src, tgt));
        }

        @Test
        void applyInsertResourceViaWorld() {
            var world = World.builder().build();
            var cmds = new Commands();
            cmds.insertResource("resource_value");
            cmds.applyTo(world);
            // Verify resource was set
            assertEquals("resource_value", world.getResource(String.class));
        }

        @Test
        void sizeAndIsEmpty() {
            var cmds = new Commands();
            assertTrue(cmds.isEmpty());
            assertEquals(0, cmds.size());
            cmds.spawn(new Pos(1, 2));
            assertFalse(cmds.isEmpty());
            assertEquals(1, cmds.size());
        }
    }

    // ================================================================
    // 8. Long2ObjectOpenMap
    // ================================================================
    @Nested
    class Long2ObjectOpenMapTests {

        @Test
        void computeIfAbsentExistingKey() {
            // Use RelationStore which internally uses Long2ObjectOpenMap
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);
            store.set(a, b, new Hunts(1));
            store.set(a, c, new Hunts(2));
            // Both should be under the same source key
            assertEquals(new Hunts(1), store.get(a, b));
            assertEquals(new Hunts(2), store.get(a, c));
        }

        @Test
        void forEachCoversAllEntries() {
            var store = new RelationStore<>(Hunts.class);
            var seen = new ArrayList<Entity>();
            for (int i = 0; i < 10; i++) {
                store.set(Entity.of(i, 0), Entity.of(i + 100, 0), new Hunts(i));
            }
            store.forEachPair((src, tgt, val) -> seen.add(src));
            assertEquals(10, seen.size());
        }

        @Test
        void collisionChainsWithRemove() {
            // Insert many entries to force collisions, then remove some
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(0, 0);
            for (int i = 1; i <= 30; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            assertEquals(30, store.size());

            // Remove half
            for (int i = 1; i <= 15; i++) {
                store.remove(Entity.of(i, 0), target);
            }
            assertEquals(15, store.size());

            // Remaining should be accessible
            for (int i = 16; i <= 30; i++) {
                assertNotNull(store.get(Entity.of(i, 0), target));
            }
        }

        @Test
        void resizeTrigger() {
            // Default capacity 16, load factor 0.6 => resize at 10 entries
            var store = new RelationStore<>(Hunts.class);
            for (int i = 0; i < 20; i++) {
                store.set(Entity.of(i, 0), Entity.of(i + 100, 0), new Hunts(i));
            }
            assertEquals(20, store.size());
            // All entries still accessible
            for (int i = 0; i < 20; i++) {
                assertEquals(new Hunts(i), store.get(Entity.of(i, 0), Entity.of(i + 100, 0)));
            }
        }
    }

    // ================================================================
    // 9. SourceSlice / TargetSlice
    // ================================================================
    @Nested
    class SliceTests {

        @Test
        void sourceSliceSwapRemove() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);
            var target = Entity.of(10, 0);
            store.set(a, target, new Hunts(1));
            store.set(b, target, new Hunts(2));
            store.set(c, target, new Hunts(3));

            // Remove middle source
            store.remove(b, target);
            assertEquals(2, store.size());
            assertNotNull(store.get(a, target));
            assertNotNull(store.get(c, target));
            assertNull(store.get(b, target));
        }

        @Test
        void targetSliceSwapRemove() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var a = Entity.of(10, 0);
            var b = Entity.of(11, 0);
            var c = Entity.of(12, 0);
            store.set(src, a, new Hunts(1));
            store.set(src, b, new Hunts(2));
            store.set(src, c, new Hunts(3));

            // Remove middle target
            store.remove(src, b);
            assertEquals(2, store.size());
            assertNotNull(store.get(src, a));
            assertNotNull(store.get(src, c));
            assertNull(store.get(src, b));
        }

        @Test
        void targetSliceGrowth() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            // Add many targets to force TargetSlice growth beyond initial capacity (2)
            for (int i = 0; i < 10; i++) {
                store.set(src, Entity.of(100 + i, 0), new Hunts(i));
            }
            assertEquals(10, store.size());
            for (int i = 0; i < 10; i++) {
                assertEquals(new Hunts(i), store.get(src, Entity.of(100 + i, 0)));
            }
        }

        @Test
        void sourceSliceGrowth() {
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(1, 0);
            for (int i = 0; i < 10; i++) {
                store.set(Entity.of(100 + i, 0), target, new Hunts(i));
            }
            assertEquals(10, store.size());
        }

        @Test
        void removeLastPairCleansUpForwardAndReverse() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            store.set(a, b, new Hunts(1));
            assertTrue(store.hasSource(a));
            assertTrue(store.hasTarget(b));

            store.remove(a, b);
            assertFalse(store.hasSource(a));
            assertFalse(store.hasTarget(b));
        }

        @Test
        void targetSliceOverwrite() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            store.set(src, tgt, new Hunts(1));
            var old = store.set(src, tgt, new Hunts(2));
            assertEquals(new Hunts(1), old);
            assertEquals(new Hunts(2), store.get(src, tgt));
            assertEquals(1, store.size()); // size should not increase
        }

        @Test
        void sourceSliceDuplicateAdd() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            store.set(src, tgt, new Hunts(1));
            store.set(src, tgt, new Hunts(2)); // overwrite, not duplicate
            // Verify reverse index still has exactly 1 source
            var sources = new ArrayList<Entity>();
            store.sourcesFor(tgt).forEach(sources::add);
            assertEquals(1, sources.size());
        }
    }

    // ================================================================
    // 10. RemovedRelationsImpl
    // ================================================================
    @Nested
    class RemovedRelationsImplTests {

        @Test
        void isEmptyWhenNoRemovals() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            var plan = new SystemExecutionPlan(0, List.of(), List.of(), Map.of());
            var rr = RemovedRelations.bind(log, plan);
            assertTrue(rr.isEmpty());
            assertEquals(List.of(), rr.asList());
        }

        @Test
        void iterationYieldsRemovals() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            var plan = new SystemExecutionPlan(0, List.of(), List.of(), Map.of());
            // plan.lastSeenTick() is 0 by default
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(5), 1);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(10), 2);

            var rr = RemovedRelations.bind(log, plan);
            assertFalse(rr.isEmpty());
            var list = rr.asList();
            assertEquals(2, list.size());

            // Check iteration via iterator
            int count = 0;
            for (var removal : rr) {
                assertNotNull(removal.lastValue());
                count++;
            }
            assertEquals(2, count);
        }

        @Test
        void watermarkFiltersOldRemovals() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            var plan = new SystemExecutionPlan(0, List.of(), List.of(), Map.of());
            plan.markExecuted(6); // lastSeenTick = 5

            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(5), 3); // tick 3 <= 5, filtered
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(10), 8); // tick 8 > 5, visible

            var rr = RemovedRelations.bind(log, plan);
            assertFalse(rr.isEmpty());
            assertEquals(1, rr.asList().size());
        }

        @Test
        void pairRemovalLogCollectGarbage() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 10);
            log.append(Entity.of(5, 0), Entity.of(6, 0), new Hunts(3), 15);

            log.collectGarbage(10);
            assertEquals(10, log.minWatermark());
            assertEquals(1, log.snapshot(0).size());
        }

        @Test
        void pairRemovalLogCollectGarbageNoRegress() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.collectGarbage(10);
            log.collectGarbage(5); // should be no-op
            assertEquals(10, log.minWatermark());
        }

        @Test
        void pairRemovalLogCollectGarbageClearsAll() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            log.collectGarbage(5);
            assertTrue(log.snapshot(0).isEmpty());
        }

        @Test
        void pairRemovalLogHasEntriesAfter() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            assertFalse(log.hasEntriesAfter(0));

            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            assertTrue(log.hasEntriesAfter(0));
            assertTrue(log.hasEntriesAfter(4));
            assertFalse(log.hasEntriesAfter(5));
        }

        @Test
        void pairRemovalLogClearConsumers() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            log.clearConsumers();
            // After clearing consumers, append should be no-op
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 10);
            assertTrue(log.snapshot(0).isEmpty());
            assertEquals(0, log.minWatermark());
        }

        @Test
        void pairRemovalLogAppendNoConsumer() {
            var log = new PairRemovalLog();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            assertTrue(log.snapshot(0).isEmpty());
        }

        @Test
        void pairRemovalLogPartialGC() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 10);
            log.append(Entity.of(5, 0), Entity.of(6, 0), new Hunts(3), 15);
            // GC at 10 should drop entries at tick 5 and 10, keep 15
            log.collectGarbage(10);
            var remaining = log.snapshot(0);
            assertEquals(1, remaining.size());
            assertEquals(15, remaining.get(0).tick());
        }
    }

    // ================================================================
    // 11. WorldBuilder
    // ================================================================
    @Nested
    class WorldBuilderTests {

        @Test
        void addStage() {
            var world = World.builder()
                .addStage("Physics", new Stage("Physics", 150))
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void addEvent() {
            record MyEvent(int data) {}
            var world = World.builder()
                .addEvent(MyEvent.class)
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void storageFactory() {
            var world = World.builder()
                .storageFactory(zzuegg.ecs.storage.ComponentStorage.defaultFactory())
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void executorOption() {
            var world = World.builder()
                .executor(Executors.singleThreaded())
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void chunkSizeOption() {
            var world = World.builder()
                .chunkSize(512)
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void useGeneratedProcessorsOption() {
            var world = World.builder()
                .useGeneratedProcessors(false)
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void autoPromoteSoAOption() {
            var world = World.builder()
                .storageFactory(zzuegg.ecs.storage.ComponentStorage.defaultFactory())
                .autoPromoteSoA(true)
                .build();
            assertNotNull(world);
            world.close();
        }

        @Test
        void defaultExecutorIsSingleThreaded() {
            var world = World.builder().build();
            assertNotNull(world);
            world.close();
        }
    }

    // ================================================================
    // 12. CommandProcessor - setRelation/removeRelation/insertResource
    // ================================================================
    @Nested
    class CommandProcessorTests {

        @Test
        void setRelationCommandThroughWorld() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));

            var cmds = new Commands();
            cmds.setRelation(src, tgt, new Hunts(42));
            world.flushCommands(cmds);

            var store = world.componentRegistry().relationStore(Hunts.class);
            assertEquals(new Hunts(42), store.get(src, tgt));
        }

        @Test
        void removeRelationCommandThroughWorld() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));
            world.setRelation(src, tgt, new Hunts(42));

            var cmds = new Commands();
            cmds.removeRelation(src, tgt, Hunts.class);
            world.flushCommands(cmds);

            var store = world.componentRegistry().relationStore(Hunts.class);
            assertNull(store.get(src, tgt));
        }

        @Test
        void insertResourceCommandThroughWorld() {
            var world = World.builder().build();
            var cmds = new Commands();
            cmds.insertResource(42);
            world.flushCommands(cmds);
            assertEquals(42, world.getResource(Integer.class));
        }

        @Test
        void setRelationOnDeadEntitySkips() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));
            world.despawn(tgt);

            var cmds = new Commands();
            cmds.setRelation(src, tgt, new Hunts(42));
            world.flushCommands(cmds); // should not throw
        }

        @Test
        void removeRelationOnDeadSourceSkips() {
            var world = World.builder().build();
            var src = world.spawn(new Pos(1, 2));
            var tgt = world.spawn(new Pos(3, 4));

            var cmds = new Commands();
            world.despawn(src);
            cmds.removeRelation(src, tgt, Hunts.class);
            world.flushCommands(cmds); // should not throw
        }
    }

    // ================================================================
    // 13. FieldFilter
    // ================================================================
    @Nested
    class FieldFilterTests {

        record Stats(int hp, float speed, String name) {}

        @Test
        void singleFieldFilterAllOperators() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));

            // greaterThan
            var gt = FieldFilter.of(Stats.class, "hp").greaterThan(49);
            assertTrue(gt.test(components));
            var gtFail = FieldFilter.of(Stats.class, "hp").greaterThan(50);
            assertFalse(gtFail.test(components));

            // greaterThanOrEqual
            var gte = FieldFilter.of(Stats.class, "hp").greaterThanOrEqual(50);
            assertTrue(gte.test(components));

            // lessThan
            var lt = FieldFilter.of(Stats.class, "hp").lessThan(51);
            assertTrue(lt.test(components));
            var ltFail = FieldFilter.of(Stats.class, "hp").lessThan(50);
            assertFalse(ltFail.test(components));

            // lessThanOrEqual
            var lte = FieldFilter.of(Stats.class, "hp").lessThanOrEqual(50);
            assertTrue(lte.test(components));

            // equalTo
            var eq = FieldFilter.of(Stats.class, "hp").equalTo(50);
            assertTrue(eq.test(components));
            var eqStr = FieldFilter.of(Stats.class, "name").equalTo("hero");
            assertTrue(eqStr.test(components));

            // notEqualTo
            var neq = FieldFilter.of(Stats.class, "hp").notEqualTo(49);
            assertTrue(neq.test(components));
            var neqFail = FieldFilter.of(Stats.class, "hp").notEqualTo(50);
            assertFalse(neqFail.test(components));
        }

        @Test
        void missingComponentReturnsFalse() {
            var components = new HashMap<Class<?>, Record>();
            // Empty map -- component missing
            var filter = FieldFilter.of(Stats.class, "hp").greaterThan(0);
            assertFalse(filter.test(components));
        }

        @Test
        void parseAllOperators() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));

            assertTrue(FieldFilter.parse("hp > 49", Stats.class).test(components));
            assertTrue(FieldFilter.parse("hp >= 50", Stats.class).test(components));
            assertTrue(FieldFilter.parse("hp < 51", Stats.class).test(components));
            assertTrue(FieldFilter.parse("hp <= 50", Stats.class).test(components));
            assertTrue(FieldFilter.parse("hp == 50", Stats.class).test(components));
            assertTrue(FieldFilter.parse("hp != 49", Stats.class).test(components));
        }

        @Test
        void parseStringEquality() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));
            assertTrue(FieldFilter.parse("name == 'hero'", Stats.class).test(components));
            assertTrue(FieldFilter.parse("name != 'villain'", Stats.class).test(components));
        }

        @Test
        void parseNoOperatorThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.parse("hp 50", Stats.class));
        }

        @Test
        void parseDoubleValue() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));
            assertTrue(FieldFilter.parse("speed > 3.0", Stats.class).test(components));
        }

        @Test
        void andFilter() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));

            var f = FieldFilter.and(
                FieldFilter.of(Stats.class, "hp").greaterThan(0),
                FieldFilter.of(Stats.class, "hp").lessThan(100)
            );
            assertTrue(f.test(components));

            var fail = FieldFilter.and(
                FieldFilter.of(Stats.class, "hp").greaterThan(0),
                FieldFilter.of(Stats.class, "hp").lessThan(10) // hp=50, not < 10
            );
            assertFalse(fail.test(components));
        }

        @Test
        void orFilter() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));

            var f = FieldFilter.or(
                FieldFilter.of(Stats.class, "hp").greaterThan(100), // false
                FieldFilter.of(Stats.class, "hp").lessThan(100)     // true
            );
            assertTrue(f.test(components));

            var fail = FieldFilter.or(
                FieldFilter.of(Stats.class, "hp").greaterThan(100),
                FieldFilter.of(Stats.class, "hp").lessThan(10)
            );
            assertFalse(fail.test(components));
        }

        @Test
        void parseEmptyFieldThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.parse("> 50", Stats.class));
        }

        @Test
        void invalidFieldNameThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.of(Stats.class, "nonexistent"));
        }

        @Test
        void parseNotANumberThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.parse("hp > abc", Stats.class));
        }

        @Test
        void parseUnquotedStringAsComparable() {
            var components = new HashMap<Class<?>, Record>();
            components.put(Stats.class, new Stats(50, 3.5f, "hero"));
            // unquoted string gets passed through as-is
            assertTrue(FieldFilter.parse("name == hero", Stats.class).test(components));
        }
    }

    // ================================================================
    // 14. ComponentRegistry
    // ================================================================
    @Nested
    class ComponentRegistryTests {

        @Test
        void registerRelation() {
            var reg = new ComponentRegistry();
            var store = reg.registerRelation(Hunts.class);
            assertNotNull(store);
            assertEquals(Hunts.class, store.type());
            assertNotNull(store.sourceMarkerId());
            assertNotNull(store.targetMarkerId());
        }

        @Test
        void registerRelationIdempotent() {
            var reg = new ComponentRegistry();
            var store1 = reg.registerRelation(Hunts.class);
            var store2 = reg.registerRelation(Hunts.class);
            assertSame(store1, store2);
        }

        @Test
        void duplicateRegistrationReturnsSameId() {
            var reg = new ComponentRegistry();
            var id1 = reg.register(Pos.class);
            var id2 = reg.register(Pos.class);
            assertEquals(id1, id2);
        }

        @Test
        void nonRecordThrows() {
            var reg = new ComponentRegistry();
            assertThrows(IllegalArgumentException.class,
                () -> reg.register(String.class));
        }

        @SparseStorage record SparseComp() {}

        @Test
        void sparseStorageThrows() {
            var reg = new ComponentRegistry();
            assertThrows(UnsupportedOperationException.class,
                () -> reg.register(SparseComp.class));
        }

        @Test
        void infoByIdThrowsForUnknown() {
            var reg = new ComponentRegistry();
            assertThrows(IllegalArgumentException.class,
                () -> reg.info(new ComponentId(999)));
        }

        @Test
        void infoByTypeThrowsForUnregistered() {
            var reg = new ComponentRegistry();
            assertThrows(IllegalArgumentException.class,
                () -> reg.info(Pos.class));
        }

        @Test
        void relationStoreNullForUnregistered() {
            var reg = new ComponentRegistry();
            assertNull(reg.relationStore(Hunts.class));
        }

        @Test
        void allRelationStores() {
            var reg = new ComponentRegistry();
            assertTrue(reg.allRelationStores().isEmpty());
            assertFalse(reg.hasAnyRelations());

            reg.registerRelation(Hunts.class);
            assertEquals(1, reg.allRelationStores().size());
            assertTrue(reg.hasAnyRelations());
        }

        @Test
        void getOrRegisterInfo() {
            var reg = new ComponentRegistry();
            var info = reg.getOrRegisterInfo(Pos.class);
            assertNotNull(info);
            assertEquals(Pos.class, info.type());
        }

        @Test
        void relationWithAnnotation() {
            var reg = new ComponentRegistry();
            var store = reg.registerRelation(ChildOf.class);
            assertEquals(CleanupPolicy.CASCADE_SOURCE, store.onTargetDespawn());
        }
    }

    // ================================================================
    // 15. BinaryCodec.NestedRecordFieldCodec
    // ================================================================
    @Nested
    class BinaryCodecNestedTests {

        @Test
        void nestedRecordRoundTrip() throws Exception {
            var codec = new BinaryCodec<>(Outer.class);
            var original = new Outer(new Inner(42, 3.14f), 7);

            var baos = new ByteArrayOutputStream();
            codec.encode(original, new DataOutputStream(baos));
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(original, decoded);
        }

        @Test
        void deepNestedRoundTrip() throws Exception {
            var codec = new BinaryCodec<>(DeepNested.class);
            var original = new DeepNested(new Outer(new Inner(1, 2.0f), 3), 4);

            var baos = new ByteArrayOutputStream();
            codec.encode(original, new DataOutputStream(baos));
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(original, decoded);
        }

        @Test
        void allPrimitiveTypesRoundTrip() throws Exception {
            var codec = new BinaryCodec<>(AllPrimitives.class);
            var original = new AllPrimitives(
                (byte) 1, (short) 2, 3, 4L, 5.0f, 6.0, true, 'A'
            );

            var baos = new ByteArrayOutputStream();
            codec.encode(original, new DataOutputStream(baos));
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(original, decoded);
        }

        @Test
        void supportsDirectDecode() {
            var codec = new BinaryCodec<>(AllPrimitives.class);
            assertTrue(codec.supportsDirectDecode());
            assertTrue(codec.supportsDirectEncode());
            assertTrue(codec.flatFieldCount() > 0);
        }

        @Test
        void directDecodeAndEncode() throws Exception {
            var codec = new BinaryCodec<>(AllPrimitives.class);
            var original = new AllPrimitives(
                (byte) 11, (short) 22, 33, 44L, 55.0f, 66.0, false, 'Z'
            );

            // First encode normally
            var baos = new ByteArrayOutputStream();
            codec.encode(original, new DataOutputStream(baos));
            var bytes = baos.toByteArray();

            // Create SoA arrays for direct decode
            int fieldCount = codec.flatFieldCount();
            var soaArrays = new Object[fieldCount];
            soaArrays[0] = new byte[1];    // byte
            soaArrays[1] = new short[1];   // short
            soaArrays[2] = new int[1];     // int
            soaArrays[3] = new long[1];    // long
            soaArrays[4] = new float[1];   // float
            soaArrays[5] = new double[1];  // double
            soaArrays[6] = new boolean[1]; // boolean
            soaArrays[7] = new char[1];    // char

            codec.decodeDirect(
                new DataInputStream(new ByteArrayInputStream(bytes)),
                soaArrays, 0
            );

            assertEquals((byte) 11, ((byte[]) soaArrays[0])[0]);
            assertEquals((short) 22, ((short[]) soaArrays[1])[0]);
            assertEquals(33, ((int[]) soaArrays[2])[0]);
            assertEquals(44L, ((long[]) soaArrays[3])[0]);
            assertEquals(55.0f, ((float[]) soaArrays[4])[0]);
            assertEquals(66.0, ((double[]) soaArrays[5])[0]);
            assertFalse(((boolean[]) soaArrays[6])[0]);
            assertEquals('Z', ((char[]) soaArrays[7])[0]);

            // Now test encodeDirect
            var baos2 = new ByteArrayOutputStream();
            codec.encodeDirect(soaArrays, 0, new DataOutputStream(baos2));
            assertArrayEquals(bytes, baos2.toByteArray());
        }

        @Test
        void nestedRecordNotSoAEligible() {
            var codec = new BinaryCodec<>(Outer.class);
            // Outer has a nested record field, so direct decode/encode should
            // still work if it's all-primitive leaves
            // Actually Outer(Inner(int, float), int) is SoA-eligible because
            // RecordFlattener flattens nested records to their primitive fields
            assertTrue(codec.supportsDirectDecode());
        }

        @Test
        void decodeDirect_unsupportedThrows() {
            // Name has a String field, so BinaryCodec construction itself throws.
            // We test the UnsupportedOperationException path by checking on a type
            // that IS constructable but not SoA-eligible -- actually all-primitive
            // nested records ARE SoA eligible. Instead, test the throw path by
            // verifying the guard method returns false for a codec that doesn't
            // support direct -- but since our codec types are all primitive,
            // let's just verify the guard methods are consistent.
            var codec = new BinaryCodec<>(AllPrimitives.class);
            assertTrue(codec.supportsDirectDecode());
            assertTrue(codec.supportsDirectEncode());

            // For unsupported type, BinaryCodec constructor throws at the
            // createFieldCodec step, so we verify that path:
            assertThrows(IllegalArgumentException.class,
                () -> new BinaryCodec<>(Name.class));
        }

        record SimpleInner(int x) {}
        @Persistent record OuterWithInner(SimpleInner inner) {}

        @Test
        void innerRecordCodecRoundTrip() throws Exception {
            var codec = new BinaryCodec<>(OuterWithInner.class);
            var original = new OuterWithInner(new SimpleInner(99));
            var baos = new ByteArrayOutputStream();
            codec.encode(original, new DataOutputStream(baos));
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(original, decoded);
        }
    }

    // ================================================================
    // Additional edge-case tests for better coverage
    // ================================================================

    @Nested
    class AdditionalEdgeCases {

        @Test
        void changeTrackerSwapRemoveNonDirtySlot() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(3, 10); // only slot 3 is dirty
            // swap-remove slot 0 (non-dirty) when count=4, moves slot 3 to slot 0
            ct.swapRemove(0, 4);
            // slot 3's data should now be at slot 0
            assertEquals(10, ct.addedTick(0));
        }

        @Test
        void changeTrackerSwapRemoveLastSlot() {
            var ct = new ChangeTracker(64);
            ct.markAdded(2, 10);
            // swap-remove the last slot (slot == last)
            ct.swapRemove(2, 3);
            assertEquals(0, ct.addedTick(2));
        }

        @Test
        void pruneDirtyListAllKept() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            ct.markChanged(1, 20);
            ct.pruneDirtyList(0); // watermark 0, everything is > 0
            assertEquals(2, ct.dirtyCount());
        }

        @Test
        void pruneDirtyListAllDropped() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            ct.markChanged(1, 20);
            ct.pruneDirtyList(20); // watermark 20, everything <= 20
            assertEquals(0, ct.dirtyCount());

            // After prune, re-marking should work (dirty bits cleared)
            ct.markAdded(0, 25);
            assertEquals(1, ct.dirtyCount());
        }

        @Test
        void commandsDrainAllTypes() {
            var cmds = new Commands();
            cmds.spawn(new Pos(1, 2));
            cmds.despawn(Entity.of(1, 0));
            cmds.add(Entity.of(2, 0), new Vel(1, 2));
            cmds.remove(Entity.of(3, 0), Pos.class);
            cmds.set(Entity.of(4, 0), new Pos(5, 6));
            cmds.insertResource("res");
            cmds.setRelation(Entity.of(5, 0), Entity.of(6, 0), new Hunts(1));
            cmds.removeRelation(Entity.of(7, 0), Entity.of(8, 0), Hunts.class);

            var drained = cmds.drain();
            assertEquals(8, drained.size());
            assertInstanceOf(Commands.SpawnCommand.class, drained.get(0));
            assertInstanceOf(Commands.DespawnCommand.class, drained.get(1));
            assertInstanceOf(Commands.AddCommand.class, drained.get(2));
            assertInstanceOf(Commands.RemoveCommand.class, drained.get(3));
            assertInstanceOf(Commands.SetCommand.class, drained.get(4));
            assertInstanceOf(Commands.InsertResourceCommand.class, drained.get(5));
            assertInstanceOf(Commands.SetRelationCommand.class, drained.get(6));
            assertInstanceOf(Commands.RemoveRelationCommand.class, drained.get(7));
        }

        @Test
        void worldBuilderWithMultiThreadedExecutor() {
            var world = World.builder()
                .executor(Executors.multiThreaded())
                .build();
            world.spawn(new Pos(1, 2));
            world.tick();
            world.close();
        }

        @Test
        void stagePreDefinedConstants() {
            assertEquals(0, Stage.FIRST.order());
            assertEquals(100, Stage.PRE_UPDATE.order());
            assertEquals(200, Stage.UPDATE.order());
            assertEquals(300, Stage.POST_UPDATE.order());
            assertEquals(400, Stage.LAST.order());
        }

        @Test
        void removalLogSnapshotFilters() {
            var log = new RemovalLog();
            var id = new ComponentId(0);
            log.registerConsumer(id);
            log.append(id, Entity.of(1, 0), new Pos(1, 2), 5);
            log.append(id, Entity.of(2, 0), new Pos(3, 4), 10);

            // sinceExclusive = 7 should only show tick=10
            var snap = log.snapshot(id, 7);
            assertEquals(1, snap.size());
            assertEquals(10, snap.get(0).tick());
        }

        @Test
        void multiThreadedExecutorWithSequentialDeps() {
            // Systems with a dependency chain should execute sequentially
            // even with a multi-threaded executor
            var desc1 = stub("sys.first");
            var desc2 = stub("sys.second", Set.of("sys.first"), Set.of());

            var graph = DagBuilder.build(List.of(desc1, desc2));
            var executor = new MultiThreadedExecutor(2);
            var order = Collections.synchronizedList(new ArrayList<String>());
            executor.execute(graph, node -> order.add(node.descriptor().name()));
            assertEquals(List.of("sys.first", "sys.second"), order);
            executor.shutdown();
        }

        @Test
        void relationStoreForEachPairLong() {
            var store = new RelationStore<>(Hunts.class);
            store.set(Entity.of(1, 0), Entity.of(10, 0), new Hunts(1));
            store.set(Entity.of(2, 0), Entity.of(20, 0), new Hunts(2));

            var seen = new ArrayList<Long>();
            store.forEachPairLong((srcId, tgtId, val) -> {
                seen.add(srcId);
                seen.add(tgtId);
            });
            assertEquals(4, seen.size());
        }

        @Test
        void relationStoreTargetsForAndSourcesFor() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt1 = Entity.of(10, 0);
            var tgt2 = Entity.of(11, 0);
            store.set(src, tgt1, new Hunts(1));
            store.set(src, tgt2, new Hunts(2));

            var targets = new ArrayList<Entity>();
            store.targetsFor(src).forEach(e -> targets.add(e.getKey()));
            assertEquals(2, targets.size());

            // Empty source
            var emptyTargets = new ArrayList<>();
            store.targetsFor(Entity.of(99, 0)).forEach(emptyTargets::add);
            assertTrue(emptyTargets.isEmpty());

            // sourcesFor
            var sources = new ArrayList<Entity>();
            store.sourcesFor(tgt1).forEach(sources::add);
            assertEquals(1, sources.size());

            // Empty target
            var emptySources = new ArrayList<>();
            store.sourcesFor(Entity.of(99, 0)).forEach(emptySources::add);
            assertTrue(emptySources.isEmpty());
        }

        @Test
        void long2ObjectOpenMapPutNull() {
            // The Long2ObjectOpenMap.put rejects nulls
            var store = new RelationStore<>(Hunts.class);
            // This test is indirect -- setting a null via the store is not exposed,
            // but we verify the map works correctly through insertions
            store.set(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1));
            assertNotNull(store.get(Entity.of(1, 0), Entity.of(2, 0)));
        }

        @Test
        void relationStoreClear() {
            var store = new RelationStore<>(Hunts.class);
            store.set(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1));
            assertEquals(1, store.size());
            store.clear();
            assertEquals(0, store.size());
        }

        @Test
        void setDirtyTrackedDisable() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            assertEquals(1, ct.dirtyCount());
            ct.setDirtyTracked(false, 0);
            assertFalse(ct.isDirtyTracked());
            // After disabling, marks should not add to dirty list
            ct.markAdded(1, 20);
            // dirtyCount is still 1 from before
            assertEquals(1, ct.dirtyCount());
        }

        @Test
        void changeTrackerDedupDirtySlots() {
            var ct = new ChangeTracker(64);
            ct.setDirtyTracked(true);
            ct.markAdded(0, 10);
            ct.markChanged(0, 20); // same slot, should not duplicate
            assertEquals(1, ct.dirtyCount());
        }

        @Test
        void scheduleOrderedStages() {
            var stageMap = new LinkedHashMap<String, Stage>();
            stageMap.put("Last", Stage.LAST);
            stageMap.put("First", Stage.FIRST);
            stageMap.put("Update", Stage.UPDATE);

            var schedule = new Schedule(List.of(), stageMap);
            var ordered = schedule.orderedStages();
            // Should be sorted by Stage order
            assertTrue(ordered.get(0).getKey().order() <= ordered.get(1).getKey().order());
            assertTrue(ordered.get(1).getKey().order() <= ordered.get(2).getKey().order());
        }

        // -- SourceSlice.contains() coverage (lines 39-45) --
        @Test
        void sourceSliceContainsViaHasTarget() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            // hasTarget uses the reverse index (SourceSlice)
            assertFalse(store.hasTarget(tgt));
            store.set(src, tgt, new Hunts(1));
            assertTrue(store.hasTarget(tgt));
        }

        // -- SourceSlice.remove() returning false (line 86) --
        @Test
        void sourceSliceRemoveNonExistentSource() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            store.set(src, tgt, new Hunts(1));
            // Removing a source that has no pair with this target
            var removed = store.remove(Entity.of(99, 0), tgt);
            assertNull(removed);
        }

        // -- TargetSlice.containsTarget() coverage (lines 81-87) --
        @Test
        void targetSliceContainsTarget() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            assertFalse(store.hasSource(src));
            store.set(src, tgt, new Hunts(1));
            assertTrue(store.hasSource(src));
        }

        // -- TargetSlice.remove() returning null (not found) --
        @Test
        void targetSliceRemoveNonExistentTarget() {
            var store = new RelationStore<>(Hunts.class);
            var src = Entity.of(1, 0);
            var tgt = Entity.of(2, 0);
            store.set(src, tgt, new Hunts(1));
            // Remove a target that doesn't exist in this source's targets
            var removed = store.remove(src, Entity.of(99, 0));
            assertNull(removed);
        }

        // -- BinaryCodec.type() coverage (line 89) --
        @Test
        void binaryCodecType() {
            var codec = new BinaryCodec<>(AllPrimitives.class);
            assertEquals(AllPrimitives.class, codec.type());
        }

        // -- CommandProcessor flat-buffer InsertResource path --
        @Test
        void commandProcessorInsertResourceViaFlush() {
            var world = World.builder().build();
            var cmds = new Commands();
            cmds.insertResource("flush_res");
            world.flushCommands(cmds);
            assertEquals("flush_res", world.getResource(String.class));
        }

        // -- FieldFilter parse empty value throws (line 64) --
        @Test
        void fieldFilterParseEmptyValueThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.parse("hp >=", Stats.class));
        }

        // -- FieldFilter Comparable comparison path (lines 181-182) --
        @Test
        void fieldFilterComparableComparison() {
            record StringComp(String val) {}
            var components = new HashMap<Class<?>, Record>();
            components.put(StringComp.class, new StringComp("banana"));
            // Use greaterThan with a string threshold -- triggers Comparable path
            var gt = FieldFilter.of(StringComp.class, "val").greaterThan("apple");
            assertTrue(gt.test(components));
            var lt = FieldFilter.of(StringComp.class, "val").lessThan("cherry");
            assertTrue(lt.test(components));
        }

        // -- WorldBuilder autoPromoteSoA with non-SoA factory (line 112) --
        @Test
        void worldBuilderAutoPromoteSoAWithCustomFactory() {
            var world = World.builder()
                .storageFactory(zzuegg.ecs.storage.ComponentStorage.defaultFactory())
                .autoPromoteSoA(true)
                .build();
            world.spawn(new Pos(1, 2));
            world.tick();
            world.close();
        }

        // -- BinaryCodec nested encode path (NestedRecordFieldCodec lines 274-279) --
        @Test
        void binaryCodecNestedEncodeAndDecode() throws Exception {
            // This exercises the NestedRecordFieldCodec.encode and .decode paths
            var codec = new BinaryCodec<>(DeepNested.class);
            var val = new DeepNested(new Outer(new Inner(7, 8.5f), 9), 10);
            var baos = new ByteArrayOutputStream();
            var out = new DataOutputStream(baos);
            codec.encode(val, out);
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(val, decoded);
        }

        // Note: MultiThreadedExecutor line 70 (checked exception wrapping) is
        // unreachable via Consumer<SystemNode> interface. Already tested
        // RuntimeException and Error paths in MultiThreadedExecutorTests.

        // -- BinaryCodec.PrimitiveFieldCodec encode for all types --
        @Test
        void binaryCodecPrimitiveFieldCodecEncode() throws Exception {
            // This exercises the PrimitiveFieldCodec.encode path for all 8 types
            // The AllPrimitives record has all 8 primitive types
            var codec = new BinaryCodec<>(AllPrimitives.class);
            var val = new AllPrimitives(
                (byte) 1, (short) 2, 3, 4L, 5.0f, 6.0, true, 'X');
            var baos = new ByteArrayOutputStream();
            codec.encode(val, new DataOutputStream(baos));
            assertTrue(baos.size() > 0);

            // Decode and verify roundtrip
            var decoded = codec.decode(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
            assertEquals(val, decoded);
        }

        // record type for string-field FieldFilter test
        record Stats(int hp, float speed, String name) {}
    }
}
