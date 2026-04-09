package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

public final class SingleThreadedExecutor implements Executor {

    @Override
    public void execute(ScheduleGraph graph) {
        graph.reset();
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Deadlock: no systems ready but graph not complete");
            }
            for (var node : ready) {
                graph.complete(node);
            }
        }
    }
}
