package zzuegg.ecs.component;

public record ComponentInfo(
    ComponentId id,
    Class<? extends Record> type,
    boolean isTableStorage,
    boolean isSparseStorage,
    boolean isValueTracked
) {}
