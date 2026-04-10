package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SingleThreadedExecutorTest {

    private SystemDescriptor stub(String name, Set<String> after, Set<String> before) {
        return new SystemDescriptor(
            name, "Update", after, before, false,
            List.of(), java.util.Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), Set.of(), false, false, null, null, null
        );
    }

    @Test
    void executesInTopologicalOrder() {
        var order = new ArrayList<String>();

        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of("a"), Set.of());
        var c = stub("c", Set.of("b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));
        var executor = new SingleThreadedExecutor();
        executor.execute(graph, node -> order.add(node.descriptor().name()));

        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void parallelSystemsBothReady() {
        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of(), Set.of());
        var c = stub("c", Set.of("a", "b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));

        var ready = graph.readySystems();
        assertEquals(2, ready.size());
    }

    @Test
    void executorWithMultiThreaded() {
        var order = java.util.Collections.synchronizedList(new ArrayList<String>());

        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of("a"), Set.of());
        var c = stub("c", Set.of("a"), Set.of());
        var d = stub("d", Set.of("b", "c"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c, d));
        var executor = new MultiThreadedExecutor(4);
        executor.execute(graph, node -> order.add(node.descriptor().name()));
        executor.shutdown();

        assertEquals(4, order.size());
        // a must be first, d must be last
        assertEquals("a", order.getFirst());
        assertEquals("d", order.getLast());
    }
}
