package zzuegg.ecs.relation;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Non-fragmenting side-table storage for one relation type. Holds
 * forward + reverse indices + per-pair change tracker + removal log.
 *
 * <p>This is the lowest layer of the relations stack — no knowledge
 * of the archetype graph, no knowledge of scheduler, no public API
 * surface beyond what unit tests at this level need. Higher layers
 * ({@code ComponentRegistry}, {@code World}) wrap this.
 */
public final class RelationStore<T extends Record> {

    private final Class<T> type;
    /**
     * Archetype-level "this entity has >= 1 <i>outgoing</i> pair of
     * this relation type" marker id. Nominally optional — the store
     * itself is fully functional without it — but when set (at
     * {@code ComponentRegistry.registerRelation} time) it lets
     * {@code World} add/remove the marker from a source entity's
     * archetype as it gains/loses its first/last outgoing pair.
     */
    private final ComponentId markerId;
    /**
     * Archetype-level "this entity has >= 1 <i>incoming</i> pair of
     * this relation type" marker id. Maintained on target entities
     * so {@code @Pair(role = TARGET)} systems can let the archetype
     * filter narrow to "things currently being targeted" without the
     * user writing a reverse-index-backed filter by hand.
     */
    private final ComponentId targetMarkerId;
    /** Cleanup policy applied when a target entity is despawned. */
    private final CleanupPolicy onTargetDespawn;
    /**
     * Forward index: source entity id → flat slice of
     * (target, payload) entries. Keyed by the packed {@code long}
     * Entity id — a primitive open-addressing map instead of
     * {@code HashMap<Entity, ...>} so per-call lookups skip
     * {@code Entity.hashCode()}, {@code Entity.equals()} and the
     * Node-chain walker. See {@link TargetSlice} for the inner
     * structure rationale.
     */
    private final Long2ObjectOpenMap<TargetSlice<T>> forward = new Long2ObjectOpenMap<>();
    /**
     * Reverse index: target entity id → flat slice of source entity
     * ids. Same rationale as the forward map. See
     * {@link SourceSlice}.
     */
    private final Long2ObjectOpenMap<SourceSlice> reverse = new Long2ObjectOpenMap<>();
    /**
     * Per-pair change ticks + dirty list. Driven by {@link #set(Entity, Entity, Record, long)}
     * and {@link #remove(Entity, Entity)}. The no-tick {@link #set(Entity, Entity, Record)}
     * overload bypasses the tracker (tick 0 = "untracked" sentinel) so legacy
     * non-tracking tests stay cheap.
     */
    private final PairChangeTracker tracker = new PairChangeTracker();
    /**
     * Log of dropped pairs for {@code RemovedRelations<T>} observers.
     * Fed by {@link #remove(Entity, Entity, long)}; the no-tick
     * {@link #remove(Entity, Entity)} form uses {@code tick = 0} which
     * the log's short-circuit path treats as untracked.
     */
    private final PairRemovalLog removalLog = new PairRemovalLog();
    /** Total number of (source, target) pairs currently stored. */
    private int size = 0;

    public RelationStore(Class<T> type) {
        this(type, null, null, CleanupPolicy.RELEASE_TARGET);
    }

    public RelationStore(Class<T> type, ComponentId markerId) {
        this(type, markerId, null, CleanupPolicy.RELEASE_TARGET);
    }

    public RelationStore(Class<T> type, ComponentId markerId, CleanupPolicy onTargetDespawn) {
        this(type, markerId, null, onTargetDespawn);
    }

    public RelationStore(Class<T> type, ComponentId sourceMarkerId,
                          ComponentId targetMarkerId, CleanupPolicy onTargetDespawn) {
        this.type = type;
        this.markerId = sourceMarkerId;
        this.targetMarkerId = targetMarkerId;
        this.onTargetDespawn = onTargetDespawn;
    }

    public Class<T> type() {
        return type;
    }

    /**
     * Source-side marker component id — the "entity has >= 1
     * outgoing pair of this relation type" archetype flag. Returns
     * {@code null} if the store was constructed outside the registry
     * (unit-test path).
     */
    public ComponentId markerId() {
        return markerId;
    }

