package zzuegg.ecs.relation;

import zzuegg.ecs.entity.Entity;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@link PairReader} implementation backed by a single
 * {@link RelationStore}. One instance per system per relation type,
 * constructed by {@code World.resolveServiceParam} and reused across
 * ticks.
 *
 * <p>Iteration methods return <em>lazy</em> iterables that walk the
 * store's live forward/reverse maps directly, without materialising
 * an intermediate snapshot. No {@code ArrayList}, no eager copy —
 * each iteration produces one fresh {@link Pair} record at most, and
 * for the common single-pair case the wrapper + iterator objects are
 * typically scalar-replaced by the JIT.
 *
 * <p><b>Safety:</b> the walk is over the store's live maps. Callers
 * must not mutate the store directly during iteration
 * ({@code World.setRelation}, {@code World.removeRelation}). Deferred
 * mutation via {@code Commands} is always safe because command
 * buffers flush at stage boundaries — a system body reading pairs
 * cannot race its own writes.
 */
public final class StorePairReader<T extends Record> implements PairReader<T> {

    private final RelationStore<T> store;

    public StorePairReader(RelationStore<T> store) {
        this.store = store;
    }

    @Override
    public Iterable<Pair<T>> fromSource(Entity source) {
        // Direct access to the store's TargetSlice — a flat
        // long[] + Object[] micro-map — instead of going through the
        // public targetsFor() adapter. This skips HashMap's
        // EntryIterator (which was 13% of CPU in profiling) and
        // walks the slice's two parallel arrays with a tight indexed
        // loop. Empty case returns the shared empty list for zero
        // allocation.
        var slice = store.targetSliceFor(source);
        if (slice == null || slice.isEmpty()) return Collections.emptyList();
        return new FromSourceWalk<>(source, slice);
    }

    @Override
    public Iterable<Pair<T>> withTarget(Entity target) {
        var slice = store.sourceSliceFor(target);
        if (slice == null || slice.isEmpty()) return Collections.emptyList();
        return new WithTargetWalk<>(target, store, slice);
    }

    @Override
    public boolean hasSource(Entity source) {
        return store.hasSource(source);
    }

    @Override
    public boolean hasTarget(Entity target) {
        return store.hasTarget(target);
    }

    @Override
    public Optional<T> get(Entity source, Entity target) {
        return Optional.ofNullable(store.get(source, target));
    }

    @Override
    public int size() {
        return store.size();
    }

    // ------------------- lazy walks -------------------
    //
    // Each "walk" is both an Iterable and an Iterator — iterator()
    // returns `this`. This halves the per-fromSource allocation
    // compared to the two-object pattern (Iterable + Iterator). The
    // trade-off is that the walk is single-use: a caller can't
    // iterate the returned object twice. For-each syntax only calls
    // iterator() once so this is fine in practice.

    private static final class FromSourceWalk<T extends Record>
            implements Iterable<Pair<T>>, Iterator<Pair<T>> {
        private final Entity source;
        // Cached at construction so the hot inner loop is a plain
        // indexed read on a local long[]/Object[] pair.
        private final long[] targetIds;
        private final Object[] values;
        private final int size;
        private int idx;

        FromSourceWalk(Entity source, TargetSlice<T> slice) {
            this.source = source;
            this.targetIds = slice.targetIdsArray();
            this.values = slice.valuesArray();
            this.size = slice.size();
        }

        @Override public Iterator<Pair<T>> iterator() { return this; }
        @Override public boolean hasNext() { return idx < size; }

        @SuppressWarnings("unchecked")
        @Override public Pair<T> next() {
            if (idx >= size) throw new NoSuchElementException();
            var target = new Entity(targetIds[idx]);
            var value = (T) values[idx];
            idx++;
            return new Pair<>(source, target, value);
        }
    }

    private static final class WithTargetWalk<T extends Record>
            implements Iterable<Pair<T>>, Iterator<Pair<T>> {
        private final Entity target;
        private final RelationStore<T> store;
        private final long[] sourceIds;
        private final int size;
        private int idx;

        WithTargetWalk(Entity target, RelationStore<T> store, SourceSlice slice) {
            this.target = target;
            this.store = store;
            this.sourceIds = slice.sourceIdsArray();
            this.size = slice.size();
        }

        @Override public Iterator<Pair<T>> iterator() { return this; }
        @Override public boolean hasNext() { return idx < size; }

        @Override
        public Pair<T> next() {
            if (idx >= size) throw new NoSuchElementException();
            var source = new Entity(sourceIds[idx++]);
            var value = store.get(source, target);
            return new Pair<>(source, target, value);
        }
    }
}
