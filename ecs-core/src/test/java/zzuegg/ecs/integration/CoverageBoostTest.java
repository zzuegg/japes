package zzuegg.ecs.integration;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zzuegg.ecs.archetype.ArchetypeGraph;
import zzuegg.ecs.archetype.ArchetypeId;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.query.FieldFilter;
import zzuegg.ecs.relation.*;
import zzuegg.ecs.storage.ComponentStorage;
import zzuegg.ecs.world.World;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-boost tests targeting uncovered methods and branches in the
 * relation, archetype, and query packages.
 */
class CoverageBoostTest {

    // -- Shared record types --
    record Health(int hp) {}
    record Position(float x, float y) {}
    record Armor(int value) {}
    record Tag() {}

    @Relation
    record Hunts(int power) {}

    @Relation
    record Follows(int priority) {}

    // ================================================================
    // RelationStore coverage
    // ================================================================
    @Nested
    class RelationStoreCoverage {

        @Test
        void setOverwriteReturnsPreviousValue() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);

            assertNull(store.set(a, b, new Hunts(1)));
            var previous = store.set(a, b, new Hunts(99));
            assertEquals(new Hunts(1), previous);
            assertEquals(1, store.size(), "overwrite should not increase size");
        }

        @Test
        void removeNonExistentPairReturnsNull() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            assertNull(store.remove(a, b));
        }

        @Test
        void removeNonExistentTargetFromExistingSourceReturnsNull() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);
            store.set(a, b, new Hunts(1));
            assertNull(store.remove(a, c));
            assertEquals(1, store.size());
        }

        @Test
        void removeLastPairCleansUpForwardEntry() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            store.set(a, b, new Hunts(5));
            var removed = store.remove(a, b);
            assertEquals(new Hunts(5), removed);
            assertEquals(0, store.size());
            assertFalse(store.hasSource(a));
            assertFalse(store.hasTarget(b));
        }

        @Test
        void hasSourceAndHasTarget() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            assertFalse(store.hasSource(a));
            assertFalse(store.hasTarget(b));
            store.set(a, b, new Hunts(1));
            assertTrue(store.hasSource(a));
            assertTrue(store.hasTarget(b));
        }

        @Test
        void multipleTargetsPerSource() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);
            var d = Entity.of(4, 0);

            store.set(a, b, new Hunts(1));
            store.set(a, c, new Hunts(2));
            store.set(a, d, new Hunts(3));

            assertEquals(3, store.size());
            assertEquals(new Hunts(1), store.get(a, b));
            assertEquals(new Hunts(2), store.get(a, c));
            assertEquals(new Hunts(3), store.get(a, d));

            // Remove middle target
            store.remove(a, c);
            assertEquals(2, store.size());
            assertNull(store.get(a, c));
            assertTrue(store.hasSource(a));
        }

        @Test
        void multipleSourcesSameTarget() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var target = Entity.of(10, 0);

            store.set(a, target, new Hunts(1));
            store.set(b, target, new Hunts(2));

            assertTrue(store.hasTarget(target));

            // Remove one source
            store.remove(a, target);
            assertTrue(store.hasTarget(target));

            // Remove last source
            store.remove(b, target);
            assertFalse(store.hasTarget(target));
        }

        @Test
        void targetsForIteration() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);

            // Empty case
            assertFalse(store.targetsFor(a).iterator().hasNext());

            store.set(a, b, new Hunts(10));
            store.set(a, c, new Hunts(20));

            var pairs = new ArrayList<Map.Entry<Entity, Hunts>>();
            for (var entry : store.targetsFor(a)) {
                pairs.add(entry);
            }
            assertEquals(2, pairs.size());

            // Test NoSuchElementException on exhausted iterator
            var iter = store.targetsFor(a).iterator();
            while (iter.hasNext()) iter.next();
            assertThrows(NoSuchElementException.class, iter::next);
        }

        @Test
        void sourcesForIteration() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var target = Entity.of(10, 0);

            // Empty case
            assertFalse(store.sourcesFor(target).iterator().hasNext());

            store.set(a, target, new Hunts(1));
            store.set(b, target, new Hunts(2));

            var sources = new ArrayList<Entity>();
            for (var s : store.sourcesFor(target)) {
                sources.add(s);
            }
            assertEquals(2, sources.size());

            // Test NoSuchElementException on exhausted iterator
            var iter = store.sourcesFor(target).iterator();
            while (iter.hasNext()) iter.next();
            assertThrows(NoSuchElementException.class, iter::next);
        }

        @Test
        void forEachPairWalksAllPairs() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);

            store.set(a, b, new Hunts(10));
            store.set(a, c, new Hunts(20));
            store.set(b, c, new Hunts(30));

            var triples = new ArrayList<String>();
            store.forEachPair((src, tgt, val) ->
                triples.add(src.index() + "->" + tgt.index() + ":" + val.power()));
            assertEquals(3, triples.size());
        }

        @Test
        void forEachPairLongWalksAllPairs() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);

            store.set(a, b, new Hunts(10));

            var seen = new ArrayList<Long>();
            store.forEachPairLong((srcId, tgtId, val) -> {
                seen.add(srcId);
                seen.add(tgtId);
            });
            assertEquals(2, seen.size());
        }

        @Test
        void clearResetsEverything() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            store.set(a, b, new Hunts(1));
            store.clear();
            assertEquals(0, store.size());
            assertFalse(store.hasSource(a));
            assertFalse(store.hasTarget(b));
        }

        @Test
        void tickAwareSetAndRemove() {
            var store = new RelationStore<>(Hunts.class);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);

            // Set with tick
            store.set(a, b, new Hunts(1), 5L);
            var key = new PairKey(a, b);
            assertEquals(5L, store.tracker().addedTick(key));

            // Overwrite with tick fires changedTick
            store.set(a, b, new Hunts(2), 7L);
            assertEquals(7L, store.tracker().changedTick(key));

            // Remove with tick logs to removalLog
            store.removalLog().registerConsumer();
            store.remove(a, b, 10L);
            var entries = store.removalLog().snapshot(0L);
            assertEquals(1, entries.size());
            assertEquals(10L, entries.get(0).tick());
        }

        @Test
        void constructorVariants() {
            var store1 = new RelationStore<>(Hunts.class);
            assertNull(store1.markerId());
            assertNull(store1.targetMarkerId());
            assertEquals(CleanupPolicy.RELEASE_TARGET, store1.onTargetDespawn());

            var marker = new ComponentId(42);
            var store2 = new RelationStore<>(Hunts.class, marker);
            assertEquals(marker, store2.markerId());
            assertEquals(marker, store2.sourceMarkerId());

            var store3 = new RelationStore<>(Hunts.class, marker, CleanupPolicy.CASCADE_SOURCE);
            assertEquals(CleanupPolicy.CASCADE_SOURCE, store3.onTargetDespawn());

            var targetMarker = new ComponentId(43);
            var store4 = new RelationStore<>(Hunts.class, marker, targetMarker, CleanupPolicy.RELEASE_TARGET);
            assertEquals(marker, store4.sourceMarkerId());
            assertEquals(targetMarker, store4.targetMarkerId());
        }
    }

    // ================================================================
    // Long2ObjectOpenMap coverage (exercised via RelationStore)
    // ================================================================
    @Nested
    class Long2ObjectOpenMapCoverage {

        @Test
        void manyPairsTriggerResizeAndCollisions() {
            // Insert enough distinct (source, target) pairs to force the
            // Long2ObjectOpenMap backing the forward index past its resize
            // threshold (initial capacity 16 * 0.6 = 9). Also exercises
            // collision handling in get/put since different source entity ids
            // will hash to the same slot.
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            for (int i = 0; i < 30; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            assertEquals(30, store.size());

            // Verify all get() calls traverse collision chains
            for (int i = 0; i < 30; i++) {
                assertEquals(new Hunts(i), store.get(Entity.of(i, 0), target));
            }

            // Remove entries to exercise shift-back logic in Long2ObjectOpenMap.remove
            for (int i = 0; i < 30; i++) {
                var removed = store.remove(Entity.of(i, 0), target);
                assertEquals(new Hunts(i), removed);
            }
            assertEquals(0, store.size());
        }

        @Test
        void manyTargetsPerSourceTriggerTargetSliceGrowth() {
            var store = new RelationStore<>(Hunts.class);
            var source = Entity.of(1, 0);

            // TargetSlice starts with capacity 2, this forces multiple growths
            for (int i = 0; i < 20; i++) {
                store.set(source, Entity.of(100 + i, 0), new Hunts(i));
            }
            assertEquals(20, store.size());

            // Remove some from the middle (swap-remove path in TargetSlice)
            for (int i = 5; i < 15; i++) {
                assertNotNull(store.remove(source, Entity.of(100 + i, 0)));
            }
            assertEquals(10, store.size());

            // Remaining entries still accessible
            for (int i = 0; i < 5; i++) {
                assertEquals(new Hunts(i), store.get(source, Entity.of(100 + i, 0)));
            }
        }

        @Test
        void manySourcesPerTargetTriggerSourceSliceGrowth() {
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            // SourceSlice starts with capacity 2, this forces growth
            for (int i = 0; i < 20; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            assertTrue(store.hasTarget(target));

            // Remove some from the middle (swap-remove path in SourceSlice)
            for (int i = 5; i < 15; i++) {
                assertNotNull(store.remove(Entity.of(i, 0), target));
            }
            assertEquals(10, store.size());
            assertTrue(store.hasTarget(target));
        }

        @Test
        void overwriteExistingPairInLargeMap() {
            // Exercise the put-collision-overwrite path when map is large
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            for (int i = 0; i < 20; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            // Overwrite all — exercises the collision-chain overwrite branch
            for (int i = 0; i < 20; i++) {
                var prev = store.set(Entity.of(i, 0), target, new Hunts(i + 100));
                assertEquals(new Hunts(i), prev);
            }
            assertEquals(20, store.size());
        }

        @Test
        void getNonExistentKeyInPopulatedMap() {
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            for (int i = 0; i < 20; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            // get for non-existent source must traverse collision chain to null
            assertNull(store.get(Entity.of(999, 0), target));
        }

        @Test
        void removeNonExistentKeyInPopulatedMap() {
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            for (int i = 0; i < 20; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }
            // remove for non-existent source must traverse collision chain
            assertNull(store.remove(Entity.of(999, 0), target));
            assertEquals(20, store.size());
        }

        @Test
        void removeInMiddleOfCollisionChainTriggersShiftBack() {
            // Create a dense set, remove entries in the middle, then verify
            // remaining entries are still accessible (shift-back correctness)
            var store = new RelationStore<>(Hunts.class);
            var target = Entity.of(999, 0);

            for (int i = 0; i < 50; i++) {
                store.set(Entity.of(i, 0), target, new Hunts(i));
            }

            // Remove every other entry
            for (int i = 0; i < 50; i += 2) {
                assertNotNull(store.remove(Entity.of(i, 0), target));
            }
            assertEquals(25, store.size());

            // All remaining entries must still be accessible
            for (int i = 1; i < 50; i += 2) {
                assertEquals(new Hunts(i), store.get(Entity.of(i, 0), target));
            }
        }
    }

    // ================================================================
    // PairChangeTracker coverage
    // ================================================================
    @Nested
    class PairChangeTrackerCoverage {

        @Test
        void fullyUntrackedBypassesAllUpdates() {
            var tracker = new PairChangeTracker();
            tracker.setFullyUntracked(true);
            assertTrue(tracker.isFullyUntracked());

            var key = new PairKey(Entity.of(1, 0), Entity.of(2, 0));
            tracker.markAdded(key, 5L);
            tracker.markChanged(key, 6L);
            assertEquals(0L, tracker.addedTick(key));
            assertEquals(0L, tracker.changedTick(key));
            assertEquals(0, tracker.dirtyCount());

            // remove is also a no-op
            tracker.remove(key);
        }

        @Test
        void markAddedAndChangedDeduplicate() {
            var tracker = new PairChangeTracker();
            var key = new PairKey(Entity.of(1, 0), Entity.of(2, 0));

            tracker.markAdded(key, 1L);
            tracker.markChanged(key, 2L);
            // Second add + change should not duplicate in dirty list
            tracker.markAdded(key, 3L);
            tracker.markChanged(key, 4L);

            assertEquals(1, tracker.dirtyCount());
            assertEquals(3L, tracker.addedTick(key));
            assertEquals(4L, tracker.changedTick(key));
        }

        @Test
        void pruneDirtyList() {
            var tracker = new PairChangeTracker();
            var k1 = new PairKey(Entity.of(1, 0), Entity.of(2, 0));
            var k2 = new PairKey(Entity.of(3, 0), Entity.of(4, 0));

            tracker.markAdded(k1, 5L);
            tracker.markChanged(k2, 10L);

            // Prune with watermark 6 should keep k2 (tick 10 > 6) but drop k1 (tick 5 <= 6)
            tracker.pruneDirtyList(6L);
            assertEquals(1, tracker.dirtyCount());

            var dirtyKeys = new ArrayList<PairKey>();
            tracker.forEachDirty(dirtyKeys::add);
            assertTrue(dirtyKeys.contains(k2));
            assertFalse(dirtyKeys.contains(k1));
        }

        @Test
        void pruneDropsRemovedKeys() {
            var tracker = new PairChangeTracker();
            var k1 = new PairKey(Entity.of(1, 0), Entity.of(2, 0));
            tracker.markAdded(k1, 5L);
            tracker.remove(k1);
            // Prune should drop k1 since it was removed (both ticks cleared)
            tracker.pruneDirtyList(0L);
            assertEquals(0, tracker.dirtyCount());
        }

        @Test
        void clearResetsAllState() {
            var tracker = new PairChangeTracker();
            var key = new PairKey(Entity.of(1, 0), Entity.of(2, 0));
            tracker.markAdded(key, 1L);
            tracker.clear();
            assertEquals(0L, tracker.addedTick(key));
            assertEquals(0, tracker.dirtyCount());
        }

        @Test
        void pruneDirtyListKeepsEntriesWithChangedTickAboveWatermark() {
            var tracker = new PairChangeTracker();
            var key = new PairKey(Entity.of(1, 0), Entity.of(2, 0));

            // Only changedTick set, no addedTick
            tracker.markChanged(key, 10L);
            tracker.pruneDirtyList(5L);
            assertEquals(1, tracker.dirtyCount());
        }

        @Test
        void pruneDirtyListUsesMaxOfAddedAndChangedTick() {
            var tracker = new PairChangeTracker();
            var key = new PairKey(Entity.of(1, 0), Entity.of(2, 0));

            tracker.markAdded(key, 3L);
            tracker.markChanged(key, 8L);
            // max(3, 8) = 8, watermark 7 => keep
            tracker.pruneDirtyList(7L);
            assertEquals(1, tracker.dirtyCount());
            // watermark 8 => drop
            tracker.pruneDirtyList(8L);
            assertEquals(0, tracker.dirtyCount());
        }
    }

    // ================================================================
    // PairRemovalLog coverage
    // ================================================================
    @Nested
    class PairRemovalLogCoverage {

        @Test
        void appendWithoutConsumerIsNoOp() {
            var log = new PairRemovalLog();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5L);
            assertTrue(log.snapshot(0L).isEmpty());
        }

        @Test
        void snapshotFiltersOnTick() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 3L);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 7L);
            log.append(Entity.of(5, 0), Entity.of(6, 0), new Hunts(3), 10L);

            var after5 = log.snapshot(5L);
            assertEquals(2, after5.size());
        }

        @Test
        void hasEntriesAfter() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 5L);

            assertTrue(log.hasEntriesAfter(4L));
            assertFalse(log.hasEntriesAfter(5L));
            assertFalse(log.hasEntriesAfter(6L));
        }

        @Test
        void collectGarbageAdvancesWatermarkAndDropsOldEntries() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 3L);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 7L);
            log.append(Entity.of(5, 0), Entity.of(6, 0), new Hunts(3), 10L);

            log.collectGarbage(5L);
            assertEquals(5L, log.minWatermark());
            var remaining = log.snapshot(0L);
            assertEquals(2, remaining.size());

            // Regressed watermark is ignored
            log.collectGarbage(3L);
            assertEquals(5L, log.minWatermark());
        }

        @Test
        void collectGarbageClearsAllWhenAllOld() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 3L);
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 5L);

            log.collectGarbage(10L);
            assertTrue(log.snapshot(0L).isEmpty());
        }

        @Test
        void clearAndClearConsumers() {
            var log = new PairRemovalLog();
            log.registerConsumer();
            log.append(Entity.of(1, 0), Entity.of(2, 0), new Hunts(1), 3L);

            log.clear();
            assertTrue(log.snapshot(0L).isEmpty());

            // Consumer still active after clear
            log.append(Entity.of(3, 0), Entity.of(4, 0), new Hunts(2), 5L);
            assertEquals(1, log.snapshot(0L).size());

            log.clearConsumers();
            // Now append is a no-op
            log.append(Entity.of(5, 0), Entity.of(6, 0), new Hunts(3), 7L);
            assertTrue(log.snapshot(0L).isEmpty());
        }

        @Test
        void hasEntriesAfterOnEmptyLog() {
            var log = new PairRemovalLog();
            assertFalse(log.hasEntriesAfter(0L));
        }

        @Test
        void snapshotOnEmptyLogReturnsImmutableEmpty() {
            var log = new PairRemovalLog();
            var empty = log.snapshot(0L);
            assertTrue(empty.isEmpty());
        }
    }

    // ================================================================
    // StorePairReader coverage
    // ================================================================
    @Nested
    class StorePairReaderCoverage {

        @Test
        void fromSourceWalksAllPairs() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var c = Entity.of(3, 0);

            store.set(a, b, new Hunts(10));
            store.set(a, c, new Hunts(20));

            var pairs = new ArrayList<PairReader.Pair<Hunts>>();
            for (var p : reader.fromSource(a)) {
                pairs.add(p);
            }
            assertEquals(2, pairs.size());
        }

        @Test
        void fromSourceEmptyReturnsEmptyIterable() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);

            assertFalse(reader.fromSource(a).iterator().hasNext());
        }

        @Test
        void withTargetWalksAllSources() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);
            var target = Entity.of(10, 0);

            store.set(a, target, new Hunts(10));
            store.set(b, target, new Hunts(20));

            var pairs = new ArrayList<PairReader.Pair<Hunts>>();
            for (var p : reader.withTarget(target)) {
                pairs.add(p);
            }
            assertEquals(2, pairs.size());
        }

        @Test
        void withTargetEmptyReturnsEmptyIterable() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var target = Entity.of(10, 0);

            assertFalse(reader.withTarget(target).iterator().hasNext());
        }

        @Test
        void fromSourceIteratorThrowsNoSuchElementWhenExhausted() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);

            store.set(a, b, new Hunts(10));

            var iter = reader.fromSource(a).iterator();
            assertTrue(iter.hasNext());
            iter.next();
            assertFalse(iter.hasNext());
            assertThrows(NoSuchElementException.class, iter::next);
        }

        @Test
        void withTargetIteratorThrowsNoSuchElementWhenExhausted() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);
            var target = Entity.of(10, 0);

            store.set(a, target, new Hunts(10));

            var iter = reader.withTarget(target).iterator();
            assertTrue(iter.hasNext());
            iter.next();
            assertFalse(iter.hasNext());
            assertThrows(NoSuchElementException.class, iter::next);
        }

        @Test
        void hasSourceAndHasTargetAndGetAndSize() {
            var store = new RelationStore<>(Hunts.class);
            var reader = new StorePairReader<>(store);
            var a = Entity.of(1, 0);
            var b = Entity.of(2, 0);

            assertFalse(reader.hasSource(a));
            assertFalse(reader.hasTarget(b));
            assertEquals(0, reader.size());
            assertTrue(reader.get(a, b).isEmpty());

            store.set(a, b, new Hunts(42));

            assertTrue(reader.hasSource(a));
            assertTrue(reader.hasTarget(b));
            assertEquals(1, reader.size());
            assertEquals(new Hunts(42), reader.get(a, b).orElseThrow());
        }
    }

    // ================================================================
    // ArchetypeId coverage
    // ================================================================
    @Nested
    class ArchetypeIdCoverage {

        @Test
        void withOnAlreadyPresentReturnsSame() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(2)));
            var same = id.with(new ComponentId(1));
            assertSame(id, same);
        }

        @Test
        void withoutOnAbsentReturnsSame() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(2)));
            var same = id.without(new ComponentId(99));
            assertSame(id, same);
        }

        @Test
        void withAddsComponent() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1)));
            var extended = id.with(new ComponentId(5));
            assertEquals(2, extended.size());
            assertTrue(extended.contains(new ComponentId(1)));
            assertTrue(extended.contains(new ComponentId(5)));
        }

        @Test
        void withoutRemovesComponent() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(5)));
            var reduced = id.without(new ComponentId(5));
            assertEquals(1, reduced.size());
            assertTrue(reduced.contains(new ComponentId(1)));
            assertFalse(reduced.contains(new ComponentId(5)));
        }

        @Test
        void containsAll() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(2), new ComponentId(3)));
            assertTrue(id.containsAll(Set.of(new ComponentId(1), new ComponentId(3))));
            assertFalse(id.containsAll(Set.of(new ComponentId(1), new ComponentId(99))));
        }

        @Test
        void equalsAndHashCode() {
            var id1 = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(2)));
            var id2 = ArchetypeId.of(Set.of(new ComponentId(2), new ComponentId(1)));
            assertEquals(id1, id2);
            assertEquals(id1.hashCode(), id2.hashCode());

            var id3 = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(3)));
            assertNotEquals(id1, id3);
        }

        @Test
        void equalsSameInstance() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1)));
            assertEquals(id, id);
        }

        @Test
        void equalsNonArchetypeId() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1)));
            assertNotEquals(id, "not an ArchetypeId");
        }

        @Test
        void equalsHashCodeFastPathMismatch() {
            // Force hash computation on both, then compare unequal ids
            var id1 = ArchetypeId.of(Set.of(new ComponentId(1)));
            var id2 = ArchetypeId.of(Set.of(new ComponentId(2)));
            // Trigger hash computation
            id1.hashCode();
            id2.hashCode();
            assertNotEquals(id1, id2);
        }

        @Test
        void toStringContainsComponents() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1)));
            assertTrue(id.toString().contains("ArchetypeId"));
        }

        @Test
        void componentsListIsCached() {
            var id = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(2)));
            var list1 = id.components();
            var list2 = id.components();
            assertSame(list1, list2);
        }

        @Test
        void sortedArray() {
            var id = ArchetypeId.of(Set.of(new ComponentId(3), new ComponentId(1), new ComponentId(2)));
            var arr = id.sortedArray();
            assertEquals(3, arr.length);
            assertEquals(new ComponentId(1), arr[0]);
            assertEquals(new ComponentId(2), arr[1]);
            assertEquals(new ComponentId(3), arr[2]);
        }
    }

    // ================================================================
    // ArchetypeGraph coverage
    // ================================================================
    @Nested
    class ArchetypeGraphCoverage {

        record CompA(int a) {}
        record CompB(int b) {}
        record CompC(int c) {}

        private ComponentRegistry createRegistry() {
            var reg = new ComponentRegistry();
            reg.register(CompA.class);
            reg.register(CompB.class);
            reg.register(CompC.class);
            return reg;
        }

        private ComponentId idOf(ComponentRegistry reg, Class<?> type) {
            return reg.info(type).id();
        }

        @Test
        void getOrCreateReturnsSameForSameId() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var id = ArchetypeId.of(Set.of());
            var a1 = graph.getOrCreate(id);
            var a2 = graph.getOrCreate(id);
            assertSame(a1, a2);
        }

        @Test
        void generationIncreasesWithNewArchetypes() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            assertEquals(0, graph.generation());
            graph.getOrCreate(ArchetypeId.of(Set.of()));
            assertEquals(1, graph.generation());
            graph.getOrCreate(ArchetypeId.of(Set.of()));
            assertEquals(1, graph.generation());
            graph.getOrCreate(ArchetypeId.of(Set.of(ca)));
            assertEquals(2, graph.generation());
        }

        @Test
        void addEdgeCachesResult() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var source = ArchetypeId.of(Set.of());

            var target1 = graph.addEdge(source, ca);
            var target2 = graph.addEdge(source, ca);
            assertEquals(target1, target2);
        }

        @Test
        void removeEdgeCachesResult() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var source = ArchetypeId.of(Set.of(ca));

            var target1 = graph.removeEdge(source, ca);
            var target2 = graph.removeEdge(source, ca);
            assertEquals(target1, target2);
        }

        @Test
        void findMatchingReturnsMatchingArchetypes() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var cb = idOf(reg, CompB.class);
            var cc = idOf(reg, CompC.class);

            graph.getOrCreate(ArchetypeId.of(Set.of(ca, cb)));
            graph.getOrCreate(ArchetypeId.of(Set.of(ca, cc)));
            graph.getOrCreate(ArchetypeId.of(Set.of(cb, cc)));

            var matchCa = graph.findMatching(Set.of(ca));
            assertEquals(2, matchCa.size());

            var matchCaAgain = graph.findMatching(Set.of(ca));
            assertSame(matchCa, matchCaAgain);
        }

        @Test
        void findMatchingCacheInvalidatedOnNewArchetype() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var cb = idOf(reg, CompB.class);

            graph.getOrCreate(ArchetypeId.of(Set.of(ca)));
            var before = graph.findMatching(Set.of(ca));
            assertEquals(1, before.size());

            graph.getOrCreate(ArchetypeId.of(Set.of(ca, cb)));
            var after = graph.findMatching(Set.of(ca));
            assertEquals(2, after.size());
        }

        @Test
        void archetypeCountAndGet() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            assertEquals(0, graph.archetypeCount());

            var id = ArchetypeId.of(Set.of());
            graph.getOrCreate(id);
            assertEquals(1, graph.archetypeCount());
            assertNotNull(graph.get(id));
            assertNull(graph.get(ArchetypeId.of(Set.of(new ComponentId(99)))));
        }

        @Test
        void allArchetypes() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            graph.getOrCreate(ArchetypeId.of(Set.of()));
            graph.getOrCreate(ArchetypeId.of(Set.of(ca)));
            assertEquals(2, graph.allArchetypes().size());
        }

        @Test
        void clearRemovesEverything() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            graph.getOrCreate(ArchetypeId.of(Set.of()));
            graph.clear();
            assertEquals(0, graph.archetypeCount());
        }

        @Test
        void addEdgeCreatesTargetArchetype() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var cb = idOf(reg, CompB.class);
            var source = ArchetypeId.of(Set.of(ca));
            graph.getOrCreate(source);
            var target = graph.addEdge(source, cb);
            assertNotNull(target);
            assertTrue(target.contains(cb));
            assertTrue(target.contains(ca));
        }

        @Test
        void removeEdgeCreatesTargetArchetype() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            var cb = idOf(reg, CompB.class);
            var source = ArchetypeId.of(Set.of(ca, cb));
            graph.getOrCreate(source);
            var target = graph.removeEdge(source, cb);
            assertNotNull(target);
            assertFalse(target.contains(cb));
            assertTrue(target.contains(ca));
        }

        @Test
        void enableDirtyTracking() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            graph.enableDirtyTracking(ca);
            graph.enableDirtyTracking(ca); // duplicate is a no-op
            assertTrue(graph.dirtyTrackedComponents().contains(ca));
        }

        @Test
        void setFullyUntrackedComponents() {
            var reg = createRegistry();
            var graph = new ArchetypeGraph(reg, 64, ComponentStorage.defaultFactory());
            var ca = idOf(reg, CompA.class);
            graph.setFullyUntrackedComponents(Set.of(ca));
            // No exception even without archetypes
        }
    }

    // ================================================================
    // Archetype coverage (ensureCapacity, entity, multi-chunk)
    // ================================================================
    @Nested
    class ArchetypeCoverage {

        @Test
        void ensureCapacityPreAllocatesChunks() {
            var world = World.builder().build();
            // Spawn enough entities to trigger multi-chunk
            var entities = new ArrayList<Entity>();
            for (int i = 0; i < 200; i++) {
                entities.add(world.spawn(new Health(i)));
            }
            // Verify they are all alive and accessible
            for (int i = 0; i < 200; i++) {
                assertEquals(new Health(i), world.getComponent(entities.get(i), Health.class));
            }
        }

        @Test
        void removeFromMiddleSwapsCorrectly() {
            var world = World.builder().build();
            var e1 = world.spawn(new Health(1));
            var e2 = world.spawn(new Health(2));
            var e3 = world.spawn(new Health(3));

            world.despawn(e2);

            // e1 and e3 should still have correct components
            assertEquals(new Health(1), world.getComponent(e1, Health.class));
            assertEquals(new Health(3), world.getComponent(e3, Health.class));
        }
    }

    // ================================================================
    // FieldFilter / query coverage
    // ================================================================
    @Nested
    class FieldFilterCoverage {

        @Test
        void greaterThan() {
            var filter = FieldFilter.of(Health.class, "hp").greaterThan(50);
            var components = Map.<Class<?>, Record>of(Health.class, new Health(60));
            assertTrue(filter.test(components));
            assertFalse(filter.test(Map.of(Health.class, new Health(30))));
        }

        @Test
        void greaterThanOrEqual() {
            var filter = FieldFilter.of(Health.class, "hp").greaterThanOrEqual(50);
            assertTrue(filter.test(Map.of(Health.class, new Health(50))));
            assertTrue(filter.test(Map.of(Health.class, new Health(51))));
            assertFalse(filter.test(Map.of(Health.class, new Health(49))));
        }

        @Test
        void lessThan() {
            var filter = FieldFilter.of(Health.class, "hp").lessThan(50);
            assertTrue(filter.test(Map.of(Health.class, new Health(30))));
            assertFalse(filter.test(Map.of(Health.class, new Health(60))));
        }

        @Test
        void lessThanOrEqual() {
            var filter = FieldFilter.of(Health.class, "hp").lessThanOrEqual(50);
            assertTrue(filter.test(Map.of(Health.class, new Health(50))));
            assertTrue(filter.test(Map.of(Health.class, new Health(49))));
            assertFalse(filter.test(Map.of(Health.class, new Health(51))));
        }

        @Test
        void equalTo() {
            var filter = FieldFilter.of(Health.class, "hp").equalTo(42);
            assertTrue(filter.test(Map.of(Health.class, new Health(42))));
            assertFalse(filter.test(Map.of(Health.class, new Health(43))));
        }

        @Test
        void notEqualTo() {
            var filter = FieldFilter.of(Health.class, "hp").notEqualTo(42);
            assertFalse(filter.test(Map.of(Health.class, new Health(42))));
            assertTrue(filter.test(Map.of(Health.class, new Health(43))));
        }

        @Test
        void singleFieldFilterReturnsFalseWhenComponentMissing() {
            var filter = FieldFilter.of(Health.class, "hp").greaterThan(0);
            assertFalse(filter.test(Map.of()));
        }

        @Test
        void andFilter() {
            var f1 = FieldFilter.of(Health.class, "hp").greaterThan(10);
            var f2 = FieldFilter.of(Health.class, "hp").lessThan(100);
            var combined = FieldFilter.and(f1, f2);

            assertTrue(combined.test(Map.of(Health.class, new Health(50))));
            assertFalse(combined.test(Map.of(Health.class, new Health(5))));
            assertFalse(combined.test(Map.of(Health.class, new Health(200))));
        }

        @Test
        void orFilter() {
            var f1 = FieldFilter.of(Health.class, "hp").lessThan(10);
            var f2 = FieldFilter.of(Health.class, "hp").greaterThan(90);
            var combined = FieldFilter.or(f1, f2);

            assertTrue(combined.test(Map.of(Health.class, new Health(5))));
            assertTrue(combined.test(Map.of(Health.class, new Health(95))));
            assertFalse(combined.test(Map.of(Health.class, new Health(50))));
        }

        @Test
        void orFilterAllFalseReturnsFalse() {
            var f1 = FieldFilter.of(Health.class, "hp").lessThan(0);
            var f2 = FieldFilter.of(Health.class, "hp").greaterThan(1000);
            var combined = FieldFilter.or(f1, f2);
            assertFalse(combined.test(Map.of(Health.class, new Health(50))));
        }

        @Test
        void invalidFieldThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.of(Health.class, "nonexistent"));
        }

        @Test
        void parseGreaterThan() {
            var filter = FieldFilter.parse("hp > 50", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(60))));
            assertFalse(filter.test(Map.of(Health.class, new Health(30))));
        }

        @Test
        void parseGreaterThanOrEqual() {
            var filter = FieldFilter.parse("hp >= 50", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(50))));
        }

        @Test
        void parseLessThan() {
            var filter = FieldFilter.parse("hp < 50", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(30))));
        }

        @Test
        void parseLessThanOrEqual() {
            var filter = FieldFilter.parse("hp <= 50", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(50))));
            assertTrue(filter.test(Map.of(Health.class, new Health(49))));
            assertFalse(filter.test(Map.of(Health.class, new Health(51))));
        }

        @Test
        void parseEqualTo() {
            var filter = FieldFilter.parse("hp == 42", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(42))));
            assertFalse(filter.test(Map.of(Health.class, new Health(43))));
        }

        @Test
        void parseNotEqualTo() {
            var filter = FieldFilter.parse("hp != 42", Health.class);
            assertFalse(filter.test(Map.of(Health.class, new Health(42))));
            assertTrue(filter.test(Map.of(Health.class, new Health(43))));
        }

        @Test
        void parseNoOperatorThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> FieldFilter.parse("hp 50", Health.class));
        }

        @Test
        void parseCompactSyntax() {
            // No spaces between field, operator, and value
            var filter = FieldFilter.parse("hp>50", Health.class);
            assertTrue(filter.test(Map.of(Health.class, new Health(60))));
        }
    }

    // ================================================================
    // ComponentAccess coverage
    // ================================================================
    @Nested
    class ComponentAccessCoverage {

        @Test
        void threeArgConstructorDefaultsFromTargetFalse() {
            var access = new ComponentAccess(new ComponentId(1), Health.class, AccessType.READ);
            assertFalse(access.fromTarget());
            assertEquals(AccessType.READ, access.accessType());
        }

        @Test
        void fourArgConstructorWithFromTarget() {
            var access = new ComponentAccess(new ComponentId(1), Health.class, AccessType.WRITE, true);
            assertTrue(access.fromTarget());
            assertEquals(AccessType.WRITE, access.accessType());
        }
    }

    // ================================================================
    // World-level relation integration
    // ================================================================
    @Nested
    class WorldRelationIntegration {

        @Test
        void setAndGetRelationThroughWorld() {
            var world = World.builder().build();
            var a = world.spawn(new Health(100));
            var b = world.spawn(new Health(50));

            world.setRelation(a, b, new Hunts(5));
            var result = world.getRelation(a, b, Hunts.class);
            assertTrue(result.isPresent());
            assertEquals(new Hunts(5), result.get());
        }

        @Test
        void removeRelationThroughWorld() {
            var world = World.builder().build();
            var a = world.spawn(new Health(100));
            var b = world.spawn(new Health(50));

            world.setRelation(a, b, new Hunts(5));
            world.removeRelation(a, b, Hunts.class);
            assertTrue(world.getRelation(a, b, Hunts.class).isEmpty());
        }

        @Test
        void getRelationForUnregisteredTypeReturnsEmpty() {
            var world = World.builder().build();
            var a = world.spawn(new Health(100));
            var b = world.spawn(new Health(50));
            assertTrue(world.getRelation(a, b, Follows.class).isEmpty());
        }

        @Test
        void removeRelationForUnregisteredTypeIsNoOp() {
            var world = World.builder().build();
            var a = world.spawn(new Health(100));
            var b = world.spawn(new Health(50));
            // Should not throw
            world.removeRelation(a, b, Follows.class);
        }

        @Test
        void multipleRelationsFromSameSource() {
            var world = World.builder().build();
            var a = world.spawn(new Health(100));
            var b = world.spawn(new Health(50));
            var c = world.spawn(new Health(30));

            world.setRelation(a, b, new Hunts(1));
            world.setRelation(a, c, new Hunts(2));

            assertEquals(new Hunts(1), world.getRelation(a, b, Hunts.class).orElseThrow());
            assertEquals(new Hunts(2), world.getRelation(a, c, Hunts.class).orElseThrow());
        }
    }
}