    /**
     * Alias for {@link #markerId()} with the role-qualified name,
     * for call sites that also deal with the target-side marker and
     * want the name parity.
     */
    public ComponentId sourceMarkerId() {
        return markerId;
    }

    /**
     * Target-side marker component id — the "entity has >= 1
     * incoming pair of this relation type" archetype flag. Set on
     * target entities so {@code @Pair(role = TARGET)} systems can
     * narrow the archetype filter to "things currently being
     * targeted" without the user writing a manual filter. Returns
     * {@code null} if the store was constructed outside the registry.
     */
    public ComponentId targetMarkerId() {
        return targetMarkerId;
    }

    /**
     * Cleanup policy applied when a target entity is despawned. Reads
     * the {@code @Relation} annotation at registration time and
     * defaults to {@link CleanupPolicy#RELEASE_TARGET}.
     */
    public CleanupPolicy onTargetDespawn() {
        return onTargetDespawn;
    }

    /**
     * {@code true} iff there is at least one pair with {@code source}
     * as the source. Used by {@code World} to detect
     * "first pair / last pair" transitions around archetype marker
     * maintenance.
     */
    public boolean hasSource(Entity source) {
        return forward.containsKey(source.id());
    }

    /**
     * {@code true} iff there is at least one pair pointing at
     * {@code target}. Cheap existence check on the reverse index.
     */
    public boolean hasTarget(Entity target) {
        return reverse.containsKey(target.id());
    }

    public int size() {
        return size;
    }

    /** Expose the per-pair change tracker. Primarily for tests + observers. */
    public PairChangeTracker tracker() {
        return tracker;
    }

    /** Expose the per-type removal log. Primarily for tests + observers. */
    public PairRemovalLog removalLog() {
        return removalLog;
    }

    /**
     * Tick-less insert. Delegates to the tick-aware form with
     * {@code tick = 0}, which the tracker treats as a no-op marker.
     */
    public T set(Entity source, Entity target, T value) {
        return set(source, target, value, 0L);
    }

    /**
     * Insert or overwrite the pair payload for {@code (source, target)},
     * updating change-tracker state at the given tick. Returns the
     * previous payload if one existed, otherwise {@code null}.
     *
     * <p>Tick semantics match {@code ChangeTracker}: a brand-new pair
     * bumps {@code addedTick}, an overwrite bumps {@code changedTick},
     * and in both cases the pair is added to the dirty list (deduped).
     * A zero tick is reserved as the "untracked" sentinel — it bypasses
     * tracker updates entirely, so tests can insert without polluting
     * the dirty list.
     */
    public T set(Entity source, Entity target, T value, long tick) {
        var inner = forward.computeIfAbsent(source.id(), k -> new TargetSlice<>());
        var previous = inner.put(target, value);
        if (previous == null) {
            size++;
            reverse.computeIfAbsent(target.id(), k -> new SourceSlice()).add(source);
            if (tick != 0L && !tracker.isFullyUntracked()) tracker.markAdded(new PairKey(source, target), tick);
        } else {
            if (tick != 0L && !tracker.isFullyUntracked()) tracker.markChanged(new PairKey(source, target), tick);
        }
        return previous;
    }

    /**
     * Look up the pair payload for {@code (source, target)}, or
     * {@code null} if no such pair exists.
     */
    public T get(Entity source, Entity target) {
        var inner = forward.get(source.id());
        if (inner == null) return null;
        return inner.get(target);
    }

    /**
     * Drop the {@code (source, target)} pair from the store. Returns
     * the removed payload, or {@code null} if the pair wasn't present.
     * When the source has no remaining pairs of this type, the empty
     * inner map is also removed so the archetype-marker maintenance
     * layer (coming in PR 2) can detect "source dropped its last pair
     * of type T."
     */
    public T remove(Entity source, Entity target) {
        return remove(source, target, 0L);
    }

