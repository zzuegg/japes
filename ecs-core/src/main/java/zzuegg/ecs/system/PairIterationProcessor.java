package zzuegg.ecs.system;

import zzuegg.ecs.archetype.Archetype;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.relation.RelationStore;
import zzuegg.ecs.storage.ComponentStorage;
import zzuegg.ecs.world.World;

/**
 * Dispatcher for {@code @ForEachPair} systems. Walks the driving
 * relation store's forward index once per tick, resolving source-
 * side and target-side components via cached per-archetype storage
 * pointers, and invokes the user method per pair with the same
 * allocation-free path as the existing {@code @Pair} tier-1.
 *
 * <p>Caching strategy:
 * <ul>
 *   <li><b>Per-source cache</b>: source's archetype + chunk +
 *       storage pointers + write-side {@link Mut} are held in
 *       locals for the lifetime of one source's pair group. Every
 *       pair from the same source reuses them, with a single write-
 *       back at end-of-source.</li>
 *   <li><b>Per-target-archetype cache</b>: target-side storage
 *       pointers are cached for the last archetype we served. A
 *       cache hit means the per-pair target read is a single array
 *       index; a miss re-resolves lazily.</li>
 * </ul>
 *
 * <p>Method dispatch uses {@link SystemInvoker} ({@link java.lang.invoke.MethodHandle}
 * spread-invoke), which is slower than a JIT-inlined
 * {@code invokevirtual} in a tier-1 bytecode-generated processor.
 * That's the remaining cost tier-1 would close, but profiling tells
 * us the biggest wins come from eliminating the per-call
 * {@code world.getComponent} chain, which <em>is</em> already gone
 * here.
 */
public final class PairIterationProcessor {

    private final SystemDescriptor desc;
    private final SystemInvoker invoker;
    private final World world;
    private final int paramCount;
    private final Object[] args;
    private final Kind[] kinds;
    @SuppressWarnings("rawtypes")
    private final Class[] paramClass;
    private final ComponentId[] paramComponentId;
    // Reusable Mut instances for source @Write params. One per
    // WRITE slot, lived across the whole run, reset per source.
    @SuppressWarnings("rawtypes")
    private final Mut[] sourceWriteMuts;
    // Pre-computed slot indices so the hot path skips the
    // {@code for i in paramCount if kinds[i]==X} branch entirely.
    // Flush-per-source was a 9% hot spot before this; the iteration
    // now touches only the slots that actually need work.
    private final int[] sourceReadSlots;
    private final int[] sourceWriteSlots;
    private final int[] targetReadSlots;

    private enum Kind {
        SOURCE_READ, SOURCE_WRITE, TARGET_READ,
        SOURCE_ENTITY, TARGET_ENTITY, PAYLOAD, SERVICE
    }

