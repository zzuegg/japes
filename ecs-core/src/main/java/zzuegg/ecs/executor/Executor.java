package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

import java.util.function.Consumer;

public interface Executor {
    void execute(ScheduleGraph graph, Consumer<ScheduleGraph.SystemNode> runner);
    default void shutdown() {}
}