    /**
     * Tick-aware variant of {@link #remove(Entity, Entity)}. Feeds the
     * removal log with {@code (source, target, lastValue, tick)} for
     * {@code RemovedRelations<T>} observers, in addition to clearing
     * the change-tracker entry. A zero tick is reserved as the
     * "untracked" sentinel — it bypasses the log entirely.
     */
    public T remove(Entity source, Entity target, long tick) {
        var inner = forward.get(source.id());
        if (inner == null) return null;
        var previous = inner.remove(target);
        if (previous == null) return null;
        size--;
        if (inner.isEmpty()) forward.remove(source.id());

        var sources = reverse.get(target.id());
        if (sources != null) {
            sources.remove(source);
            if (sources.isEmpty()) reverse.remove(target.id());
        }
        if (!tracker.isFullyUntracked()) tracker.remove(new PairKey(source, target));
        if (tick != 0L) removalLog.append(source, target, previous, tick);
        return previous;
    }

    /**
     * Package-private hot-path accessor returning the live
     * {@link TargetSlice} for a source entity, or {@code null} if
     * the source has no pairs. Used by {@link StorePairReader} to
     * walk pairs without wrapping each entry in a {@link Map.Entry}
     * adapter.
     */
    TargetSlice<T> targetSliceFor(Entity source) {
        return forward.get(source.id());
    }

    /**
     * Raw backing {@code long[]} of the forward map's keys. Length
     * is the map's internal table size; callers must skip slots
     * where {@code forwardValuesArray()[i] == null}. Exposed as
     * public for tier-1 generated {@code @ForEachPair} processors
     * to walk the outer map without going through
     * {@code HashMap$HashIterator} or {@code Long2ObjectOpenMap.forEach}.
     * Do not mutate — the array is live and shared with the store.
     */
    public long[] forwardKeysArray() { return forward.keysArray(); }

    /** Raw backing values array — same contract as {@link #forwardKeysArray()}. */
    public Object[] forwardValuesArray() { return forward.valuesArray(); }

    /**
     * Bulk callback over every live pair. Calls {@code consumer}
     * once per {@code (source, target, value)} triple, with no
     * intermediate {@link Pair} allocation. Used by exclusive
     * systems (e.g. distance checks, pair-wise cleanup) that want
     * to scan all live pairs without going through
     * {@link PairReader} per-entity walks.
     *
     * <p>Iteration is over the store's live maps — callers must not
     * mutate the store from inside the consumer. Defer mutations
     * into a local list and apply them after the walk finishes.
     */
    @SuppressWarnings("unchecked")
    public void forEachPair(PairConsumer<T> consumer) {
        // Direct walk over the outer map's arrays + inner slice
        // arrays. Inlining avoids the BiConsumer lambda invocation
        // per live slot (which showed up in profiling as 7–8% of
        // total CPU) and trusts the JIT to scalar-replace the
        // per-pair Entity allocations.
        var outerKeys = forward.keysArray();
        var outerVals = forward.valuesArray();
        for (int si = 0; si < outerKeys.length; si++) {
            var sliceRaw = outerVals[si];
            if (sliceRaw == null) continue;
            var slice = (TargetSlice<T>) sliceRaw;
            var source = new Entity(outerKeys[si]);
            int n = slice.size();
            var ids = slice.targetIdsArray();
            var vals = slice.valuesArray();
            for (int i = 0; i < n; i++) {
                consumer.accept(source, new Entity(ids[i]), (T) vals[i]);
            }
        }
    }

    /** Functional shape for {@link #forEachPair}. */
    @FunctionalInterface
    public interface PairConsumer<T extends Record> {
        void accept(Entity source, Entity target, T value);
    }

    /**
     * Raw-long bulk walk over every live pair. Same iteration
     * semantics as {@link #forEachPair(PairConsumer)} but hands the
     * packed {@code long} entity ids to the consumer directly —
     * callers that already operate on long ids (tier-1 pair runners,
     * cleanup scans over {@code ComponentReader.getById}) avoid the
     * per-pair {@link Entity} allocation that the {@code PairConsumer}
     * variant forces through the interface boundary.
     *
     * <p>See {@link #forEachPair(PairConsumer)} for the mutation
     * contract (don't mutate the store during iteration).
     */
    @SuppressWarnings("unchecked")
    public void forEachPairLong(LongPairConsumer<T> consumer) {
        var outerKeys = forward.keysArray();
        var outerVals = forward.valuesArray();
        for (int si = 0; si < outerKeys.length; si++) {
            var sliceRaw = outerVals[si];
            if (sliceRaw == null) continue;
            var slice = (TargetSlice<T>) sliceRaw;
            long sourceId = outerKeys[si];
            int n = slice.size();
            var ids = slice.targetIdsArray();
            var vals = slice.valuesArray();
            for (int i = 0; i < n; i++) {
                consumer.accept(sourceId, ids[i], (T) vals[i]);
            }
        }
    }

