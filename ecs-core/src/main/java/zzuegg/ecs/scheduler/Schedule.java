package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;
import java.util.stream.Collectors;

public final class Schedule {

    private final TreeMap<Stage, ScheduleGraph> stages = new TreeMap<>();
    // Cached view of stages.entrySet() so World.tick() doesn't pay a
    // List.copyOf() + TreeMap iteration on every call. The schedule is
    // immutable after construction (a new Schedule is built from scratch
    // whenever systems are added/removed), so this list stays valid for
    // the whole lifetime of the instance.
    private final List<Map.Entry<Stage, ScheduleGraph>> orderedStagesView;

    public Schedule(List<SystemDescriptor> allDescriptors, Map<String, Stage> stageMap) {
        var byStage = allDescriptors.stream()
            .collect(Collectors.groupingBy(d -> {
                var stage = stageMap.get(d.stage());
                if (stage == null) {
                    throw new IllegalArgumentException("Unknown stage: " + d.stage());
                }
                return stage;
            }));

        for (var entry : byStage.entrySet()) {
            stages.put(entry.getKey(), DagBuilder.build(entry.getValue()));
        }

        for (var stage : stageMap.values()) {
            stages.putIfAbsent(stage, DagBuilder.build(List.of()));
        }

        this.orderedStagesView = List.copyOf(stages.entrySet());
    }

    public List<Map.Entry<Stage, ScheduleGraph>> orderedStages() {
        return orderedStagesView;
    }

    public ScheduleGraph graphForStage(Stage stage) {
        return stages.get(stage);
    }
}
