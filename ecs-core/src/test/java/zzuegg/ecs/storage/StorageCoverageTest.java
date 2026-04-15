package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests to improve coverage of the storage package.
 * Targets: UnifiedSoAStorage, DefaultComponentStorage, SoAPromotingFactory,
 * RecordFlattener edge cases, SoAComponentStorage cache path and branch coverage.
 */
class StorageCoverageTest {

    // --- Test record types covering all primitive types ---
    record FloatPair(float x, float y) {}
    record IntPair(int a, int b) {}
    record DoublePair(double x, double y) {}
    record LongPair(long a, long b) {}
    record BoolPair(boolean a, boolean b) {}
    record BytePair(byte a, byte b) {}
    record ShortPair(short a, short b) {}
    record CharPair(char a, char b) {}
    record AllPrimitives(int i, long l, float f, double d, boolean b, byte by, short s, char c) {}

    // Nested record
    record Inner(float a, float b) {}
    record Outer(Inner i1, int extra) {}

    // Non-eligible records
    record WithString(String name) {}
    record MixedNonEligible(float x, String s) {}

    // =========================================================================
    // UnifiedSoAStorage — all methods, all primitive types
    // =========================================================================

    @Test
    void unifiedSoA_floatSetAndGet() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 16);
        s.set(0, new FloatPair(1.5f, 2.5f));
        assertEquals(new FloatPair(1.5f, 2.5f), s.get(0));
    }

    @Test
    void unifiedSoA_intSetAndGet() {
        var s = new UnifiedSoAStorage<>(IntPair.class, 16);
        s.set(0, new IntPair(10, 20));
        assertEquals(new IntPair(10, 20), s.get(0));
    }

    @Test
    void unifiedSoA_doubleSetAndGet() {
        var s = new UnifiedSoAStorage<>(DoublePair.class, 16);
        s.set(0, new DoublePair(3.14, 2.71));
        assertEquals(new DoublePair(3.14, 2.71), s.get(0));
    }

    @Test
    void unifiedSoA_longSetAndGet() {
        var s = new UnifiedSoAStorage<>(LongPair.class, 16);
        s.set(0, new LongPair(100L, 200L));
        assertEquals(new LongPair(100L, 200L), s.get(0));
    }

    @Test
    void unifiedSoA_booleanSetAndGet() {
        var s = new UnifiedSoAStorage<>(BoolPair.class, 16);
        s.set(0, new BoolPair(true, false));
        assertEquals(new BoolPair(true, false), s.get(0));
    }

    @Test
    void unifiedSoA_byteSetAndGet() {
        var s = new UnifiedSoAStorage<>(BytePair.class, 16);
        s.set(0, new BytePair((byte) 1, (byte) 2));
        assertEquals(new BytePair((byte) 1, (byte) 2), s.get(0));
    }

    @Test
    void unifiedSoA_shortSetAndGet() {
        var s = new UnifiedSoAStorage<>(ShortPair.class, 16);
        s.set(0, new ShortPair((short) 100, (short) 200));
        assertEquals(new ShortPair((short) 100, (short) 200), s.get(0));
    }

    @Test
    void unifiedSoA_charSetAndGet() {
        var s = new UnifiedSoAStorage<>(CharPair.class, 16);
        s.set(0, new CharPair('A', 'B'));
        assertEquals(new CharPair('A', 'B'), s.get(0));
    }

    @Test
    void unifiedSoA_allPrimitivesSetAndGet() {
        var s = new UnifiedSoAStorage<>(AllPrimitives.class, 8);
        var val = new AllPrimitives(1, 2L, 3.0f, 4.0, true, (byte) 5, (short) 6, 'Z');
        s.set(0, val);
        assertEquals(val, s.get(0));
    }

    @Test
    void unifiedSoA_multipleSlots() {
        var s = new UnifiedSoAStorage<>(IntPair.class, 16);
        s.set(0, new IntPair(1, 2));
        s.set(1, new IntPair(3, 4));
        s.set(2, new IntPair(5, 6));
        assertEquals(new IntPair(1, 2), s.get(0));
        assertEquals(new IntPair(3, 4), s.get(1));
        assertEquals(new IntPair(5, 6), s.get(2));
    }

    @Test
    void unifiedSoA_swapRemove() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 16);
        s.set(0, new FloatPair(0, 0));
        s.set(1, new FloatPair(1, 1));
        s.set(2, new FloatPair(2, 2));
        s.swapRemove(0, 3); // swap slot 0 with last (slot 2)
        assertEquals(new FloatPair(2, 2), s.get(0));
    }

    @Test
    void unifiedSoA_swapRemoveLastElement() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 16);
        s.set(0, new FloatPair(1, 1));
        // index == last, should be a no-op
        s.swapRemove(0, 1);
        // No exception thrown is the assertion
    }

    @Test
    void unifiedSoA_swapRemoveAllTypes() {
        var s = new UnifiedSoAStorage<>(AllPrimitives.class, 8);
        var v0 = new AllPrimitives(0, 0L, 0f, 0.0, false, (byte) 0, (short) 0, '0');
        var v1 = new AllPrimitives(1, 1L, 1f, 1.0, true, (byte) 1, (short) 1, '1');
        var v2 = new AllPrimitives(2, 2L, 2f, 2.0, false, (byte) 2, (short) 2, '2');
        s.set(0, v0);
        s.set(1, v1);
        s.set(2, v2);
        s.swapRemove(0, 3);
        assertEquals(v2, s.get(0));
    }

    @Test
    void unifiedSoA_copyInto() {
        var src = new UnifiedSoAStorage<>(IntPair.class, 16);
        var dst = new UnifiedSoAStorage<>(IntPair.class, 16);
        src.set(3, new IntPair(99, 88));
        src.copyInto(3, dst, 0);
        assertEquals(new IntPair(99, 88), dst.get(0));
    }

    @Test
    void unifiedSoA_capacity() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 1024);
        assertEquals(1024, s.capacity());
    }

    @Test
    void unifiedSoA_type() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 8);
        assertEquals(FloatPair.class, s.type());
    }

    @Test
    void unifiedSoA_soaFieldArrays() {
        var s = new UnifiedSoAStorage<>(IntPair.class, 8);
        Object[] arrays = s.soaFieldArrays();
        assertNotNull(arrays);
        assertEquals(2, arrays.length);
        assertTrue(arrays[0] instanceof int[]);
        assertTrue(arrays[1] instanceof int[]);
        assertEquals(8, ((int[]) arrays[0]).length);
    }

    @Test
    void unifiedSoA_soaFieldArraysReflectsStores() {
        var s = new UnifiedSoAStorage<>(FloatPair.class, 8);
        s.set(0, new FloatPair(3.14f, 2.71f));
        Object[] arrays = s.soaFieldArrays();
        assertEquals(3.14f, ((float[]) arrays[0])[0], 0.001f);
        assertEquals(2.71f, ((float[]) arrays[1])[0], 0.001f);
    }

    // =========================================================================
    // DefaultComponentStorage — direct tests to improve coverage
    // =========================================================================

    @Test
    void defaultStorage_setGetBasic() {
        var s = new DefaultComponentStorage<>(FloatPair.class, 16);
        s.set(0, new FloatPair(1, 2));
        assertEquals(new FloatPair(1, 2), s.get(0));
    }

    @Test
    void defaultStorage_swapRemoveMiddle() {
        var s = new DefaultComponentStorage<>(IntPair.class, 16);
        s.set(0, new IntPair(0, 0));
        s.set(1, new IntPair(1, 1));
        s.set(2, new IntPair(2, 2));
        s.swapRemove(0, 3);
        assertEquals(new IntPair(2, 2), s.get(0));
        assertNull(s.get(2)); // last slot nulled
    }

    @Test
    void defaultStorage_swapRemoveLast() {
        var s = new DefaultComponentStorage<>(IntPair.class, 16);
        s.set(0, new IntPair(1, 1));
        s.swapRemove(0, 1); // index == last, skip swap, null the slot
        assertNull(s.get(0));
    }

    @Test
    void defaultStorage_copyInto() {
        var src = new DefaultComponentStorage<>(FloatPair.class, 16);
        var dst = new DefaultComponentStorage<>(FloatPair.class, 16);
        src.set(5, new FloatPair(7, 8));
        src.copyInto(5, dst, 0);
        assertEquals(new FloatPair(7, 8), dst.get(0));
    }

    @Test
    void defaultStorage_capacity() {
        var s = new DefaultComponentStorage<>(FloatPair.class, 512);
        assertEquals(512, s.capacity());
    }

    @Test
    void defaultStorage_type() {
        var s = new DefaultComponentStorage<>(IntPair.class, 8);
        assertEquals(IntPair.class, s.type());
    }

    @Test
    void defaultStorage_rawArray() {
        var s = new DefaultComponentStorage<>(FloatPair.class, 4);
        s.set(0, new FloatPair(1, 2));
        var arr = s.rawArray();
        assertNotNull(arr);
        assertEquals(4, arr.length);
        assertEquals(new FloatPair(1, 2), arr[0]);
    }

    @Test
    void defaultStorage_soaFieldArraysReturnsNull() {
        var s = new DefaultComponentStorage<>(FloatPair.class, 8);
        assertNull(s.soaFieldArrays());
    }

    @Test
    void defaultStorage_nonEligibleRecord() {
        // WithString is not SoA eligible so would use DefaultComponentStorage
        var s = new DefaultComponentStorage<>(WithString.class, 8);
        s.set(0, new WithString("hello"));
        assertEquals(new WithString("hello"), s.get(0));
        assertEquals(WithString.class, s.type());
    }

    // =========================================================================
    // SoAPromotingFactory
    // =========================================================================

    @Test
    void soaPromotingFactory_eligibleGetsSoA() {
        var delegate = new ComponentStorage.Factory() {
            @Override
            public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
                return new DefaultComponentStorage<>(type, capacity);
            }
        };
        var factory = new SoAComponentStorage.SoAPromotingFactory(delegate);
        var storage = factory.create(FloatPair.class, 16);
        // SoA eligible records should get SoA storage (non-null soaFieldArrays)
        assertNotNull(storage.soaFieldArrays());
        storage.set(0, new FloatPair(1, 2));
        assertEquals(new FloatPair(1, 2), storage.get(0));
    }

    @Test
    void soaPromotingFactory_nonEligibleDelegatesToWrapped() {
        var delegate = new ComponentStorage.Factory() {
            @Override
            public <T extends Record> ComponentStorage<T> create(Class<T> type, int capacity) {
                return new DefaultComponentStorage<>(type, capacity);
            }
        };
        var factory = new SoAComponentStorage.SoAPromotingFactory(delegate);
        var storage = factory.create(WithString.class, 8);
        // Non-eligible should use the delegate (DefaultComponentStorage)
        assertNull(storage.soaFieldArrays());
        storage.set(0, new WithString("test"));
        assertEquals(new WithString("test"), storage.get(0));
    }

    // =========================================================================
    // SoAComponentStorage — cache path, all primitive types, edge cases
    // =========================================================================

    @Test
    void soaComponentStorage_cacheHit() {
        // First call generates; second should hit cache
        var s1 = SoAComponentStorage.create(FloatPair.class, 8);
        var s2 = SoAComponentStorage.create(FloatPair.class, 16);
        // Both should work correctly
        s1.set(0, new FloatPair(1, 2));
        s2.set(0, new FloatPair(3, 4));
        assertEquals(new FloatPair(1, 2), s1.get(0));
        assertEquals(new FloatPair(3, 4), s2.get(0));
    }

    @Test
    void soaComponentStorage_intType() {
        var s = SoAComponentStorage.create(IntPair.class, 8);
        s.set(0, new IntPair(42, 99));
        assertEquals(new IntPair(42, 99), s.get(0));
    }

    @Test
    void soaComponentStorage_doubleType() {
        var s = SoAComponentStorage.create(DoublePair.class, 8);
        s.set(0, new DoublePair(1.1, 2.2));
        assertEquals(new DoublePair(1.1, 2.2), s.get(0));
    }

    @Test
    void soaComponentStorage_longType() {
        var s = SoAComponentStorage.create(LongPair.class, 8);
        s.set(0, new LongPair(Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(new LongPair(Long.MAX_VALUE, Long.MIN_VALUE), s.get(0));
    }

    @Test
    void soaComponentStorage_booleanType() {
        var s = SoAComponentStorage.create(BoolPair.class, 8);
        s.set(0, new BoolPair(true, false));
        assertEquals(new BoolPair(true, false), s.get(0));
    }

    @Test
    void soaComponentStorage_byteType() {
        var s = SoAComponentStorage.create(BytePair.class, 8);
        s.set(0, new BytePair((byte) 127, (byte) -128));
        assertEquals(new BytePair((byte) 127, (byte) -128), s.get(0));
    }

    @Test
    void soaComponentStorage_shortType() {
        var s = SoAComponentStorage.create(ShortPair.class, 8);
        s.set(0, new ShortPair((short) 1000, (short) -1000));
        assertEquals(new ShortPair((short) 1000, (short) -1000), s.get(0));
    }

    @Test
    void soaComponentStorage_charType() {
        var s = SoAComponentStorage.create(CharPair.class, 8);
        s.set(0, new CharPair('X', 'Y'));
        assertEquals(new CharPair('X', 'Y'), s.get(0));
    }

    @Test
    void soaComponentStorage_allPrimitives() {
        var s = SoAComponentStorage.create(AllPrimitives.class, 8);
        var val = new AllPrimitives(1, 2L, 3.0f, 4.0, true, (byte) 5, (short) 6, 'Z');
        s.set(0, val);
        assertEquals(val, s.get(0));
    }

    @Test
    void soaComponentStorage_swapRemoveAllTypes() {
        var s = SoAComponentStorage.create(AllPrimitives.class, 8);
        var v0 = new AllPrimitives(0, 0L, 0f, 0.0, false, (byte) 0, (short) 0, '0');
        var v2 = new AllPrimitives(2, 2L, 2f, 2.0, false, (byte) 2, (short) 2, '2');
        s.set(0, v0);
        s.set(1, new AllPrimitives(1, 1L, 1f, 1.0, true, (byte) 1, (short) 1, '1'));
        s.set(2, v2);
        s.swapRemove(0, 3);
        assertEquals(v2, s.get(0));
    }

    @Test
    void soaComponentStorage_swapRemoveLastIsNoop() {
        var s = SoAComponentStorage.create(IntPair.class, 8);
        s.set(0, new IntPair(10, 20));
        s.swapRemove(0, 1); // index >= last, should be no-op
        // Should still have the original value (no swap occurred)
        assertEquals(new IntPair(10, 20), s.get(0));
    }

    @Test
    void soaComponentStorage_capacityAndType() {
        var s = SoAComponentStorage.create(IntPair.class, 256);
        assertEquals(256, s.capacity());
        assertEquals(IntPair.class, s.type());
    }

    @Test
    void soaComponentStorage_soaFieldArrays() {
        var s = SoAComponentStorage.create(AllPrimitives.class, 4);
        var arrays = s.soaFieldArrays();
        assertNotNull(arrays);
        assertEquals(8, arrays.length); // 8 primitive fields
    }

    @Test
    void soaComponentStorage_copyInto() {
        var src = SoAComponentStorage.create(IntPair.class, 8);
        var dst = SoAComponentStorage.create(IntPair.class, 8);
        src.set(2, new IntPair(77, 88));
        src.copyInto(2, dst, 0);
        assertEquals(new IntPair(77, 88), dst.get(0));
    }

    @Test
    void soaFactory_nonEligibleGetsDefault() {
        var factory = new SoAComponentStorage.SoAFactory();
        var storage = factory.create(WithString.class, 8);
        assertNull(storage.soaFieldArrays()); // DefaultComponentStorage returns null
        storage.set(0, new WithString("test"));
        assertEquals(new WithString("test"), storage.get(0));
    }

    @Test
    void soaComponentStorage_isNotEligible() {
        assertFalse(SoAComponentStorage.isEligible(WithString.class));
        assertFalse(SoAComponentStorage.isEligible(MixedNonEligible.class));
    }

    // =========================================================================
    // RecordFlattener — edge cases for missed branches
    // =========================================================================

    @Test
    void recordFlattener_flattenNonEligibleThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> RecordFlattener.flatten(WithString.class));
    }

    @Test
    void recordFlattener_flattenMixedNonEligibleThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> RecordFlattener.flatten(MixedNonEligible.class));
    }

    // =========================================================================
    // ComponentStorage interface — static methods
    // =========================================================================

    @Test
    void componentStorage_createStaticMethod() {
        var s = ComponentStorage.create(FloatPair.class, 16);
        assertNotNull(s);
        s.set(0, new FloatPair(1, 2));
        assertEquals(new FloatPair(1, 2), s.get(0));
    }

    @Test
    void componentStorage_defaultFactory() {
        var factory = ComponentStorage.defaultFactory();
        assertNotNull(factory);
        var s = factory.create(FloatPair.class, 8);
        assertNotNull(s);
    }

    @Test
    void componentStorage_defaultSoaFieldArraysReturnsNull() {
        // The default method in the interface returns null.
        // DefaultComponentStorage inherits this behavior.
        var s = new DefaultComponentStorage<>(FloatPair.class, 4);
        assertNull(s.soaFieldArrays());
    }

    // =========================================================================
    // Chunk — bulkSetEntities, bulkMarkAdded, copyComponentTo, dirtyTracked, fullyUntracked
    // =========================================================================

    private static final ComponentId CID_0 = new ComponentId(0);
    private static final ComponentId CID_1 = new ComponentId(1);

    @Test
    void chunk_bulkSetEntities() {
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of());
        var entities = new Entity[] {
            Entity.of(10, 0), Entity.of(11, 0), Entity.of(12, 0)
        };
        chunk.bulkSetEntities(entities, 0, 3);
        assertEquals(3, chunk.count());
        assertEquals(Entity.of(10, 0), chunk.entity(0));
        assertEquals(Entity.of(11, 0), chunk.entity(1));
        assertEquals(Entity.of(12, 0), chunk.entity(2));
    }

    @Test
    void chunk_bulkSetEntitiesWithOffset() {
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of());
        var entities = new Entity[] {
            Entity.of(0, 0), Entity.of(1, 0), Entity.of(2, 0), Entity.of(3, 0)
        };
        chunk.bulkSetEntities(entities, 1, 2);
        assertEquals(2, chunk.count());
        assertEquals(Entity.of(1, 0), chunk.entity(0));
        assertEquals(Entity.of(2, 0), chunk.entity(1));
    }

    @Test
    void chunk_bulkMarkAdded() {
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of(CID_0));
        chunk.add(Entity.of(0, 0));
        chunk.add(Entity.of(1, 0));
        chunk.bulkMarkAdded(42L);
        // After bulkMarkAdded, change trackers should reflect the added tick
        var tracker = chunk.changeTracker(CID_0);
        assertTrue(tracker.isAddedSince(0, 41L));
        assertTrue(tracker.isAddedSince(1, 41L));
    }

    @Test
    void chunk_copyComponentTo() {
        var src = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of());
        var dst = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of());
        src.add(Entity.of(0, 0));
        src.set(CID_0, 0, new FloatPair(5, 10));
        dst.add(Entity.of(1, 0));
        src.copyComponentTo(CID_0, 0, dst, 0);
        assertEquals(new FloatPair(5, 10), dst.get(CID_0, 0));
    }

    @Test
    void chunk_markAdded() {
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of(CID_0));
        chunk.add(Entity.of(0, 0));
        chunk.markAdded(0, 100L);
        var tracker = chunk.changeTracker(CID_0);
        assertTrue(tracker.isAddedSince(0, 99L));
        assertFalse(tracker.isAddedSince(0, 100L));
    }

    @Test
    void chunk_entityArray() {
        var chunk = new Chunk(4, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of());
        chunk.add(Entity.of(5, 0));
        Entity[] arr = chunk.entityArray();
        assertNotNull(arr);
        assertEquals(Entity.of(5, 0), arr[0]);
    }

    @Test
    void chunk_dirtyTrackedComponents() {
        // Create chunk with dirty-tracked component
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class, CID_1, IntPair.class),
            ComponentStorage.defaultFactory(), Set.of(CID_0));
        var tracker0 = chunk.changeTracker(CID_0);
        var tracker1 = chunk.changeTracker(CID_1);
        assertTrue(tracker0.isDirtyTracked());
        assertFalse(tracker1.isDirtyTracked());
    }

    @Test
    void chunk_fullyUntrackedComponents() {
        // Create chunk with fully-untracked component
        var chunk = new Chunk(8, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of(), Set.of(CID_0));
        var tracker = chunk.changeTracker(CID_0);
        assertTrue(tracker.isFullyUntracked());
    }

    @Test
    void chunk_twoArgConstructor() {
        // Test the 4-arg constructor (without fullyUntrackedComponents)
        var chunk = new Chunk(4, Map.of(CID_0, FloatPair.class),
            ComponentStorage.defaultFactory(), Set.of(CID_0));
        assertNotNull(chunk);
        assertEquals(4, chunk.capacity());
        assertTrue(chunk.isEmpty());
    }
}
