package zzuegg.ecs.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreConcurrencyTest {

    record E(int id) {}

    @Test
    void concurrentSendAndSwapLosesNoEvents() throws Exception {
        var store = new EventStore<E>();
        int total = 20_000;
        var sent = new AtomicInteger();
        var seen = new AtomicInteger();
        var started = new CountDownLatch(1);

        var producer = new Thread(() -> {
            try {
                started.await();
                for (int i = 0; i < total; i++) {
                    store.send(new E(i));
                    sent.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start();
        started.countDown();

        // Drive swap() concurrently while the producer is sending; count
        // every event observed in the read buffer. Use a hard deadline so a
        // broken send/swap race (where events are lost and sent>seen never
        // equalizes) fails with an assertion instead of spinning forever.
        long deadlineNs = java.lang.System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.isAlive() || sent.get() > seen.get()) {
            store.swap();
            for (var e : store.read()) seen.incrementAndGet();
            if (java.lang.System.nanoTime() > deadlineNs) break;
            Thread.onSpinWait();
        }
        // Final swap to drain whatever the producer sent after the last iteration.
        store.swap();
        for (var e : store.read()) seen.incrementAndGet();

        producer.join(5_000);
        assertFalse(producer.isAlive(), "producer did not finish");

        assertEquals(total, sent.get());
        assertEquals(sent.get(), seen.get(),
            "events were lost across send/swap: sent=" + sent.get() + " seen=" + seen.get());
    }

    @Test
    void swapIsIdempotentWithNoProducer() {
        var store = new EventStore<E>();
        store.send(new E(1));
        store.send(new E(2));

        store.swap();
        assertEquals(2, store.read().size());

        store.swap();
        // After the second swap with no new writes, the read buffer is empty.
        assertEquals(0, store.read().size());
    }
}
