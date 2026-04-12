package zzuegg.ecs.world;

import zzuegg.ecs.executor.Executor;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.scheduler.Stage;
import zzuegg.ecs.storage.ComponentStorage;

import java.util.*;

public final class WorldBuilder {

    final List<Class<?>> systemClasses = new ArrayList<>();
    final List<Object> systemInstances = new ArrayList<>();
    final List<Object> resources = new ArrayList<>();
    final List<Class<? extends Record>> eventTypes = new ArrayList<>();
    final Map<String, Stage> stages = new LinkedHashMap<>();
    Executor executor;
    ComponentStorage.Factory storageFactory;
    // True when the user is using the default storage factory — lets tier-1
    // safely assume every ComponentStorage is a DefaultComponentStorage and
    // access the backing array directly via rawArray(). Flipped to false
    // by storageFactory() — which also enables the SoA tier-1 inline path
    // for write-heavy workloads.
    boolean useDefaultStorageFactory = true;
    boolean useGeneratedProcessors = true;
    int chunkSize = 1024;

    WorldBuilder() {
        stages.put("First", Stage.FIRST);
        stages.put("PreUpdate", Stage.PRE_UPDATE);
        stages.put("Update", Stage.UPDATE);
        stages.put("PostUpdate", Stage.POST_UPDATE);
        stages.put("Last", Stage.LAST);
    }

    public WorldBuilder addSystem(Class<?> systemClass) {
        systemClasses.add(systemClass);
        return this;
    }

    public WorldBuilder addSystem(Object systemInstance) {
        systemInstances.add(systemInstance);
        return this;
    }

    public WorldBuilder addResource(Object resource) {
        resources.add(resource);
        return this;
    }

    public WorldBuilder addEvent(Class<? extends Record> eventType) {
        eventTypes.add(eventType);
        return this;
    }

    public WorldBuilder addStage(String name, Stage stage) {
        stages.put(name, new Stage(name, stage.order()));
        return this;
    }

    public WorldBuilder executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    public WorldBuilder chunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
        return this;
    }

    public WorldBuilder useGeneratedProcessors(boolean enabled) {
        this.useGeneratedProcessors = enabled;
        return this;
    }

    public WorldBuilder storageFactory(ComponentStorage.Factory factory) {
        this.storageFactory = factory;
        // A custom factory might return something that isn't a
        // DefaultComponentStorage, so we can't use the tier-1 raw-array path.
        this.useDefaultStorageFactory = false;
        return this;
    }

    public World build() {
        if (executor == null) {
            executor = Executors.singleThreaded();
        }
        if (storageFactory == null) {
            storageFactory = ComponentStorage.defaultFactory();
        }
        return new World(this);
    }
}
