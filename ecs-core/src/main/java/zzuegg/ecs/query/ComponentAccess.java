package zzuegg.ecs.query;

import zzuegg.ecs.component.ComponentId;

public record ComponentAccess(ComponentId componentId, Class<? extends Record> type, AccessType accessType) {}
