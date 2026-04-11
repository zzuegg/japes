package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.scheduler.ScheduleGraph;
import zzuegg.ecs.system.SystemDescriptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the "deadlock guard" branch in SingleThreadedExecutor and
 * MultiThreadedExecutor: if no system is ready but the graph is not complete,
 * the executor must fail loudly instead of looping forever.
 */
class ExecutorDeadlockTest {

    private SystemDescriptor stub(String name) {
        return new SystemDescriptor(
            name, "Update", Set.of(), Set.of(), false,
            List.of(), java.util.Map.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), Set.of(), List.of(), Set.of(), null, -1, Set.of(), Set.of(), false, false, null, null, null
        );
    }

    /**
     * Corrupt the graph's in-degree array so every node appears blocked,
     * emulating a lost dependency edge. Reflection is the only way to reach
     * the internal state without adding a test hook to ScheduleGraph.
     */
    private static void freezeAllInDegrees(ScheduleGraph graph) throws Exception {
        Field origF = ScheduleGraph.class.getDeclaredField("originalInDegree");
        origF.setAccessible(true);
        int[] orig = (int[]) origF.get(graph);
        for (int i = 0; i < orig.length; i++) orig[i] = 1; // every node blocked

        Field curF = ScheduleGraph.class.getDeclaredField("inDegree");
        curF.setAccessible(true);
        int[] cur = (int[]) curF.get(graph);
        for (int i = 0; i < cur.length; i++) cur[i] = 1;
    }

    @Test
    void singleThreadedDeadlockThrows() throws Exception {
        var a = stub("a");
        var b = stub("b");
        var graph = DagBuilder.build(List.of(a, b));
        freezeAllInDegrees(graph);

        var executor = new SingleThreadedExecutor();
        var ex = assertThrows(IllegalStateException.class,
            () -> executor.execute(graph, node -> {}));
        assertTrue(ex.getMessage().toLowerCase().contains("deadlock"),
            "expected 'deadlock' in message; got: " + ex.getMessage());
    }

    @Test
    void multiThreadedDeadlockThrows() throws Exception {
        var a = stub("a");
        var b = stub("b");
        var graph = DagBuilder.build(List.of(a, b));
        freezeAllInDegrees(graph);

        var executor = new MultiThreadedExecutor(2);
        try {
            var ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(graph, node -> {}));
            assertTrue(ex.getMessage().toLowerCase().contains("deadlock"));
        } finally {
            executor.shutdown();
        }
    }
}
