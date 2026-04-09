package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;
import java.util.stream.Collectors;

public final class Schedule {

    private final TreeMap<Stage, ScheduleGraph> stages = new TreeMap<>();

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
    }

    public List<Map.Entry<Stage, ScheduleGraph>> orderedStages() {
        return List.copyOf(stages.entrySet());
    }

    public ScheduleGraph graphForStage(Stage stage) {
        return stages.get(stage);
    }
}
