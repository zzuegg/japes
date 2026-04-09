package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

public interface Executor {
    void execute(ScheduleGraph graph);
    default void shutdown() {}
}
