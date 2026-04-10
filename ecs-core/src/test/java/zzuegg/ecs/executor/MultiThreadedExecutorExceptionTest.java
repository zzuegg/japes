package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadedExecutorExceptionTest {

    private SystemDescriptor stub(String name, Set<String> after) {
        return new SystemDescriptor(
            name, "Update", after, Set.of(), false,
            List.of(), java.util.Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), false, false, null, null, null
        );
    }

    @Test
    void runnerExceptionPropagatesFromMultiThreadedExecute() {
        var a = stub("a", Set.of());
        var b = stub("b", Set.of());
        var graph = DagBuilder.build(List.of(a, b));

        var executor = new MultiThreadedExecutor(2);
        try {
            var ex = assertThrows(RuntimeException.class, () ->
                executor.execute(graph, node -> {
                    if (node.descriptor().name().equals("b")) {
                        throw new IllegalStateException("boom from b");
                    }
                })
            );
            // Cause must reference the original failure so callers can diagnose.
            var root = ex.getCause() != null ? ex.getCause() : ex;
            assertTrue(root.getMessage().contains("boom from b"),
                "expected original exception message, got: " + root);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void multipleRunnerExceptionsStillSurface() {
        var a = stub("a", Set.of());
        var b = stub("b", Set.of());
        var c = stub("c", Set.of());
        var graph = DagBuilder.build(List.of(a, b, c));

        var completed = new AtomicInteger();
        var executor = new MultiThreadedExecutor(3);
        try {
            assertThrows(RuntimeException.class, () ->
                executor.execute(graph, node -> {
                    completed.incrementAndGet();
                    throw new RuntimeException("fail-" + node.descriptor().name());
                })
            );
            // All three ready tasks were dispatched, so all three ran before the throw surfaced.
            assertEquals(3, completed.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void subsequentExecuteStillWorksAfterFailure() {
        var a = stub("a", Set.of());
        var graph = DagBuilder.build(List.of(a));

        var executor = new MultiThreadedExecutor(2);
        try {
            // First call fails (single ready system takes the fast path — still must propagate).
            assertThrows(RuntimeException.class, () ->
                executor.execute(graph, node -> { throw new RuntimeException("first"); })
            );

            // Second call on a fresh graph should still run cleanly.
            var graph2 = DagBuilder.build(List.of(a));
            var ran = new AtomicInteger();
            executor.execute(graph2, node -> ran.incrementAndGet());
            assertEquals(1, ran.get());
        } finally {
            executor.shutdown();
        }
    }
}
