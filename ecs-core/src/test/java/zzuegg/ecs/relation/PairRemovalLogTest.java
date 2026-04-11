package zzuegg.ecs.relation;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-relation-type removal log. Parallels
 * {@code zzuegg.ecs.change.RemovalLog} but keyed only by pair identity
 * — the containing {@link RelationStore} is already per-type, so
 * there's no need for a component id discriminator.
 *
 * <p>Drives {@code RemovedRelations<T>} system parameters: systems
 * register as consumers, walk their unseen entries via
 * {@link PairRemovalLog#snapshot}, then advance their watermark. The
 * store drops entries once every consumer has advanced past them.
 */
class PairRemovalLogTest {

    record Distance(float meters) {}

    @Test
    void emptyLogWithNoConsumersIgnoresAppend() {
        var log = new PairRemovalLog();
        // No registerConsumer call — nothing should be retained.
        log.append(Entity.of(1, 0), Entity.of(2, 0), new Distance(5f), 10L);
        assertTrue(log.snapshot(0L).isEmpty());
    }

    @Test
    void appendAfterRegisterRetainsEntries() {
        var log = new PairRemovalLog();
        log.registerConsumer();

        var src = Entity.of(1, 0);
        var tgt = Entity.of(2, 0);
        log.append(src, tgt, new Distance(5f), 10L);

        var snap = log.snapshot(0L);
        assertEquals(1, snap.size());
        assertEquals(src, snap.getFirst().source());
        assertEquals(tgt, snap.getFirst().target());
        assertEquals(new Distance(5f), snap.getFirst().value());
        assertEquals(10L, snap.getFirst().tick());
    }

    @Test
    void snapshotOnlyIncludesEntriesAfterWatermark() {
        var log = new PairRemovalLog();
        log.registerConsumer();

        log.append(Entity.of(1, 0), Entity.of(2, 0), new Distance(1f), 5L);
        log.append(Entity.of(1, 0), Entity.of(3, 0), new Distance(2f), 10L);
        log.append(Entity.of(1, 0), Entity.of(4, 0), new Distance(3f), 15L);

        var snap = log.snapshot(10L);
        assertEquals(1, snap.size(),
            "snapshot is strictly > since, so tick=10 is excluded");
        assertEquals(Entity.of(4, 0), snap.getFirst().target());
    }

    @Test
    void collectGarbageDropsEntriesAtOrBelowNewMinimum() {
        var log = new PairRemovalLog();
        log.registerConsumer();

        log.append(Entity.of(1, 0), Entity.of(2, 0), new Distance(1f), 5L);
        log.append(Entity.of(1, 0), Entity.of(3, 0), new Distance(2f), 10L);
        log.append(Entity.of(1, 0), Entity.of(4, 0), new Distance(3f), 15L);

        log.collectGarbage(10L);

        var snap = log.snapshot(0L);
        assertEquals(1, snap.size(),
            "entries with tick <= watermark are dropped");
        assertEquals(15L, snap.getFirst().tick());
    }

    @Test
    void collectGarbageIgnoresLowerWatermark() {
        var log = new PairRemovalLog();
        log.registerConsumer();

        log.append(Entity.of(1, 0), Entity.of(2, 0), new Distance(1f), 5L);
        log.collectGarbage(10L);
        log.collectGarbage(3L);  // regression — do not undo

        assertEquals(10L, log.minWatermark());
    }

    @Test
    void clearConsumersResetsState() {
        var log = new PairRemovalLog();
        log.registerConsumer();
        log.append(Entity.of(1, 0), Entity.of(2, 0), new Distance(1f), 5L);

        log.clearConsumers();

        log.append(Entity.of(1, 0), Entity.of(3, 0), new Distance(2f), 10L);
        assertTrue(log.snapshot(0L).isEmpty(),
            "after clearConsumers, subsequent appends with no consumer drop");
    }
}
