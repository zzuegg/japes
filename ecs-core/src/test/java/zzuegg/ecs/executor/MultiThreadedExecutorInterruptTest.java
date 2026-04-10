package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadedExecutorInterruptTest {

    private SystemDescriptor stub(String name) {
        return new SystemDescriptor(
            name, "Update", Set.of(), Set.of(), false,
            List.of(), java.util.Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), false, false, null, null, null
        );
    }

    @Test
    void interruptDuringExecuteSurfacesAsException() throws Exception {
        var a = stub("a");
        var b = stub("b");
        var graph = DagBuilder.build(List.of(a, b));
        var executor = new MultiThreadedExecutor(2);

        var runnerStarted = new CountDownLatch(1);
        var threw = new AtomicBoolean();
        var saw = new Thread(() -> {
            try {
                executor.execute(graph, node -> {
                    runnerStarted.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RuntimeException e) {
                threw.set(true);
            } finally {
                executor.shutdown();
            }
        });
        saw.start();

        assertTrue(runnerStarted.await(2, TimeUnit.SECONDS));
        // Interrupt the caller while awaitAdvance is blocking in execute().
        saw.interrupt();
        saw.join(5_000);

        assertFalse(saw.isAlive(), "execute() did not surface interrupt in time");
        assertTrue(threw.get(),
            "interrupt during execute() must surface as a RuntimeException, not be silently lost");
    }
}