    /** Functional shape for {@link #forEachPairLong}. */
    @FunctionalInterface
    public interface LongPairConsumer<T extends Record> {
        void accept(long sourceId, long targetId, T value);
    }

    /**
     * Iterate every {@code (target, payload)} pair stored under the
     * given source. Returns a lazy adapter over the underlying
     * {@link TargetSlice} — the hot path at
     * {@link StorePairReader#fromSource(Entity)} uses
     * {@link #targetSliceFor(Entity)} directly to skip this wrapping.
     * This variant stays for tests, {@code World.removeAllRelations},
     * and the despawn-cleanup scan, which iterate once per call.
     *
     * <p>Returns an empty iterable when the source has no pairs.
     * The returned view is live and must not be held across store
     * mutations.
     */
    public Iterable<Map.Entry<Entity, T>> targetsFor(Entity source) {
        var slice = forward.get(source.id());
        if (slice == null) return Collections.emptyList();
        return new SliceEntryIterable<>(slice);
    }

    private static final class SliceEntryIterable<T extends Record>
            implements Iterable<Map.Entry<Entity, T>> {
        private final TargetSlice<T> slice;
        SliceEntryIterable(TargetSlice<T> slice) { this.slice = slice; }

        @Override public Iterator<Map.Entry<Entity, T>> iterator() {
            return new SliceEntryIterator<>(slice);
        }
    }

    private static final class SliceEntryIterator<T extends Record>
            implements Iterator<Map.Entry<Entity, T>> {
        private final TargetSlice<T> slice;
        private int idx;
        SliceEntryIterator(TargetSlice<T> slice) { this.slice = slice; }

        @Override public boolean hasNext() { return idx < slice.size(); }

        @SuppressWarnings("unchecked")
        @Override public Map.Entry<Entity, T> next() {
            if (idx >= slice.size()) throw new NoSuchElementException();
            var key = new Entity(slice.targetIdsArray()[idx]);
            var value = (T) slice.valuesArray()[idx];
            idx++;
            return new AbstractMap.SimpleImmutableEntry<>(key, value);
        }
    }

    /**
     * Iterate every source entity that has a pair pointing at the
     * given target. Returns a lazy adapter over the underlying
     * {@link SourceSlice}. The hot path uses
     * {@link #sourceSliceFor(Entity)} directly to avoid the
     * wrapper.
     *
     * <p>Returns an empty iterable when nothing points at the target.
     * The returned view is live and must not be held across store
     * mutations.
     */
    public Iterable<Entity> sourcesFor(Entity target) {
        var slice = reverse.get(target.id());
        if (slice == null) return Collections.emptyList();
        return new SliceSourceIterable(slice);
    }

    /**
     * Package-private hot-path accessor returning the live
     * {@link SourceSlice} for a target entity, or {@code null} if
     * nothing points at it.
     */
    SourceSlice sourceSliceFor(Entity target) {
        return reverse.get(target.id());
    }

    /** Drop all pairs and reset the tracker and removal log. */
    public void clear() {
        forward.clear();
        reverse.clear();
        tracker.clear();
        removalLog.clear();
        size = 0;
    }

    private static final class SliceSourceIterable implements Iterable<Entity> {
        private final SourceSlice slice;
        SliceSourceIterable(SourceSlice slice) { this.slice = slice; }
        @Override public Iterator<Entity> iterator() { return new SliceSourceIterator(slice); }
    }

    private static final class SliceSourceIterator implements Iterator<Entity> {
        private final SourceSlice slice;
        private int idx;
        SliceSourceIterator(SourceSlice slice) { this.slice = slice; }
        @Override public boolean hasNext() { return idx < slice.size(); }
        @Override public Entity next() {
            if (idx >= slice.size()) throw new NoSuchElementException();
            return new Entity(slice.sourceIdsArray()[idx++]);
        }
    }
}