    public PairIterationProcessor(SystemDescriptor desc, World world, Object[] planArgs) {
        this.desc = desc;
        this.invoker = SystemInvoker.create(desc);
        this.world = world;
        this.paramCount = desc.method().getParameterCount();
        this.args = new Object[paramCount];
        this.kinds = new Kind[paramCount];
        this.paramClass = new Class[paramCount];
        this.paramComponentId = new ComponentId[paramCount];
        this.sourceWriteMuts = new Mut[paramCount];

        int compIdx = 0;
        var params = desc.method().getParameters();
        var accesses = desc.componentAccesses();
        for (int i = 0; i < paramCount; i++) {
            var p = params[i];
            var pt = p.getType();
            if (p.isAnnotationPresent(Read.class)) {
                var access = accesses.get(compIdx++);
                kinds[i] = access.fromTarget() ? Kind.TARGET_READ : Kind.SOURCE_READ;
                paramClass[i] = access.type();
                paramComponentId[i] = access.componentId();
            } else if (p.isAnnotationPresent(Write.class)) {
                var access = accesses.get(compIdx++);
                kinds[i] = Kind.SOURCE_WRITE;
                paramClass[i] = access.type();
                paramComponentId[i] = access.componentId();
                sourceWriteMuts[i] = new Mut<>(null, 0, null, 0, false);
                args[i] = sourceWriteMuts[i];
            } else if (pt == Entity.class) {
                kinds[i] = desc.targetEntityParamSlots().contains(i)
                    ? Kind.TARGET_ENTITY : Kind.SOURCE_ENTITY;
            } else if (desc.pairValueParamSlot() == i) {
                kinds[i] = Kind.PAYLOAD;
                paramClass[i] = pt;
            } else {
                kinds[i] = Kind.SERVICE;
                args[i] = planArgs[i];
            }
        }

        // Dense slot-index lists for the hot-path resolve/flush
        // loops — iterating just the slots we actually touch instead
        // of the whole param count with a per-iteration Kind check.
        var srcReads = new java.util.ArrayList<Integer>();
        var srcWrites = new java.util.ArrayList<Integer>();
        var tgtReads = new java.util.ArrayList<Integer>();
        for (int i = 0; i < paramCount; i++) {
            switch (kinds[i]) {
                case SOURCE_READ -> srcReads.add(i);
                case SOURCE_WRITE -> srcWrites.add(i);
                case TARGET_READ -> tgtReads.add(i);
                default -> { /* no-op */ }
            }
        }
        this.sourceReadSlots = srcReads.stream().mapToInt(Integer::intValue).toArray();
        this.sourceWriteSlots = srcWrites.stream().mapToInt(Integer::intValue).toArray();
        this.targetReadSlots = tgtReads.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Drive one tick of per-pair iteration. */
    @SuppressWarnings("unchecked")
    public void run() {
        var store = (RelationStore<Record>) world.componentRegistry()
            .relationStore((Class<? extends Record>) desc.pairIterationType());
        if (store == null) return;
        var tick = world.currentTick();

        // Per-target-archetype cache (lives across sources).
        Archetype cachedTgtArch = null;
        int cachedTgtChunkIdx = -1;
        ComponentStorage<?>[] cachedTgtStorages = new ComponentStorage<?>[paramCount];

        // The forEachPair callback visits one source's pairs in a
        // contiguous burst, so source-side caching amortises the
        // lookup over all that source's pairs. The cache is reset
        // whenever the source entity changes between calls.
        var state = new State();

        try {
            final Archetype[] tgtArchCache = { cachedTgtArch };
            final int[] tgtChunkCache = { cachedTgtChunkIdx };
            store.forEachPair((source, target, value) -> {
                // --- source transition: resolve new source-side bindings ---
                if (source != state.lastSource) {
                    // First, flush any pending writes from the previous source.
                    flushSource(state);
                    resolveSource(source, tick, state);
                    state.lastSource = source;
                }
                if (!state.sourceValid) return;

                // --- per-pair target-side + payload ---
                var tgtLoc = world.entityLocationFor(target);
                if (tgtLoc == null) return;
                var tgtArch = tgtLoc.archetype();
                int tgtChunkIdx = tgtLoc.chunkIndex();
                if (tgtArch != tgtArchCache[0] || tgtChunkIdx != tgtChunkCache[0]) {
                    var tgtChunk = tgtArch.chunks().get(tgtChunkIdx);
                    // Only loop the slots we actually need.
                    for (int k = 0; k < targetReadSlots.length; k++) {
                        int i = targetReadSlots[k];
                        cachedTgtStorages[i] = tgtChunk.componentStorage(paramComponentId[i]);
                    }
                    tgtArchCache[0] = tgtArch;
                    tgtChunkCache[0] = tgtChunkIdx;
                }
                int tgtSlot = tgtLoc.slotIndex();

                // --- build argument vector ---
                // Source reads were filled into args[] during
                // resolveSource. Source writes already hold the
                // reusable Mut in args[]. Services pre-filled at
                // construction. Only the per-pair-variable slots
                // need updating here.
                for (int k = 0; k < targetReadSlots.length; k++) {
                    int i = targetReadSlots[k];
                    args[i] = cachedTgtStorages[i].get(tgtSlot);
                }
                if (desc.pairValueParamSlot() >= 0) args[desc.pairValueParamSlot()] = value;
                // Entity params are written per pair — source changes
                // only on transition, target changes every pair.
                for (int i = 0; i < paramCount; i++) {
                    if (kinds[i] == Kind.SOURCE_ENTITY) args[i] = source;
                    else if (kinds[i] == Kind.TARGET_ENTITY) args[i] = target;
                }

                try {
                    invoker.invoke(args);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            // Flush the final source after the walk.
            flushSource(state);
        }
    }

    @SuppressWarnings("unchecked")
    private void resolveSource(Entity source, long tick, State state) {
        var loc = world.entityLocationFor(source);
        if (loc == null) {
            state.sourceValid = false;
            return;
        }
        state.sourceValid = true;
        state.srcArch = loc.archetype();
        state.srcChunkIdx = loc.chunkIndex();
        state.srcSlot = loc.slotIndex();
        var chunk = state.srcArch.chunks().get(state.srcChunkIdx);
        // Only iterate the slots we actually need to resolve.
        for (int k = 0; k < sourceReadSlots.length; k++) {
            int i = sourceReadSlots[k];
            var storage = chunk.componentStorage(paramComponentId[i]);
            args[i] = storage.get(state.srcSlot);
        }
        for (int k = 0; k < sourceWriteSlots.length; k++) {
            int i = sourceWriteSlots[k];
            var storage = chunk.componentStorage(paramComponentId[i]);
            state.srcWriteStorages[i] = storage;
            var tracker = chunk.changeTracker(paramComponentId[i]);
            var mut = sourceWriteMuts[i];
            mut.setContext(tracker, tick);
            mut.resetValue((Record) storage.get(state.srcSlot), state.srcSlot);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void flushSource(State state) {
        if (!state.sourceValid) return;
        for (int k = 0; k < sourceWriteSlots.length; k++) {
            int i = sourceWriteSlots[k];
            var mut = sourceWriteMuts[i];
            var storage = state.srcWriteStorages[i];
            if (storage != null) {
                ((ComponentStorage) storage).set(state.srcSlot, mut.flush());
            }
        }
    }

    /** Source-side scratch state reset on each source transition. */
    private final class State {
        Entity lastSource = null;
        boolean sourceValid = false;
        Archetype srcArch;
        int srcChunkIdx;
        int srcSlot;
        final ComponentStorage<?>[] srcWriteStorages = new ComponentStorage<?>[paramCount];
    }
}
