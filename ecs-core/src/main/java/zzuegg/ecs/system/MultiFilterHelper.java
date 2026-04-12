package zzuegg.ecs.system;

import zzuegg.ecs.change.ChangeTracker;

/**
 * Helper for tier-1 generated chunk processors walking the union of
 * multiple change-tracker dirty lists (multi-target {@code @Filter}).
 *
 * <p>The generated {@code process(chunk, tick)} method calls
 * {@link #unionDirtySlots} once per chunk with reusable bitmap +
 * result buffers (allocated once as fields on the generated class).
 * The helper fills {@code resultBuf} and returns the count of matching
 * slots. Zero per-chunk heap allocation in the steady state.
 */
public final class MultiFilterHelper {

    private MultiFilterHelper() {}

    /**
     * Compute the deduplicated union of dirty slots across all
     * {@code trackers}, keeping only slots where at least one tracker
     * reports dirty since {@code lastSeen}. Results are written into
     * {@code resultBuf}; returns the number of entries written.
     *
     * <p>Caller must ensure:
     * <ul>
     *   <li>{@code seenBitmap.length >= (count + 63) >>> 6}</li>
     *   <li>{@code resultBuf.length >= count} (theoretical max)</li>
     *   <li>{@code seenBitmap} is zeroed before the call (caller
     *       clears it once per chunk; we don't clear here to avoid
     *       redundant work when the caller already did it)</li>
     * </ul>
     *
     * @param trackers   all trackers for one filter group's targets
     * @param count      chunk entity count (slots >= count are stale)
     * @param lastSeen   the system's lastSeenTick watermark
     * @param added      true for Added, false for Changed
     * @param seenBitmap reusable dedup bitmap (long[], caller-zeroed)
     * @param resultBuf  reusable output buffer (int[])
     * @return number of matching slots written to resultBuf
     */
    public static int unionDirtySlots(ChangeTracker[] trackers, int count,
                                       long lastSeen, boolean added,
                                       long[] seenBitmap, int[] resultBuf) {
        int write = 0;
        for (var tracker : trackers) {
            int[] dirty = tracker.dirtySlots();
            int dirtyN = tracker.dirtyCount();
            for (int d = 0; d < dirtyN; d++) {
                int slot = dirty[d];
                if (slot >= count) continue;

                // Bitmap dedup: check + set in one branch.
                int word = slot >>> 6;
                long bit = 1L << (slot & 63);
                if ((seenBitmap[word] & bit) != 0) continue;

                // OR check: does ANY tracker report dirty for this slot?
                // Check the current tracker first (it put the slot in its
                // dirty list, so it's the most likely to report true).
                boolean hit;
                if (added) {
                    hit = tracker.isAddedSince(slot, lastSeen);
                    if (!hit) {
                        for (var t : trackers) {
                            if (t == tracker) continue;
                            if (t.isAddedSince(slot, lastSeen)) { hit = true; break; }
                        }
                    }
                } else {
                    hit = tracker.isChangedSince(slot, lastSeen);
                    if (!hit) {
                        for (var t : trackers) {
                            if (t == tracker) continue;
                            if (t.isChangedSince(slot, lastSeen)) { hit = true; break; }
                        }
                    }
                }
                if (!hit) continue;

                seenBitmap[word] |= bit;
                resultBuf[write++] = slot;
            }
        }
        return write;
    }
}
