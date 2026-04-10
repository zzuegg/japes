package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadedExecutorShutdownTest {

    @Test
    void shutdownAwaitsInFlightTasks() throws Exception {
        var executor = new MultiThreadedExecutor(2);
        var finished = new AtomicBoolean(false);
        var started = new CountDownLatch(1);

        // Submit a task directly to the underlying pool (simulates an in-flight
        // system still running when world.close() is called).
        executor.pool().execute(() -> {
            started.countDown();
            try {
                Thread.sleep(200);
                finished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Make sure the task is actually running before we shut down.
        assertTrue(started.await(1, TimeUnit.SECONDS));

        executor.shutdown();

        assertTrue(finished.get(),
            "shutdown must block until in-flight tasks finish; task still running or dropped");
    }
}
