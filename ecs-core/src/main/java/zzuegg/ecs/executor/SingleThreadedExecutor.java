package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

import java.util.function.Consumer;

public final class SingleThreadedExecutor implements Executor {

    @Override
    public void execute(ScheduleGraph graph, Consumer<ScheduleGraph.SystemNode> runner) {
        // Use the pre-computed topological order instead of running
        // the readySystems/complete DAG loop on every tick. The flat
        // array is built once (Kahn's algorithm in flatOrder()) and
        // cached; iterating it is a plain indexed loop with zero
        // per-tick HashMap lookups, ArrayList allocations, or boolean
        // scans. The multi-threaded executor still uses the DAG loop
        // for parallel dispatch.
        for (var node : graph.flatOrder()) {
            runner.accept(node);
        }
    }
}
