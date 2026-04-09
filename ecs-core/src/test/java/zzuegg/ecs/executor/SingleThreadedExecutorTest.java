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
            List.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), false, false, null, null, null
        );
    }

    @Test
    void executesInTopologicalOrder() {
        var order = new ArrayList<String>();

        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of("a"), Set.of());
        var c = stub("c", Set.of("b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));

        graph.reset();
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            assertFalse(ready.isEmpty());
            for (var node : ready) {
                order.add(node.descriptor().name());
                graph.complete(node);
            }
        }

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
}
