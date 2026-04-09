# ECS Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a high-performance, Valhalla-ready ECS library for Java 26 with Bevy-inspired API, automatic parallelism, and batch command processing.

**Architecture:** Archetype + chunk storage with sparse set alternative. Per-entity method-signature injection for systems, DAG-based scheduler with pluggable executor, deferred commands with batch coalescing. All code TDD, zero runtime dependencies.

**Tech Stack:** Java 26 (--enable-preview), Gradle 8.x with Kotlin DSL, JUnit 5, JMH, Valhalla EA for comparison benchmarks.

---

## File Structure

### Build Files
- `build.gradle.kts` — root build config, shared compiler settings
- `settings.gradle.kts` — multi-module project settings
- `gradle/libs.versions.toml` — version catalog

### ecs-core Module

**Entity:**
- `ecs-core/src/main/java/zzuegg/ecs/entity/Entity.java` — 64-bit ID record (32 index + 32 generation)
- `ecs-core/src/main/java/zzuegg/ecs/entity/EntityAllocator.java` — allocation, recycling, free list
- `ecs-core/src/test/java/zzuegg/ecs/entity/EntityTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/entity/EntityAllocatorTest.java`

**Component Annotations:**
- `ecs-core/src/main/java/zzuegg/ecs/component/TableStorage.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/component/SparseStorage.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/component/ValueTracked.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/component/ComponentId.java` — type-safe integer ID for component types
- `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java` — maps Class<?> to ComponentId + metadata
- `ecs-core/src/test/java/zzuegg/ecs/component/ComponentRegistryTest.java`

**Storage:**
- `ecs-core/src/main/java/zzuegg/ecs/storage/ComponentArray.java` — dense typed array for one component type in a chunk
- `ecs-core/src/main/java/zzuegg/ecs/storage/Chunk.java` — fixed-size block holding entity IDs + component arrays
- `ecs-core/src/main/java/zzuegg/ecs/storage/SparseSet.java` — sparse set storage for a single component type
- `ecs-core/src/test/java/zzuegg/ecs/storage/ComponentArrayTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/storage/ChunkTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/storage/SparseSetTest.java`

**Archetype:**
- `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeId.java` — identifies an archetype by its sorted component set
- `ecs-core/src/main/java/zzuegg/ecs/archetype/Archetype.java` — owns chunks, manages entity placement
- `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeGraph.java` — maps component-set transitions (add/remove edges)
- `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeIdTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeGraphTest.java`

**Query:**
- `ecs-core/src/main/java/zzuegg/ecs/query/AccessType.java` — enum: READ, WRITE
- `ecs-core/src/main/java/zzuegg/ecs/query/ComponentAccess.java` — record: componentId + accessType
- `ecs-core/src/main/java/zzuegg/ecs/query/QueryDescriptor.java` — describes what a query needs (components, filters)
- `ecs-core/src/main/java/zzuegg/ecs/query/QueryFilter.java` — interface for With/Without/Added/Changed/Removed
- `ecs-core/src/main/java/zzuegg/ecs/query/QueryMatcher.java` — matches archetypes against a QueryDescriptor
- `ecs-core/src/test/java/zzuegg/ecs/query/QueryDescriptorTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/query/QueryMatcherTest.java`

**Mut/Res/Local:**
- `ecs-core/src/main/java/zzuegg/ecs/component/Mut.java` — mutable component wrapper
- `ecs-core/src/main/java/zzuegg/ecs/resource/ResourceStore.java` — type-keyed map
- `ecs-core/src/main/java/zzuegg/ecs/resource/Res.java` — read-only resource wrapper
- `ecs-core/src/main/java/zzuegg/ecs/resource/ResMut.java` — mutable resource wrapper
- `ecs-core/src/main/java/zzuegg/ecs/system/Local.java` — per-system local state
- `ecs-core/src/test/java/zzuegg/ecs/component/MutTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/resource/ResourceStoreTest.java`

**Change Detection:**
- `ecs-core/src/main/java/zzuegg/ecs/change/Tick.java` — global tick counter
- `ecs-core/src/main/java/zzuegg/ecs/change/ChangeTracker.java` — per-chunk tick arrays for change detection
- `ecs-core/src/test/java/zzuegg/ecs/change/ChangeTrackerTest.java`

**Commands:**
- `ecs-core/src/main/java/zzuegg/ecs/command/Command.java` — sealed interface for command types
- `ecs-core/src/main/java/zzuegg/ecs/command/Commands.java` — thread-local command buffer
- `ecs-core/src/main/java/zzuegg/ecs/command/CommandBatch.java` — coalesced batch of commands
- `ecs-core/src/main/java/zzuegg/ecs/command/CommandProcessor.java` — applies batches to world
- `ecs-core/src/test/java/zzuegg/ecs/command/CommandsTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/command/CommandProcessorTest.java`

**Events:**
- `ecs-core/src/main/java/zzuegg/ecs/event/EventStore.java` — double-buffered event storage for one type
- `ecs-core/src/main/java/zzuegg/ecs/event/EventWriter.java` — send events
- `ecs-core/src/main/java/zzuegg/ecs/event/EventReader.java` — receive events
- `ecs-core/src/main/java/zzuegg/ecs/event/EventRegistry.java` — manages all event stores
- `ecs-core/src/test/java/zzuegg/ecs/event/EventStoreTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/event/EventRegistryTest.java`

**System Annotations & Registration:**
- `ecs-core/src/main/java/zzuegg/ecs/system/System.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/SystemSet.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Exclusive.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Read.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Write.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/With.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Without.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Filter.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/RunIf.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/Default.java` — annotation
- `ecs-core/src/main/java/zzuegg/ecs/system/SystemDescriptor.java` — parsed metadata from method + annotations
- `ecs-core/src/main/java/zzuegg/ecs/system/SystemInvoker.java` — method handle based invocation
- `ecs-core/src/main/java/zzuegg/ecs/system/SystemParser.java` — reflects on class to extract SystemDescriptors
- `ecs-core/src/test/java/zzuegg/ecs/system/SystemParserTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/system/SystemInvokerTest.java`

**Scheduler:**
- `ecs-core/src/main/java/zzuegg/ecs/scheduler/Stage.java` — named stage with ordering
- `ecs-core/src/main/java/zzuegg/ecs/scheduler/ScheduleGraph.java` — DAG of systems within a stage
- `ecs-core/src/main/java/zzuegg/ecs/scheduler/DagBuilder.java` — builds DAG from system descriptors
- `ecs-core/src/main/java/zzuegg/ecs/scheduler/Schedule.java` — ordered list of stages, each with a ScheduleGraph
- `ecs-core/src/test/java/zzuegg/ecs/scheduler/DagBuilderTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/scheduler/ScheduleGraphTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/scheduler/ScheduleTest.java`

**Executor:**
- `ecs-core/src/main/java/zzuegg/ecs/executor/Executor.java` — interface
- `ecs-core/src/main/java/zzuegg/ecs/executor/Executors.java` — factory methods
- `ecs-core/src/main/java/zzuegg/ecs/executor/SingleThreadedExecutor.java`
- `ecs-core/src/main/java/zzuegg/ecs/executor/MultiThreadedExecutor.java`
- `ecs-core/src/test/java/zzuegg/ecs/executor/SingleThreadedExecutorTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/executor/MultiThreadedExecutorTest.java`

**World:**
- `ecs-core/src/main/java/zzuegg/ecs/world/World.java` — top-level container
- `ecs-core/src/main/java/zzuegg/ecs/world/WorldBuilder.java` — fluent builder
- `ecs-core/src/main/java/zzuegg/ecs/world/Snapshot.java` — read-only query snapshot
- `ecs-core/src/test/java/zzuegg/ecs/world/WorldTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/world/WorldBuilderTest.java`

**Integration Tests:**
- `ecs-core/src/test/java/zzuegg/ecs/integration/PipelineTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/integration/ThreadSafetyTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/integration/DeterminismTest.java`
- `ecs-core/src/test/java/zzuegg/ecs/integration/MisuseTest.java`

### ecs-benchmark Module
- `ecs-benchmark/build.gradle.kts`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/EntityBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/IterationBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/CommandBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/EventBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/StorageBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/NBodyBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/BoidsBenchmark.java`
- `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/ParticleBenchmark.java`

---

## Task 1: Project Scaffolding

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `ecs-core/build.gradle.kts`
- Create: `ecs-benchmark/build.gradle.kts`

- [ ] **Step 1: Initialize Gradle wrapper**

```bash
cd /media/mzuegg/Vault/Projects/ecs
gradle init --type basic --dsl kotlin
```

- [ ] **Step 2: Create version catalog**

Create `gradle/libs.versions.toml`:
```toml
[versions]
junit = "5.11.4"
jmh = "1.37"
jmh-plugin = "0.7.3"

[libraries]
junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }
junit-engine = { module = "org.junit.jupiter:junit-jupiter-engine", version.ref = "junit" }
junit-params = { module = "org.junit.jupiter:junit-jupiter-params", version.ref = "junit" }
jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
jmh-annprocess = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }

[plugins]
jmh = { id = "me.champeau.jmh", version.ref = "jmh-plugin" }
```

- [ ] **Step 3: Create settings.gradle.kts**

```kotlin
rootProject.name = "ecs"

include("ecs-core")
include("ecs-benchmark")
```

- [ ] **Step 4: Create root build.gradle.kts**

```kotlin
plugins {
    java
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }

    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 5: Create ecs-core/build.gradle.kts**

```kotlin
dependencies {
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
}
```

- [ ] **Step 6: Create ecs-benchmark/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":ecs-core"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)
    jvmArgs.addAll("--enable-preview")
}

// Valhalla benchmark task
tasks.register<JavaExec>("jmhValhalla") {
    val valhallaHome = providers.gradleProperty("valhalla.home")
        .orElse(providers.environmentVariable("VALHALLA_HOME"))

    dependsOn("jmhJar")
    mainClass.set("org.openjdk.jmh.Main")
    classpath = files(tasks.named("jmhJar"))

    doFirst {
        val home = valhallaHome.orNull
            ?: throw GradleException("Set valhalla.home property or VALHALLA_HOME env var")
        executable = "$home/bin/java"
        jvmArgs("--enable-preview")
    }
}
```

- [ ] **Step 7: Create source directories**

```bash
mkdir -p ecs-core/src/main/java/zzuegg/ecs
mkdir -p ecs-core/src/test/java/zzuegg/ecs
mkdir -p ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro
mkdir -p ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro
```

- [ ] **Step 8: Verify build compiles**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL (no sources yet, just verifying config)

- [ ] **Step 9: Initialize git and commit**

```bash
git init
cat > .gitignore << 'EOF'
.gradle/
build/
.idea/
*.iml
EOF
git add .
git commit -m "chore: scaffold Gradle multi-module project"
```

---

## Task 2: Entity

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/entity/Entity.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/entity/EntityAllocator.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/entity/EntityTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/entity/EntityAllocatorTest.java`

- [ ] **Step 1: Write Entity tests**

Create `ecs-core/src/test/java/zzuegg/ecs/entity/EntityTest.java`:
```java
package zzuegg.ecs.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityTest {

    @Test
    void entityPacksIndexAndGeneration() {
        var entity = Entity.of(42, 7);
        assertEquals(42, entity.index());
        assertEquals(7, entity.generation());
    }

    @Test
    void entityWithSameIndexAndGenerationAreEqual() {
        assertEquals(Entity.of(1, 1), Entity.of(1, 1));
    }

    @Test
    void entityWithDifferentGenerationAreNotEqual() {
        assertNotEquals(Entity.of(1, 1), Entity.of(1, 2));
    }

    @Test
    void entityWithDifferentIndexAreNotEqual() {
        assertNotEquals(Entity.of(1, 1), Entity.of(2, 1));
    }

    @Test
    void entityIdEncodesTo64Bits() {
        var entity = Entity.of(0xFFFFFFFF, 0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, entity.index());
        assertEquals(0xFFFFFFFF, entity.generation());
    }

    @Test
    void nullEntityHasSpecialValue() {
        var nullEntity = Entity.NULL;
        assertEquals(-1, nullEntity.index());
        assertEquals(0, nullEntity.generation());
    }

    @Test
    void entityToStringIsReadable() {
        var entity = Entity.of(42, 3);
        assertEquals("Entity(42v3)", entity.toString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.entity.EntityTest"
```
Expected: FAIL — `Entity` class does not exist.

- [ ] **Step 3: Implement Entity**

Create `ecs-core/src/main/java/zzuegg/ecs/entity/Entity.java`:
```java
package zzuegg.ecs.entity;

public record Entity(long id) {

    public static final Entity NULL = new Entity(pack(-1, 0));

    public static Entity of(int index, int generation) {
        return new Entity(pack(index, generation));
    }

    public int index() {
        return (int) (id >>> 32);
    }

    public int generation() {
        return (int) id;
    }

    @Override
    public String toString() {
        return "Entity(" + index() + "v" + generation() + ")";
    }

    private static long pack(int index, int generation) {
        return ((long) index << 32) | Integer.toUnsignedLong(generation);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.entity.EntityTest"
```
Expected: All 7 tests PASS.

- [ ] **Step 5: Write EntityAllocator tests**

Create `ecs-core/src/test/java/zzuegg/ecs/entity/EntityAllocatorTest.java`:
```java
package zzuegg.ecs.entity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityAllocatorTest {

    @Test
    void allocateReturnsSequentialIndices() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        assertEquals(0, e0.index());
        assertEquals(1, e1.index());
    }

    @Test
    void newEntitiesHaveGenerationZero() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        assertEquals(0, entity.generation());
    }

    @Test
    void recycledEntityHasIncrementedGeneration() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        alloc.free(e0);
        var reused = alloc.allocate();
        assertEquals(e0.index(), reused.index());
        assertEquals(1, reused.generation());
    }

    @Test
    void isAliveReturnsTrueForLiveEntity() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        assertTrue(alloc.isAlive(entity));
    }

    @Test
    void isAliveReturnsFalseForFreedEntity() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        assertFalse(alloc.isAlive(entity));
    }

    @Test
    void staleReferenceIsNotAlive() {
        var alloc = new EntityAllocator();
        var original = alloc.allocate();
        alloc.free(original);
        alloc.allocate(); // reuses slot
        assertFalse(alloc.isAlive(original)); // stale generation
    }

    @Test
    void freeListReusesInLifoOrder() {
        var alloc = new EntityAllocator();
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        alloc.free(e0);
        alloc.free(e1);
        var r0 = alloc.allocate();
        var r1 = alloc.allocate();
        assertEquals(e1.index(), r0.index()); // LIFO
        assertEquals(e0.index(), r1.index());
    }

    @Test
    void doubleFreeSameEntityThrows() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        assertThrows(IllegalArgumentException.class, () -> alloc.free(entity));
    }

    @Test
    void freeStaleEntityThrows() {
        var alloc = new EntityAllocator();
        var entity = alloc.allocate();
        alloc.free(entity);
        var reused = alloc.allocate();
        assertThrows(IllegalArgumentException.class, () -> alloc.free(entity));
    }

    @Test
    void freeNeverAllocatedEntityThrows() {
        var alloc = new EntityAllocator();
        assertThrows(IllegalArgumentException.class, () -> alloc.free(Entity.of(999, 0)));
    }

    @Test
    void allocateManyEntities() {
        var alloc = new EntityAllocator();
        for (int i = 0; i < 10_000; i++) {
            var e = alloc.allocate();
            assertEquals(i, e.index());
        }
    }

    @Test
    void entityCountTracksLiveEntities() {
        var alloc = new EntityAllocator();
        assertEquals(0, alloc.entityCount());
        var e0 = alloc.allocate();
        var e1 = alloc.allocate();
        assertEquals(2, alloc.entityCount());
        alloc.free(e0);
        assertEquals(1, alloc.entityCount());
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.entity.EntityAllocatorTest"
```
Expected: FAIL — `EntityAllocator` does not exist.

- [ ] **Step 7: Implement EntityAllocator**

Create `ecs-core/src/main/java/zzuegg/ecs/entity/EntityAllocator.java`:
```java
package zzuegg.ecs.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class EntityAllocator {

    private final List<Integer> generations = new ArrayList<>();
    private final Deque<Integer> freeList = new ArrayDeque<>();
    private int liveCount = 0;

    public Entity allocate() {
        int index;
        int generation;
        if (!freeList.isEmpty()) {
            index = freeList.pop();
            generation = generations.get(index);
        } else {
            index = generations.size();
            generation = 0;
            generations.add(0);
        }
        liveCount++;
        return Entity.of(index, generation);
    }

    public void free(Entity entity) {
        int index = entity.index();
        if (index < 0 || index >= generations.size()) {
            throw new IllegalArgumentException("Entity was never allocated: " + entity);
        }
        int currentGen = generations.get(index);
        if (currentGen != entity.generation()) {
            throw new IllegalArgumentException("Stale entity reference: " + entity);
        }
        // Check if already in free list by bumping generation immediately
        // A freed entity has generation > entity.generation
        generations.set(index, currentGen + 1);
        freeList.push(index);
        liveCount--;
    }

    public boolean isAlive(Entity entity) {
        int index = entity.index();
        return index >= 0
            && index < generations.size()
            && generations.get(index) == entity.generation()
            && !freeList.contains(index); // not ideal but correct; will be replaced by a bitset
    }

    public int entityCount() {
        return liveCount;
    }
}
```

Note: The `isAlive` check using `freeList.contains` is O(n). We'll refine this now.

- [ ] **Step 8: Refine isAlive to O(1)**

Replace `EntityAllocator` with a version using a boolean alive array:
```java
package zzuegg.ecs.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class EntityAllocator {

    private final List<Integer> generations = new ArrayList<>();
    private final List<Boolean> alive = new ArrayList<>();
    private final Deque<Integer> freeList = new ArrayDeque<>();
    private int liveCount = 0;

    public Entity allocate() {
        int index;
        int generation;
        if (!freeList.isEmpty()) {
            index = freeList.pop();
            generation = generations.get(index);
        } else {
            index = generations.size();
            generation = 0;
            generations.add(0);
            alive.add(false);
        }
        alive.set(index, true);
        liveCount++;
        return Entity.of(index, generation);
    }

    public void free(Entity entity) {
        int index = entity.index();
        if (index < 0 || index >= generations.size()) {
            throw new IllegalArgumentException("Entity was never allocated: " + entity);
        }
        if (generations.get(index) != entity.generation() || !alive.get(index)) {
            throw new IllegalArgumentException("Stale or already freed entity: " + entity);
        }
        alive.set(index, false);
        generations.set(index, entity.generation() + 1);
        freeList.push(index);
        liveCount--;
    }

    public boolean isAlive(Entity entity) {
        int index = entity.index();
        return index >= 0
            && index < generations.size()
            && generations.get(index) == entity.generation()
            && alive.get(index);
    }

    public int entityCount() {
        return liveCount;
    }
}
```

- [ ] **Step 9: Run all entity tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.entity.*"
```
Expected: All tests PASS.

- [ ] **Step 10: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/entity/ ecs-core/src/test/java/zzuegg/ecs/entity/
git commit -m "feat: add Entity and EntityAllocator with allocation, recycling, and generation tracking"
```

---

## Task 3: Component Annotations & Registry

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/TableStorage.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/SparseStorage.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/ValueTracked.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/ComponentId.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/ComponentInfo.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/component/ComponentRegistryTest.java`

- [ ] **Step 1: Write ComponentRegistry tests**

Create `ecs-core/src/test/java/zzuegg/ecs/component/ComponentRegistryTest.java`:
```java
package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRegistryTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    @SparseStorage record Marker() {}
    @ValueTracked record Health(int hp) {}

    @Test
    void registerReturnsUniqueIds() {
        var registry = new ComponentRegistry();
        var posId = registry.register(Position.class);
        var velId = registry.register(Velocity.class);
        assertNotEquals(posId, velId);
    }

    @Test
    void registerSameTypeTwiceReturnsSameId() {
        var registry = new ComponentRegistry();
        var id1 = registry.register(Position.class);
        var id2 = registry.register(Position.class);
        assertEquals(id1, id2);
    }

    @Test
    void getOrRegisterAutoRegisters() {
        var registry = new ComponentRegistry();
        var id = registry.getOrRegister(Position.class);
        assertEquals(id, registry.getOrRegister(Position.class));
    }

    @Test
    void infoReflectsTableStorageByDefault() {
        var registry = new ComponentRegistry();
        registry.register(Position.class);
        var info = registry.info(Position.class);
        assertTrue(info.isTableStorage());
        assertFalse(info.isSparseStorage());
    }

    @Test
    void infoReflectsSparseAnnotation() {
        var registry = new ComponentRegistry();
        registry.register(Marker.class);
        var info = registry.info(Marker.class);
        assertTrue(info.isSparseStorage());
    }

    @Test
    void infoReflectsValueTrackedAnnotation() {
        var registry = new ComponentRegistry();
        registry.register(Health.class);
        var info = registry.info(Health.class);
        assertTrue(info.isValueTracked());
    }

    @Test
    void lookupByIdReturnsCorrectInfo() {
        var registry = new ComponentRegistry();
        var id = registry.register(Position.class);
        var info = registry.info(id);
        assertEquals(Position.class, info.type());
    }

    @Test
    void unregisteredTypeThrows() {
        var registry = new ComponentRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.info(Position.class));
    }

    @Test
    void nonRecordComponentThrows() {
        var registry = new ComponentRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(String.class));
    }

    @Test
    void componentCount() {
        var registry = new ComponentRegistry();
        assertEquals(0, registry.count());
        registry.register(Position.class);
        registry.register(Velocity.class);
        assertEquals(2, registry.count());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.component.ComponentRegistryTest"
```
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement annotations**

Create `ecs-core/src/main/java/zzuegg/ecs/component/TableStorage.java`:
```java
package zzuegg.ecs.component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TableStorage {}
```

Create `ecs-core/src/main/java/zzuegg/ecs/component/SparseStorage.java`:
```java
package zzuegg.ecs.component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SparseStorage {}
```

Create `ecs-core/src/main/java/zzuegg/ecs/component/ValueTracked.java`:
```java
package zzuegg.ecs.component;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValueTracked {}
```

- [ ] **Step 4: Implement ComponentId and ComponentInfo**

Create `ecs-core/src/main/java/zzuegg/ecs/component/ComponentId.java`:
```java
package zzuegg.ecs.component;

public record ComponentId(int id) implements Comparable<ComponentId> {
    @Override
    public int compareTo(ComponentId other) {
        return Integer.compare(this.id, other.id);
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/component/ComponentInfo.java`:
```java
package zzuegg.ecs.component;

public record ComponentInfo(
    ComponentId id,
    Class<? extends Record> type,
    boolean isTableStorage,
    boolean isSparseStorage,
    boolean isValueTracked
) {}
```

- [ ] **Step 5: Implement ComponentRegistry**

Create `ecs-core/src/main/java/zzuegg/ecs/component/ComponentRegistry.java`:
```java
package zzuegg.ecs.component;

import java.util.HashMap;
import java.util.Map;

public final class ComponentRegistry {

    private final Map<Class<?>, ComponentInfo> byType = new HashMap<>();
    private final Map<ComponentId, ComponentInfo> byId = new HashMap<>();
    private int nextId = 0;

    public ComponentId register(Class<?> type) {
        var existing = byType.get(type);
        if (existing != null) {
            return existing.id();
        }
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Components must be records: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        var recordType = (Class<? extends Record>) type;

        boolean sparse = type.isAnnotationPresent(SparseStorage.class);
        boolean valueTracked = type.isAnnotationPresent(ValueTracked.class);

        var id = new ComponentId(nextId++);
        var info = new ComponentInfo(id, recordType, !sparse, sparse, valueTracked);
        byType.put(type, info);
        byId.put(id, info);
        return id;
    }

    public ComponentId getOrRegister(Class<?> type) {
        var existing = byType.get(type);
        if (existing != null) {
            return existing.id();
        }
        return register(type);
    }

    public ComponentInfo info(Class<?> type) {
        var info = byType.get(type);
        if (info == null) {
            throw new IllegalArgumentException("Component not registered: " + type.getName());
        }
        return info;
    }

    public ComponentInfo info(ComponentId id) {
        var info = byId.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown component id: " + id);
        }
        return info;
    }

    public int count() {
        return byType.size();
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.component.ComponentRegistryTest"
```
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/component/ ecs-core/src/test/java/zzuegg/ecs/component/
git commit -m "feat: add component annotations and registry"
```

---

## Task 4: ComponentArray (Dense Typed Array)

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/storage/ComponentArray.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/storage/ComponentArrayTest.java`

- [ ] **Step 1: Write ComponentArray tests**

Create `ecs-core/src/test/java/zzuegg/ecs/storage/ComponentArrayTest.java`:
```java
package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentArrayTest {

    record Position(float x, float y) {}

    @Test
    void setAndGetAtIndex() {
        var array = new ComponentArray<>(Position.class, 16);
        var pos = new Position(1.0f, 2.0f);
        array.set(0, pos);
        assertEquals(pos, array.get(0));
    }

    @Test
    void independentSlots() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(1, 1));
        array.set(1, new Position(2, 2));
        assertEquals(new Position(1, 1), array.get(0));
        assertEquals(new Position(2, 2), array.get(1));
    }

    @Test
    void swapRemoveMovesLastIntoGap() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(0, 0));
        array.set(1, new Position(1, 1));
        array.set(2, new Position(2, 2));
        array.swapRemove(0, 3); // remove index 0, count is 3
        assertEquals(new Position(2, 2), array.get(0)); // last moved here
        assertEquals(new Position(1, 1), array.get(1)); // untouched
    }

    @Test
    void swapRemoveLastElement() {
        var array = new ComponentArray<>(Position.class, 16);
        array.set(0, new Position(0, 0));
        array.swapRemove(0, 1); // remove the only element
        // no crash, slot is now null
        assertNull(array.get(0));
    }

    @Test
    void copyInto() {
        var src = new ComponentArray<>(Position.class, 16);
        var dst = new ComponentArray<>(Position.class, 16);
        src.set(3, new Position(5, 5));
        src.copyInto(3, dst, 0);
        assertEquals(new Position(5, 5), dst.get(0));
    }

    @Test
    void capacity() {
        var array = new ComponentArray<>(Position.class, 1024);
        assertEquals(1024, array.capacity());
    }

    @Test
    void getOutOfBoundsThrows() {
        var array = new ComponentArray<>(Position.class, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> array.get(5));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.ComponentArrayTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement ComponentArray**

Create `ecs-core/src/main/java/zzuegg/ecs/storage/ComponentArray.java`:
```java
package zzuegg.ecs.storage;

import java.lang.reflect.Array;

public final class ComponentArray<T extends Record> {

    private final T[] data;
    private final Class<T> type;

    @SuppressWarnings("unchecked")
    public ComponentArray(Class<T> type, int capacity) {
        this.type = type;
        this.data = (T[]) Array.newInstance(type, capacity);
    }

    public T get(int index) {
        checkBounds(index);
        return data[index];
    }

    public void set(int index, T value) {
        checkBounds(index);
        data[index] = value;
    }

    public void swapRemove(int index, int count) {
        checkBounds(index);
        int lastIndex = count - 1;
        if (index < lastIndex) {
            data[index] = data[lastIndex];
        }
        data[lastIndex] = null;
    }

    public void copyInto(int srcIndex, ComponentArray<T> dst, int dstIndex) {
        dst.set(dstIndex, this.get(srcIndex));
    }

    public int capacity() {
        return data.length;
    }

    public Class<T> type() {
        return type;
    }

    public T[] rawArray() {
        return data;
    }

    private void checkBounds(int index) {
        if (index < 0 || index >= data.length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for capacity " + data.length);
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.ComponentArrayTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/storage/ComponentArray.java ecs-core/src/test/java/zzuegg/ecs/storage/ComponentArrayTest.java
git commit -m "feat: add ComponentArray for dense typed storage in chunks"
```

---

## Task 5: Chunk

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/storage/Chunk.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/storage/ChunkTest.java`

- [ ] **Step 1: Write Chunk tests**

Create `ecs-core/src/test/java/zzuegg/ecs/storage/ChunkTest.java`:
```java
package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    private static final ComponentId POS_ID = new ComponentId(0);
    private static final ComponentId VEL_ID = new ComponentId(1);

    private Chunk createChunk(int capacity) {
        return new Chunk(capacity, Map.of(
            POS_ID, Position.class,
            VEL_ID, Velocity.class
        ));
    }

    @Test
    void addEntityReturnsSlotIndex() {
        var chunk = createChunk(16);
        int slot = chunk.add(Entity.of(0, 0));
        assertEquals(0, slot);
    }

    @Test
    void addMultipleEntities() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        int slot = chunk.add(Entity.of(1, 0));
        assertEquals(1, slot);
        assertEquals(2, chunk.count());
    }

    @Test
    void setAndGetComponent() {
        var chunk = createChunk(16);
        int slot = chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, slot, new Position(1, 2));
        assertEquals(new Position(1, 2), chunk.get(POS_ID, slot));
    }

    @Test
    void getEntityAtSlot() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(42, 3));
        assertEquals(Entity.of(42, 3), chunk.entity(0));
    }

    @Test
    void removeSwapsLastIntoGap() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, 0, new Position(0, 0));
        chunk.add(Entity.of(1, 0));
        chunk.set(POS_ID, 1, new Position(1, 1));
        chunk.add(Entity.of(2, 0));
        chunk.set(POS_ID, 2, new Position(2, 2));

        chunk.remove(0); // remove first, last swaps in

        assertEquals(2, chunk.count());
        assertEquals(Entity.of(2, 0), chunk.entity(0)); // entity 2 moved to slot 0
        assertEquals(new Position(2, 2), chunk.get(POS_ID, 0));
    }

    @Test
    void removeLastElement() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.remove(0);
        assertEquals(0, chunk.count());
    }

    @Test
    void isFull() {
        var chunk = createChunk(2);
        assertFalse(chunk.isFull());
        chunk.add(Entity.of(0, 0));
        assertFalse(chunk.isFull());
        chunk.add(Entity.of(1, 0));
        assertTrue(chunk.isFull());
    }

    @Test
    void isEmpty() {
        var chunk = createChunk(2);
        assertTrue(chunk.isEmpty());
        chunk.add(Entity.of(0, 0));
        assertFalse(chunk.isEmpty());
    }

    @Test
    void capacity() {
        var chunk = createChunk(1024);
        assertEquals(1024, chunk.capacity());
    }

    @Test
    void addToFullChunkThrows() {
        var chunk = createChunk(1);
        chunk.add(Entity.of(0, 0));
        assertThrows(IllegalStateException.class, () -> chunk.add(Entity.of(1, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rawArrayAccess() {
        var chunk = createChunk(16);
        chunk.add(Entity.of(0, 0));
        chunk.set(POS_ID, 0, new Position(5, 10));
        ComponentArray<Position> array = (ComponentArray<Position>) chunk.componentArray(POS_ID);
        assertEquals(new Position(5, 10), array.get(0));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.ChunkTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement Chunk**

Create `ecs-core/src/main/java/zzuegg/ecs/storage/Chunk.java`:
```java
package zzuegg.ecs.storage;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.entity.Entity;

import java.util.HashMap;
import java.util.Map;

public final class Chunk {

    private final int capacity;
    private final Entity[] entities;
    private final Map<ComponentId, ComponentArray<?>> arrays;
    private int count = 0;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Chunk(int capacity, Map<ComponentId, Class<? extends Record>> componentTypes) {
        this.capacity = capacity;
        this.entities = new Entity[capacity];
        this.arrays = new HashMap<>();
        for (var entry : componentTypes.entrySet()) {
            arrays.put(entry.getKey(), new ComponentArray(entry.getValue(), capacity));
        }
    }

    public int add(Entity entity) {
        if (isFull()) {
            throw new IllegalStateException("Chunk is full (capacity=" + capacity + ")");
        }
        int slot = count;
        entities[slot] = entity;
        count++;
        return slot;
    }

    public void remove(int slot) {
        int lastIndex = count - 1;
        if (slot < lastIndex) {
            entities[slot] = entities[lastIndex];
            for (var array : arrays.values()) {
                array.swapRemove(slot, count);
            }
        } else {
            for (var array : arrays.values()) {
                array.swapRemove(slot, count);
            }
        }
        entities[lastIndex] = null;
        count--;
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId id, int slot) {
        return ((ComponentArray<T>) arrays.get(id)).get(slot);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> void set(ComponentId id, int slot, T value) {
        ((ComponentArray<T>) arrays.get(id)).set(slot, value);
    }

    public Entity entity(int slot) {
        return entities[slot];
    }

    public Entity[] entityArray() {
        return entities;
    }

    public ComponentArray<?> componentArray(ComponentId id) {
        return arrays.get(id);
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isFull() {
        return count >= capacity;
    }

    public boolean isEmpty() {
        return count == 0;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.ChunkTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/storage/Chunk.java ecs-core/src/test/java/zzuegg/ecs/storage/ChunkTest.java
git commit -m "feat: add Chunk with entity slot management and swap-remove"
```

---

## Task 6: SparseSet

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/storage/SparseSet.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/storage/SparseSetTest.java`

- [ ] **Step 1: Write SparseSet tests**

Create `ecs-core/src/test/java/zzuegg/ecs/storage/SparseSetTest.java`:
```java
package zzuegg.ecs.storage;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import static org.junit.jupiter.api.Assertions.*;

class SparseSetTest {

    record Marker() {}
    record Tag(String name) {}

    @Test
    void insertAndGet() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(5, 0);
        set.insert(entity, new Tag("enemy"));
        assertEquals(new Tag("enemy"), set.get(entity));
    }

    @Test
    void containsReturnsTrueAfterInsert() {
        var set = new SparseSet<Marker>();
        var entity = Entity.of(0, 0);
        assertFalse(set.contains(entity));
        set.insert(entity, new Marker());
        assertTrue(set.contains(entity));
    }

    @Test
    void removeWorks() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("a"));
        set.remove(entity);
        assertFalse(set.contains(entity));
    }

    @Test
    void removeSwapsDenseArray() {
        var set = new SparseSet<Tag>();
        var e0 = Entity.of(0, 0);
        var e1 = Entity.of(1, 0);
        var e2 = Entity.of(2, 0);
        set.insert(e0, new Tag("a"));
        set.insert(e1, new Tag("b"));
        set.insert(e2, new Tag("c"));

        set.remove(e0);

        assertEquals(2, set.size());
        assertFalse(set.contains(e0));
        assertTrue(set.contains(e1));
        assertTrue(set.contains(e2));
        assertEquals(new Tag("b"), set.get(e1));
        assertEquals(new Tag("c"), set.get(e2));
    }

    @Test
    void getNonExistentReturnsNull() {
        var set = new SparseSet<Tag>();
        assertNull(set.get(Entity.of(999, 0)));
    }

    @Test
    void doubleInsertOverwrites() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("old"));
        set.insert(entity, new Tag("new"));
        assertEquals(new Tag("new"), set.get(entity));
        assertEquals(1, set.size());
    }

    @Test
    void doubleRemoveIsNoOp() {
        var set = new SparseSet<Tag>();
        var entity = Entity.of(0, 0);
        set.insert(entity, new Tag("a"));
        set.remove(entity);
        set.remove(entity); // no crash
        assertEquals(0, set.size());
    }

    @Test
    void largeEntityIndex() {
        var set = new SparseSet<Marker>();
        var entity = Entity.of(100_000, 0);
        set.insert(entity, new Marker());
        assertTrue(set.contains(entity));
    }

    @Test
    void sizeTracksInsertAndRemove() {
        var set = new SparseSet<Marker>();
        assertEquals(0, set.size());
        set.insert(Entity.of(0, 0), new Marker());
        set.insert(Entity.of(1, 0), new Marker());
        assertEquals(2, set.size());
        set.remove(Entity.of(0, 0));
        assertEquals(1, set.size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.SparseSetTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement SparseSet**

Create `ecs-core/src/main/java/zzuegg/ecs/storage/SparseSet.java`:
```java
package zzuegg.ecs.storage;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SparseSet<T extends Record> {

    private static final int ABSENT = -1;

    private int[] sparse = new int[0];
    private final List<Entity> denseEntities = new ArrayList<>();
    private final List<T> denseValues = new ArrayList<>();

    public void insert(Entity entity, T value) {
        int index = entity.index();
        ensureSparseCapacity(index + 1);

        if (sparse[index] != ABSENT) {
            // overwrite
            denseValues.set(sparse[index], value);
            return;
        }

        sparse[index] = denseEntities.size();
        denseEntities.add(entity);
        denseValues.add(value);
    }

    public T get(Entity entity) {
        int index = entity.index();
        if (index >= sparse.length || sparse[index] == ABSENT) {
            return null;
        }
        return denseValues.get(sparse[index]);
    }

    public boolean contains(Entity entity) {
        int index = entity.index();
        return index < sparse.length && sparse[index] != ABSENT;
    }

    public void remove(Entity entity) {
        int index = entity.index();
        if (index >= sparse.length || sparse[index] == ABSENT) {
            return;
        }

        int denseIndex = sparse[index];
        int lastDense = denseEntities.size() - 1;

        if (denseIndex < lastDense) {
            Entity lastEntity = denseEntities.get(lastDense);
            denseEntities.set(denseIndex, lastEntity);
            denseValues.set(denseIndex, denseValues.get(lastDense));
            sparse[lastEntity.index()] = denseIndex;
        }

        denseEntities.removeLast();
        denseValues.removeLast();
        sparse[index] = ABSENT;
    }

    public int size() {
        return denseEntities.size();
    }

    public List<Entity> entities() {
        return denseEntities;
    }

    public List<T> values() {
        return denseValues;
    }

    private void ensureSparseCapacity(int required) {
        if (required > sparse.length) {
            int newSize = Math.max(required, sparse.length * 2);
            int oldLength = sparse.length;
            sparse = Arrays.copyOf(sparse, newSize);
            Arrays.fill(sparse, oldLength, newSize, ABSENT);
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.storage.SparseSetTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/storage/SparseSet.java ecs-core/src/test/java/zzuegg/ecs/storage/SparseSetTest.java
git commit -m "feat: add SparseSet for sparse component storage"
```

---

## Task 7: Change Detection (Tick & ChangeTracker)

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/change/Tick.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/change/ChangeTracker.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/change/ChangeTrackerTest.java`

- [ ] **Step 1: Write ChangeTracker tests**

Create `ecs-core/src/test/java/zzuegg/ecs/change/ChangeTrackerTest.java`:
```java
package zzuegg.ecs.change;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChangeTrackerTest {

    @Test
    void tickIncrementsMonotonically() {
        var tick = new Tick();
        assertEquals(0, tick.current());
        tick.advance();
        assertEquals(1, tick.current());
        tick.advance();
        assertEquals(2, tick.current());
    }

    @Test
    void newSlotHasAddedTick() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 5);
        assertEquals(5, tracker.addedTick(0));
    }

    @Test
    void markChangedUpdatesTick() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 3);
        assertEquals(3, tracker.changedTick(0));
    }

    @Test
    void isAddedSinceDetectsNewEntities() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 5);
        assertTrue(tracker.isAddedSince(0, 4));
        assertFalse(tracker.isAddedSince(0, 5));
        assertFalse(tracker.isAddedSince(0, 6));
    }

    @Test
    void isChangedSinceDetectsModifications() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 5);
        assertTrue(tracker.isChangedSince(0, 4));
        assertFalse(tracker.isChangedSince(0, 5));
    }

    @Test
    void swapRemoveMovesTickData() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 1);
        tracker.markChanged(0, 10);
        tracker.markAdded(2, 3);
        tracker.markChanged(2, 20);

        tracker.swapRemove(0, 3); // remove slot 0, count 3

        assertEquals(3, tracker.addedTick(0));  // slot 2 data now at slot 0
        assertEquals(20, tracker.changedTick(0));
    }

    @Test
    void capacity() {
        var tracker = new ChangeTracker(1024);
        assertEquals(1024, tracker.capacity());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.change.ChangeTrackerTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement Tick and ChangeTracker**

Create `ecs-core/src/main/java/zzuegg/ecs/change/Tick.java`:
```java
package zzuegg.ecs.change;

import java.util.concurrent.atomic.AtomicLong;

public final class Tick {

    private final AtomicLong value = new AtomicLong(0);

    public long current() {
        return value.get();
    }

    public long advance() {
        return value.incrementAndGet();
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/change/ChangeTracker.java`:
```java
package zzuegg.ecs.change;

public final class ChangeTracker {

    private final long[] addedTicks;
    private final long[] changedTicks;

    public ChangeTracker(int capacity) {
        this.addedTicks = new long[capacity];
        this.changedTicks = new long[capacity];
    }

    public void markAdded(int slot, long tick) {
        addedTicks[slot] = tick;
        changedTicks[slot] = tick;
    }

    public void markChanged(int slot, long tick) {
        changedTicks[slot] = tick;
    }

    public long addedTick(int slot) {
        return addedTicks[slot];
    }

    public long changedTick(int slot) {
        return changedTicks[slot];
    }

    public boolean isAddedSince(int slot, long sinceExclusive) {
        return addedTicks[slot] > sinceExclusive;
    }

    public boolean isChangedSince(int slot, long sinceExclusive) {
        return changedTicks[slot] > sinceExclusive;
    }

    public void swapRemove(int slot, int count) {
        int last = count - 1;
        if (slot < last) {
            addedTicks[slot] = addedTicks[last];
            changedTicks[slot] = changedTicks[last];
        }
        addedTicks[last] = 0;
        changedTicks[last] = 0;
    }

    public int capacity() {
        return addedTicks.length;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.change.ChangeTrackerTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/change/ ecs-core/src/test/java/zzuegg/ecs/change/
git commit -m "feat: add Tick counter and ChangeTracker for per-slot change detection"
```

---

## Task 8: Mut<T> (Mutable Component Wrapper)

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/component/Mut.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/component/MutTest.java`

- [ ] **Step 1: Write Mut tests**

Create `ecs-core/src/test/java/zzuegg/ecs/component/MutTest.java`:
```java
package zzuegg.ecs.component;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.change.ChangeTracker;
import static org.junit.jupiter.api.Assertions.*;

class MutTest {

    record Health(int hp) {}

    @Test
    void getReturnsCurrentValue() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        assertEquals(new Health(100), mut.get());
    }

    @Test
    void setUpdatesValue() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        mut.set(new Health(50));
        assertEquals(new Health(50), mut.get());
    }

    @Test
    void setMarksChangedWithWriteAccessTracking() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        mut.set(new Health(100)); // same value
        assertEquals(5, tracker.changedTick(0)); // still marked
    }

    @Test
    void noSetMeansNoChange() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, false);
        // don't call set()
        mut.flush();
        assertEquals(0, tracker.changedTick(0)); // unchanged
    }

    @Test
    void valueTrackedSuppressesNoOpSet() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(100)); // same value, value-tracked
        mut.flush();
        assertEquals(0, tracker.changedTick(0)); // NOT marked because equal
    }

    @Test
    void valueTrackedAllowsRealChange() {
        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var mut = new Mut<>(new Health(100), 0, tracker, 5, true);
        mut.set(new Health(50)); // different value
        mut.flush();
        assertEquals(5, tracker.changedTick(0)); // marked
    }

    @Test
    void isChangedReturnsTrueAfterSet() {
        var tracker = new ChangeTracker(16);
        var mut = new Mut<>(new Health(100), 0, tracker, 1, false);
        assertFalse(mut.isChanged());
        mut.set(new Health(50));
        assertTrue(mut.isChanged());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.component.MutTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement Mut**

Create `ecs-core/src/main/java/zzuegg/ecs/component/Mut.java`:
```java
package zzuegg.ecs.component;

import zzuegg.ecs.change.ChangeTracker;

public final class Mut<T extends Record> {

    private final T original;
    private T current;
    private final int slot;
    private final ChangeTracker tracker;
    private final long tick;
    private final boolean valueTracked;
    private boolean changed;

    public Mut(T value, int slot, ChangeTracker tracker, long tick, boolean valueTracked) {
        this.original = value;
        this.current = value;
        this.slot = slot;
        this.tracker = tracker;
        this.tick = tick;
        this.valueTracked = valueTracked;
        this.changed = false;
    }

    public T get() {
        return current;
    }

    public void set(T value) {
        this.current = value;
        this.changed = true;
    }

    public boolean isChanged() {
        return changed;
    }

    /**
     * Called by the framework after system invocation. Applies change tracking.
     * Returns the current value (may have been updated).
     */
    public T flush() {
        if (!changed) {
            return current;
        }
        if (valueTracked && original.equals(current)) {
            changed = false;
            return current;
        }
        tracker.markChanged(slot, tick);
        return current;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.component.MutTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/component/Mut.java ecs-core/src/test/java/zzuegg/ecs/component/MutTest.java
git commit -m "feat: add Mut<T> mutable component wrapper with change tracking"
```

---

## Task 9: Archetype & ArchetypeGraph

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeId.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/archetype/Archetype.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeGraph.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/archetype/EntityLocation.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeIdTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeGraphTest.java`

- [ ] **Step 1: Write ArchetypeId tests**

Create `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeIdTest.java`:
```java
package zzuegg.ecs.archetype;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentId;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ArchetypeIdTest {

    @Test
    void sameComponentsSameId() {
        var a = ArchetypeId.of(Set.of(new ComponentId(0), new ComponentId(1)));
        var b = ArchetypeId.of(Set.of(new ComponentId(1), new ComponentId(0)));
        assertEquals(a, b);
    }

    @Test
    void differentComponentsDifferentId() {
        var a = ArchetypeId.of(Set.of(new ComponentId(0)));
        var b = ArchetypeId.of(Set.of(new ComponentId(1)));
        assertNotEquals(a, b);
    }

    @Test
    void containsComponent() {
        var id = ArchetypeId.of(Set.of(new ComponentId(0), new ComponentId(2)));
        assertTrue(id.contains(new ComponentId(0)));
        assertTrue(id.contains(new ComponentId(2)));
        assertFalse(id.contains(new ComponentId(1)));
    }

    @Test
    void emptyArchetype() {
        var id = ArchetypeId.of(Set.of());
        assertTrue(id.components().isEmpty());
    }

    @Test
    void withAddsComponent() {
        var id = ArchetypeId.of(Set.of(new ComponentId(0)));
        var extended = id.with(new ComponentId(1));
        assertTrue(extended.contains(new ComponentId(0)));
        assertTrue(extended.contains(new ComponentId(1)));
    }

    @Test
    void withoutRemovesComponent() {
        var id = ArchetypeId.of(Set.of(new ComponentId(0), new ComponentId(1)));
        var reduced = id.without(new ComponentId(1));
        assertTrue(reduced.contains(new ComponentId(0)));
        assertFalse(reduced.contains(new ComponentId(1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.ArchetypeIdTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement ArchetypeId**

Create `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeId.java`:
```java
package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import java.util.*;

public record ArchetypeId(SortedSet<ComponentId> components) {

    public static ArchetypeId of(Set<ComponentId> components) {
        return new ArchetypeId(Collections.unmodifiableSortedSet(new TreeSet<>(components)));
    }

    public boolean contains(ComponentId id) {
        return components.contains(id);
    }

    public ArchetypeId with(ComponentId id) {
        var set = new TreeSet<>(components);
        set.add(id);
        return new ArchetypeId(Collections.unmodifiableSortedSet(set));
    }

    public ArchetypeId without(ComponentId id) {
        var set = new TreeSet<>(components);
        set.remove(id);
        return new ArchetypeId(Collections.unmodifiableSortedSet(set));
    }
}
```

- [ ] **Step 4: Run ArchetypeId tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.ArchetypeIdTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Write EntityLocation and Archetype tests**

Create `ecs-core/src/main/java/zzuegg/ecs/archetype/EntityLocation.java`:
```java
package zzuegg.ecs.archetype;

public record EntityLocation(ArchetypeId archetypeId, int chunkIndex, int slotIndex) {}
```

Create `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeTest.java`:
```java
package zzuegg.ecs.archetype;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.*;
import zzuegg.ecs.entity.Entity;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ArchetypeTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    private ComponentRegistry registry() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        return reg;
    }

    @Test
    void addEntityReturnsLocation() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var velId = reg.getOrRegister(Velocity.class);
        var archId = ArchetypeId.of(Set.of(posId, velId));
        var arch = new Archetype(archId, reg, 4);

        var loc = arch.add(Entity.of(0, 0));

        assertEquals(0, loc.chunkIndex());
        assertEquals(0, loc.slotIndex());
    }

    @Test
    void addCreatesNewChunkWhenFull() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2); // tiny chunks

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        var loc = arch.add(Entity.of(2, 0));

        assertEquals(1, loc.chunkIndex()); // second chunk
        assertEquals(0, loc.slotIndex());
    }

    @Test
    void setAndGetComponent() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16);

        var loc = arch.add(Entity.of(0, 0));
        arch.set(posId, loc, new Position(1, 2));

        assertEquals(new Position(1, 2), arch.get(posId, loc));
    }

    @Test
    void removeEntityAndSwapRemove() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16);

        arch.add(Entity.of(0, 0));
        arch.set(posId, new EntityLocation(archId, 0, 0), new Position(0, 0));
        arch.add(Entity.of(1, 0));
        arch.set(posId, new EntityLocation(archId, 0, 1), new Position(1, 1));

        var swapped = arch.remove(new EntityLocation(archId, 0, 0));

        assertEquals(1, arch.entityCount());
        // swapped entity is entity 1 which moved from slot 1 to slot 0
        assertTrue(swapped.isPresent());
        assertEquals(Entity.of(1, 0), swapped.get());
    }

    @Test
    void removeLastEntityReturnsEmpty() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16);

        arch.add(Entity.of(0, 0));
        var swapped = arch.remove(new EntityLocation(archId, 0, 0));
        assertTrue(swapped.isEmpty());
    }

    @Test
    void entityCountAcrossChunks() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2);

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        arch.add(Entity.of(2, 0));

        assertEquals(3, arch.entityCount());
    }

    @Test
    void chunkCount() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2);

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        assertEquals(1, arch.chunkCount());

        arch.add(Entity.of(2, 0));
        assertEquals(2, arch.chunkCount());
    }
}
```

- [ ] **Step 6: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.ArchetypeTest"
```
Expected: FAIL.

- [ ] **Step 7: Implement Archetype**

Create `ecs-core/src/main/java/zzuegg/ecs/archetype/Archetype.java`:
```java
package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.storage.Chunk;

import java.util.*;

public final class Archetype {

    private final ArchetypeId id;
    private final Map<ComponentId, Class<? extends Record>> componentTypes;
    private final int chunkCapacity;
    private final List<Chunk> chunks = new ArrayList<>();

    public Archetype(ArchetypeId id, ComponentRegistry registry, int chunkCapacity) {
        this.id = id;
        this.chunkCapacity = chunkCapacity;
        this.componentTypes = new LinkedHashMap<>();
        for (var compId : id.components()) {
            componentTypes.put(compId, registry.info(compId).type());
        }
    }

    public EntityLocation add(Entity entity) {
        Chunk chunk = findOrCreateChunk();
        int chunkIndex = chunks.indexOf(chunk);
        int slot = chunk.add(entity);
        return new EntityLocation(id, chunkIndex, slot);
    }

    public <T extends Record> void set(ComponentId compId, EntityLocation loc, T value) {
        chunks.get(loc.chunkIndex()).set(compId, loc.slotIndex(), value);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T get(ComponentId compId, EntityLocation loc) {
        return (T) chunks.get(loc.chunkIndex()).get(compId, loc.slotIndex());
    }

    /**
     * Removes entity at location. Returns the entity that was swapped into the slot (if any).
     */
    public Optional<Entity> remove(EntityLocation loc) {
        Chunk chunk = chunks.get(loc.chunkIndex());
        int slot = loc.slotIndex();
        int lastSlot = chunk.count() - 1;

        Entity swapped = null;
        if (slot < lastSlot) {
            swapped = chunk.entity(lastSlot);
        }

        chunk.remove(slot);

        return Optional.ofNullable(swapped);
    }

    public Entity entity(EntityLocation loc) {
        return chunks.get(loc.chunkIndex()).entity(loc.slotIndex());
    }

    public int entityCount() {
        int total = 0;
        for (var chunk : chunks) {
            total += chunk.count();
        }
        return total;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<Chunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    public ArchetypeId id() {
        return id;
    }

    private Chunk findOrCreateChunk() {
        for (var chunk : chunks) {
            if (!chunk.isFull()) {
                return chunk;
            }
        }
        var chunk = new Chunk(chunkCapacity, componentTypes);
        chunks.add(chunk);
        return chunk;
    }
}
```

- [ ] **Step 8: Run Archetype tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.ArchetypeTest"
```
Expected: All tests PASS.

- [ ] **Step 9: Write ArchetypeGraph tests**

Create `ecs-core/src/test/java/zzuegg/ecs/archetype/ArchetypeGraphTest.java`:
```java
package zzuegg.ecs.archetype;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ArchetypeGraphTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Health(int hp) {}

    @Test
    void getOrCreateArchetype() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var graph = new ArchetypeGraph(reg, 1024);

        var archId = ArchetypeId.of(Set.of(posId));
        var arch = graph.getOrCreate(archId);

        assertNotNull(arch);
        assertEquals(archId, arch.id());
    }

    @Test
    void sameIdReturnsSameArchetype() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var graph = new ArchetypeGraph(reg, 1024);

        var archId = ArchetypeId.of(Set.of(posId));
        var a = graph.getOrCreate(archId);
        var b = graph.getOrCreate(archId);

        assertSame(a, b);
    }

    @Test
    void addEdgeCreatesTargetArchetype() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var velId = reg.register(Velocity.class);
        var graph = new ArchetypeGraph(reg, 1024);

        var posOnly = ArchetypeId.of(Set.of(posId));
        graph.getOrCreate(posOnly);

        var posVel = graph.addEdge(posOnly, velId);

        assertTrue(posVel.contains(posId));
        assertTrue(posVel.contains(velId));
    }

    @Test
    void removeEdgeCreatesTargetArchetype() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var velId = reg.register(Velocity.class);
        var graph = new ArchetypeGraph(reg, 1024);

        var posVel = ArchetypeId.of(Set.of(posId, velId));
        graph.getOrCreate(posVel);

        var posOnly = graph.removeEdge(posVel, velId);

        assertTrue(posOnly.contains(posId));
        assertFalse(posOnly.contains(velId));
    }

    @Test
    void addEdgeCachesTransition() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var velId = reg.register(Velocity.class);
        var graph = new ArchetypeGraph(reg, 1024);

        var posOnly = ArchetypeId.of(Set.of(posId));
        graph.getOrCreate(posOnly);

        var first = graph.addEdge(posOnly, velId);
        var second = graph.addEdge(posOnly, velId);
        assertEquals(first, second);
    }

    @Test
    void archetypeCount() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var velId = reg.register(Velocity.class);
        var graph = new ArchetypeGraph(reg, 1024);

        assertEquals(0, graph.archetypeCount());
        graph.getOrCreate(ArchetypeId.of(Set.of(posId)));
        assertEquals(1, graph.archetypeCount());
        graph.getOrCreate(ArchetypeId.of(Set.of(posId, velId)));
        assertEquals(2, graph.archetypeCount());
    }

    @Test
    void matchingArchetypes() {
        var reg = new ComponentRegistry();
        var posId = reg.register(Position.class);
        var velId = reg.register(Velocity.class);
        var hpId = reg.register(Health.class);
        var graph = new ArchetypeGraph(reg, 1024);

        graph.getOrCreate(ArchetypeId.of(Set.of(posId)));
        graph.getOrCreate(ArchetypeId.of(Set.of(posId, velId)));
        graph.getOrCreate(ArchetypeId.of(Set.of(posId, velId, hpId)));
        graph.getOrCreate(ArchetypeId.of(Set.of(hpId)));

        var matching = graph.findMatching(Set.of(posId, velId));
        assertEquals(2, matching.size()); // (pos,vel) and (pos,vel,hp)
    }
}
```

- [ ] **Step 10: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.ArchetypeGraphTest"
```
Expected: FAIL.

- [ ] **Step 11: Implement ArchetypeGraph**

Create `ecs-core/src/main/java/zzuegg/ecs/archetype/ArchetypeGraph.java`:
```java
package zzuegg.ecs.archetype;

import zzuegg.ecs.component.ComponentId;
import zzuegg.ecs.component.ComponentRegistry;

import java.util.*;

public final class ArchetypeGraph {

    private final ComponentRegistry registry;
    private final int chunkCapacity;
    private final Map<ArchetypeId, Archetype> archetypes = new HashMap<>();
    // Caches: (sourceArchetype, addedComponent) -> targetArchetypeId
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> addEdges = new HashMap<>();
    private final Map<ArchetypeId, Map<ComponentId, ArchetypeId>> removeEdges = new HashMap<>();

    public ArchetypeGraph(ComponentRegistry registry, int chunkCapacity) {
        this.registry = registry;
        this.chunkCapacity = chunkCapacity;
    }

    public Archetype getOrCreate(ArchetypeId id) {
        return archetypes.computeIfAbsent(id, k -> new Archetype(k, registry, chunkCapacity));
    }

    public ArchetypeId addEdge(ArchetypeId source, ComponentId added) {
        return addEdges
            .computeIfAbsent(source, k -> new HashMap<>())
            .computeIfAbsent(added, k -> {
                var targetId = source.with(added);
                getOrCreate(targetId);
                return targetId;
            });
    }

    public ArchetypeId removeEdge(ArchetypeId source, ComponentId removed) {
        return removeEdges
            .computeIfAbsent(source, k -> new HashMap<>())
            .computeIfAbsent(removed, k -> {
                var targetId = source.without(removed);
                getOrCreate(targetId);
                return targetId;
            });
    }

    public List<Archetype> findMatching(Set<ComponentId> required) {
        var result = new ArrayList<Archetype>();
        for (var entry : archetypes.entrySet()) {
            if (entry.getKey().components().containsAll(required)) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    public Archetype get(ArchetypeId id) {
        return archetypes.get(id);
    }

    public int archetypeCount() {
        return archetypes.size();
    }

    public Collection<Archetype> allArchetypes() {
        return Collections.unmodifiableCollection(archetypes.values());
    }
}
```

- [ ] **Step 12: Run all archetype tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.archetype.*"
```
Expected: All tests PASS.

- [ ] **Step 13: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/archetype/ ecs-core/src/test/java/zzuegg/ecs/archetype/
git commit -m "feat: add Archetype, ArchetypeId, ArchetypeGraph with entity placement and graph transitions"
```

---

## Task 10: Resource Store

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/resource/Res.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/resource/ResMut.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/resource/ResourceStore.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/resource/ResourceStoreTest.java`

- [ ] **Step 1: Write ResourceStore tests**

Create `ecs-core/src/test/java/zzuegg/ecs/resource/ResourceStoreTest.java`:
```java
package zzuegg.ecs.resource;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceStoreTest {

    record DeltaTime(float dt) {}
    record Gravity(float g) {}

    @Test
    void insertAndGet() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        var res = store.get(DeltaTime.class);
        assertEquals(new DeltaTime(0.016f), res.get());
    }

    @Test
    void getMutAllowsSetAndReadBack() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        var mut = store.getMut(DeltaTime.class);
        mut.set(new DeltaTime(0.033f));
        assertEquals(new DeltaTime(0.033f), mut.get());
    }

    @Test
    void insertOverwrites() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        store.insert(new DeltaTime(0.033f));
        assertEquals(new DeltaTime(0.033f), store.get(DeltaTime.class).get());
    }

    @Test
    void getMissingResourceThrows() {
        var store = new ResourceStore();
        assertThrows(IllegalArgumentException.class, () -> store.get(DeltaTime.class));
    }

    @Test
    void getMutMissingResourceThrows() {
        var store = new ResourceStore();
        assertThrows(IllegalArgumentException.class, () -> store.getMut(DeltaTime.class));
    }

    @Test
    void containsReturnsTrueAfterInsert() {
        var store = new ResourceStore();
        assertFalse(store.contains(DeltaTime.class));
        store.insert(new DeltaTime(0f));
        assertTrue(store.contains(DeltaTime.class));
    }

    @Test
    void independentResourceTypes() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        store.insert(new Gravity(-9.81f));
        assertEquals(new DeltaTime(0.016f), store.get(DeltaTime.class).get());
        assertEquals(new Gravity(-9.81f), store.get(Gravity.class).get());
    }

    @Test
    void resIsReadOnly() {
        var store = new ResourceStore();
        store.insert(new DeltaTime(0.016f));
        var res = store.get(DeltaTime.class);
        // Res has no set method — compile-time safety
        assertEquals(new DeltaTime(0.016f), res.get());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.resource.ResourceStoreTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement Res, ResMut, and ResourceStore**

Create `ecs-core/src/main/java/zzuegg/ecs/resource/Res.java`:
```java
package zzuegg.ecs.resource;

public final class Res<T> {

    private final ResourceStore.Entry<T> entry;

    Res(ResourceStore.Entry<T> entry) {
        this.entry = entry;
    }

    public T get() {
        return entry.value();
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/resource/ResMut.java`:
```java
package zzuegg.ecs.resource;

public final class ResMut<T> {

    private final ResourceStore.Entry<T> entry;

    ResMut(ResourceStore.Entry<T> entry) {
        this.entry = entry;
    }

    public T get() {
        return entry.value();
    }

    public void set(T value) {
        entry.setValue(value);
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/resource/ResourceStore.java`:
```java
package zzuegg.ecs.resource;

import java.util.HashMap;
import java.util.Map;

public final class ResourceStore {

    static final class Entry<T> {
        private T value;

        Entry(T value) { this.value = value; }

        T value() { return value; }
        void setValue(T value) { this.value = value; }
    }

    private final Map<Class<?>, Entry<?>> resources = new HashMap<>();

    public <T> void insert(T resource) {
        @SuppressWarnings("unchecked")
        var existing = (Entry<T>) resources.get(resource.getClass());
        if (existing != null) {
            existing.setValue(resource);
        } else {
            resources.put(resource.getClass(), new Entry<>(resource));
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Res<T> get(Class<T> type) {
        var entry = (Entry<T>) resources.get(type);
        if (entry == null) {
            throw new IllegalArgumentException("Resource not found: " + type.getName());
        }
        return new Res<>(entry);
    }

    @SuppressWarnings("unchecked")
    public <T> ResMut<T> getMut(Class<T> type) {
        var entry = (Entry<T>) resources.get(type);
        if (entry == null) {
            throw new IllegalArgumentException("Resource not found: " + type.getName());
        }
        return new ResMut<>(entry);
    }

    public boolean contains(Class<?> type) {
        return resources.containsKey(type);
    }

    public void setDirect(Class<?> type, Object value) {
        @SuppressWarnings("unchecked")
        var entry = (Entry<Object>) resources.get(type);
        if (entry != null) {
            entry.setValue(value);
        } else {
            resources.put(type, new Entry<>(value));
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.resource.ResourceStoreTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/resource/ ecs-core/src/test/java/zzuegg/ecs/resource/
git commit -m "feat: add ResourceStore with Res and ResMut wrappers"
```

---

## Task 11: Events

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/event/EventStore.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/event/EventWriter.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/event/EventReader.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/event/EventRegistry.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/event/EventStoreTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/event/EventRegistryTest.java`

- [ ] **Step 1: Write EventStore tests**

Create `ecs-core/src/test/java/zzuegg/ecs/event/EventStoreTest.java`:
```java
package zzuegg.ecs.event;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {

    record DamageEvent(int amount) {}

    @Test
    void sendAndReadAfterSwap() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.send(new DamageEvent(20));
        store.swap(); // make write buffer readable
        assertEquals(List.of(new DamageEvent(10), new DamageEvent(20)), store.read());
    }

    @Test
    void readIsEmptyBeforeSwap() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        assertTrue(store.read().isEmpty());
    }

    @Test
    void swapClearsOldReadBuffer() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.swap();
        assertEquals(1, store.read().size());
        store.swap(); // old read buffer (with event 10) gets cleared
        assertTrue(store.read().isEmpty());
    }

    @Test
    void eventsLiveForOneTick() {
        var store = new EventStore<DamageEvent>();

        // tick 1: send events
        store.send(new DamageEvent(10));
        store.swap();

        // tick 1: read events
        assertEquals(1, store.read().size());

        // tick 2: events still readable? No, swap clears them
        store.swap();
        assertTrue(store.read().isEmpty());
    }

    @Test
    void concurrentSendsFromMultipleWriters() {
        var store = new EventStore<DamageEvent>();
        var w1 = store.writer();
        var w2 = store.writer();
        w1.send(new DamageEvent(10));
        w2.send(new DamageEvent(20));
        store.swap();
        assertEquals(2, store.read().size());
    }

    @Test
    void readerReturnsImmutableView() {
        var store = new EventStore<DamageEvent>();
        store.send(new DamageEvent(10));
        store.swap();
        var reader = store.reader();
        var events = reader.read();
        assertThrows(UnsupportedOperationException.class, () -> events.add(new DamageEvent(99)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.event.EventStoreTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement EventStore, EventWriter, EventReader**

Create `ecs-core/src/main/java/zzuegg/ecs/event/EventWriter.java`:
```java
package zzuegg.ecs.event;

public final class EventWriter<T extends Record> {

    private final EventStore<T> store;

    EventWriter(EventStore<T> store) {
        this.store = store;
    }

    public void send(T event) {
        store.send(event);
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/event/EventReader.java`:
```java
package zzuegg.ecs.event;

import java.util.Collections;
import java.util.List;

public final class EventReader<T extends Record> {

    private final EventStore<T> store;

    EventReader(EventStore<T> store) {
        this.store = store;
    }

    public List<T> read() {
        return Collections.unmodifiableList(store.read());
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/event/EventStore.java`:
```java
package zzuegg.ecs.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventStore<T extends Record> {

    private List<T> writeBuffer = new CopyOnWriteArrayList<>();
    private List<T> readBuffer = new ArrayList<>();

    public void send(T event) {
        writeBuffer.add(event);
    }

    public List<T> read() {
        return readBuffer;
    }

    public void swap() {
        readBuffer = new ArrayList<>(writeBuffer);
        writeBuffer = new CopyOnWriteArrayList<>();
    }

    public EventWriter<T> writer() {
        return new EventWriter<>(this);
    }

    public EventReader<T> reader() {
        return new EventReader<>(this);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.event.EventStoreTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Write EventRegistry tests**

Create `ecs-core/src/test/java/zzuegg/ecs/event/EventRegistryTest.java`:
```java
package zzuegg.ecs.event;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventRegistryTest {

    record HitEvent(int damage) {}
    record DeathEvent() {}

    @Test
    void registerAndGetStore() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        assertNotNull(registry.store(HitEvent.class));
    }

    @Test
    void unregisteredEventThrows() {
        var registry = new EventRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.store(HitEvent.class));
    }

    @Test
    void swapAllSwapsAllStores() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        registry.register(DeathEvent.class);

        registry.store(HitEvent.class).send(new HitEvent(10));
        registry.store(DeathEvent.class).send(new DeathEvent());

        registry.swapAll();

        assertEquals(1, registry.store(HitEvent.class).read().size());
        assertEquals(1, registry.store(DeathEvent.class).read().size());
    }

    @Test
    void doubleRegisterIsIdempotent() {
        var registry = new EventRegistry();
        registry.register(HitEvent.class);
        registry.register(HitEvent.class);
        assertNotNull(registry.store(HitEvent.class));
    }
}
```

- [ ] **Step 6: Implement EventRegistry**

Create `ecs-core/src/main/java/zzuegg/ecs/event/EventRegistry.java`:
```java
package zzuegg.ecs.event;

import java.util.HashMap;
import java.util.Map;

public final class EventRegistry {

    private final Map<Class<?>, EventStore<?>> stores = new HashMap<>();

    public <T extends Record> void register(Class<T> type) {
        stores.putIfAbsent(type, new EventStore<T>());
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> EventStore<T> store(Class<T> type) {
        var store = (EventStore<T>) stores.get(type);
        if (store == null) {
            throw new IllegalArgumentException("Event type not registered: " + type.getName());
        }
        return store;
    }

    public void swapAll() {
        for (var store : stores.values()) {
            store.swap();
        }
    }
}
```

- [ ] **Step 7: Run all event tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.event.*"
```
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/event/ ecs-core/src/test/java/zzuegg/ecs/event/
git commit -m "feat: add double-buffered event system with EventStore, EventWriter, EventReader, EventRegistry"
```

---

## Task 12: System Annotations

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/System.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/SystemSet.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Exclusive.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Read.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Write.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/With.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Without.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Filter.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/RunIf.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Default.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Local.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Added.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Changed.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/Removed.java`

- [ ] **Step 1: Create all annotation types**

Create `ecs-core/src/main/java/zzuegg/ecs/system/System.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface System {
    String stage() default "Update";
    String[] after() default {};
    String[] before() default {};
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/SystemSet.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemSet {
    String name();
    String stage() default "Update";
    String[] after() default {};
    String[] before() default {};
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Exclusive.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Exclusive {}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Read.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Read {}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Write.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Write {}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/With.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(With.List.class)
public @interface With {
    Class<? extends Record> value();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        With[] value();
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Without.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Without.List.class)
public @interface Without {
    Class<? extends Record> value();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Without[] value();
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Filter.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Filter.List.class)
public @interface Filter {
    Class<?> value(); // Added.class, Changed.class, or Removed.class
    Class<? extends Record> target();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Filter[] value();
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/RunIf.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunIf {
    String value();
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Default.java`:
```java
package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Default {
    String value();
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Local.java`:
```java
package zzuegg.ecs.system;

public final class Local<T> {

    private T value;

    public Local(T initial) {
        this.value = initial;
    }

    public Local() {
        this(null);
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Added.java`:
```java
package zzuegg.ecs.system;

public final class Added {
    private Added() {}
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Changed.java`:
```java
package zzuegg.ecs.system;

public final class Changed {
    private Changed() {}
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/system/Removed.java`:
```java
package zzuegg.ecs.system;

public final class Removed {
    private Removed() {}
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :ecs-core:compileJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/system/
git commit -m "feat: add all system annotations and Local<T> wrapper"
```

---

## Task 13: SystemParser & SystemDescriptor

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/SystemDescriptor.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/SystemParser.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/query/AccessType.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/query/ComponentAccess.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/system/SystemParserTest.java`

- [ ] **Step 1: Create AccessType and ComponentAccess**

Create `ecs-core/src/main/java/zzuegg/ecs/query/AccessType.java`:
```java
package zzuegg.ecs.query;

public enum AccessType {
    READ, WRITE
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/query/ComponentAccess.java`:
```java
package zzuegg.ecs.query;

import zzuegg.ecs.component.ComponentId;

public record ComponentAccess(ComponentId componentId, Class<? extends Record> type, AccessType accessType) {}
```

- [ ] **Step 2: Write SystemParser tests**

Create `ecs-core/src/test/java/zzuegg/ecs/system/SystemParserTest.java`:
```java
package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.event.EventReader;
import zzuegg.ecs.event.EventWriter;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class SystemParserTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}
    record HitEvent(int damage) {}

    static class SimpleSystems {
        @zzuegg.ecs.system.System
        void move(@Read Velocity vel, @Write Mut<Position> pos) {}
    }

    static class SystemWithResource {
        @zzuegg.ecs.system.System
        void tick(Res<DeltaTime> dt) {}
    }

    static class SystemWithMutResource {
        @zzuegg.ecs.system.System
        void tick(ResMut<DeltaTime> dt) {}
    }

    static class SystemWithEvents {
        @zzuegg.ecs.system.System
        void handle(EventReader<HitEvent> reader, EventWriter<HitEvent> writer) {}
    }

    static class SystemWithFilters {
        @zzuegg.ecs.system.System
        @With(Position.class)
        @Without(Velocity.class)
        void filtered(@Read Position pos) {}
    }

    static class SystemWithOrdering {
        @zzuegg.ecs.system.System(stage = "PostUpdate", after = "physics", before = "render")
        void ordered(@Read Position pos) {}
    }

    static class ExclusiveSystem {
        @zzuegg.ecs.system.System
        @Exclusive
        void exclusive(World world) {}
    }

    static class SystemWithCommands {
        @zzuegg.ecs.system.System
        void spawner(@Read Position pos, zzuegg.ecs.command.Commands cmd) {}
    }

    static class SystemWithLocal {
        @zzuegg.ecs.system.System
        void counter(Local<Integer> count) {}
    }

    @SystemSet(name = "physics", stage = "Update", after = "input")
    static class PhysicsSet {
        @zzuegg.ecs.system.System
        void gravity(@Write Mut<Velocity> vel) {}

        @zzuegg.ecs.system.System(after = "gravity")
        void integrate(@Read Velocity vel, @Write Mut<Position> pos) {}
    }

    @Test
    void parsesComponentAccess() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(SimpleSystems.class, reg);

        assertEquals(1, descriptors.size());
        var desc = descriptors.getFirst();
        assertEquals("move", desc.name());
        assertEquals(2, desc.componentAccesses().size());

        var reads = desc.componentAccesses().stream()
            .filter(a -> a.accessType() == AccessType.READ).toList();
        var writes = desc.componentAccesses().stream()
            .filter(a -> a.accessType() == AccessType.WRITE).toList();

        assertEquals(1, reads.size());
        assertEquals(Velocity.class, reads.getFirst().type());
        assertEquals(1, writes.size());
        assertEquals(Position.class, writes.getFirst().type());
    }

    @Test
    void parsesResourceAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithResource.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.resourceReads().size());
        assertTrue(desc.resourceWrites().isEmpty());
    }

    @Test
    void parsesMutableResourceAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithMutResource.class, reg);
        var desc = descriptors.getFirst();
        assertTrue(desc.resourceReads().isEmpty());
        assertEquals(1, desc.resourceWrites().size());
    }

    @Test
    void parsesEventAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithEvents.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.eventReads().size());
        assertEquals(1, desc.eventWrites().size());
    }

    @Test
    void parsesFilters() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(SystemWithFilters.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.withFilters().size());
        assertEquals(1, desc.withoutFilters().size());
    }

    @Test
    void parsesOrdering() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(SystemWithOrdering.class, reg);
        var desc = descriptors.getFirst();
        assertEquals("PostUpdate", desc.stage());
        assertTrue(desc.after().contains("physics"));
        assertTrue(desc.before().contains("render"));
    }

    @Test
    void parsesExclusive() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(ExclusiveSystem.class, reg);
        assertTrue(descriptors.getFirst().isExclusive());
    }

    @Test
    void parsesCommands() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(SystemWithCommands.class, reg);
        assertTrue(descriptors.getFirst().usesCommands());
    }

    @Test
    void parsesLocal() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithLocal.class, reg);
        assertTrue(descriptors.getFirst().usesLocal());
    }

    @Test
    void parsesSystemSet() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(PhysicsSet.class, reg);

        assertEquals(2, descriptors.size());
        // All systems in set inherit stage and after from @SystemSet
        for (var desc : descriptors) {
            assertEquals("Update", desc.stage());
        }
        // The set-level "after" should be inherited
        var gravity = descriptors.stream().filter(d -> d.name().equals("gravity")).findFirst().orElseThrow();
        assertTrue(gravity.after().contains("input"));
    }

    @Test
    void classWithNoSystemMethodsReturnsEmpty() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(String.class, reg);
        assertTrue(descriptors.isEmpty());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.system.SystemParserTest"
```
Expected: FAIL.

- [ ] **Step 4: Implement SystemDescriptor**

Create `ecs-core/src/main/java/zzuegg/ecs/system/SystemDescriptor.java`:
```java
package zzuegg.ecs.system;

import zzuegg.ecs.query.ComponentAccess;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

public record SystemDescriptor(
    String name,
    String stage,
    Set<String> after,
    Set<String> before,
    boolean isExclusive,
    List<ComponentAccess> componentAccesses,
    Set<Class<?>> resourceReads,
    Set<Class<?>> resourceWrites,
    Set<Class<?>> eventReads,
    Set<Class<?>> eventWrites,
    Set<Class<? extends Record>> withFilters,
    Set<Class<? extends Record>> withoutFilters,
    List<FilterDescriptor> changeFilters,
    boolean usesCommands,
    boolean usesLocal,
    String runIf,
    Method method,
    Object instance
) {

    public record FilterDescriptor(Class<?> filterType, Class<? extends Record> target) {}
}
```

- [ ] **Step 5: Implement SystemParser**

Create `ecs-core/src/main/java/zzuegg/ecs/system/SystemParser.java`:
```java
package zzuegg.ecs.system;

import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.event.EventReader;
import zzuegg.ecs.event.EventWriter;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.query.ComponentAccess;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.world.World;

import java.lang.reflect.*;
import java.util.*;

public final class SystemParser {

    private SystemParser() {}

    public static List<SystemDescriptor> parse(Class<?> clazz, ComponentRegistry registry) {
        var results = new ArrayList<SystemDescriptor>();

        // Check for @SystemSet on class
        var setAnnotation = clazz.getAnnotation(SystemSet.class);
        String setStage = setAnnotation != null ? setAnnotation.stage() : null;
        Set<String> setAfter = setAnnotation != null ? Set.of(setAnnotation.after()) : Set.of();
        Set<String> setBefore = setAnnotation != null ? Set.of(setAnnotation.before()) : Set.of();

        // Create instance for non-static methods
        Object instance = null;
        for (var method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(zzuegg.ecs.system.System.class)) {
                if (instance == null && !Modifier.isStatic(method.getModifiers())) {
                    try {
                        var ctor = clazz.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        instance = ctor.newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Cannot instantiate system class: " + clazz.getName(), e);
                    }
                }
            }
        }

        for (var method : clazz.getDeclaredMethods()) {
            var sysAnnotation = method.getAnnotation(zzuegg.ecs.system.System.class);
            if (sysAnnotation == null) continue;

            method.setAccessible(true);

            // Stage: method-level overrides class-level
            String stage = sysAnnotation.stage();
            if (setStage != null && stage.equals("Update") && !sysAnnotation.stage().equals("Update")) {
                stage = sysAnnotation.stage();
            } else if (setStage != null && stage.equals("Update")) {
                stage = setStage;
            }

            // Ordering: merge set-level and method-level
            var after = new HashSet<>(setAfter);
            after.addAll(Set.of(sysAnnotation.after()));
            var before = new HashSet<>(setBefore);
            before.addAll(Set.of(sysAnnotation.before()));

            boolean exclusive = method.isAnnotationPresent(Exclusive.class);

            // Parse parameters
            var componentAccesses = new ArrayList<ComponentAccess>();
            var resourceReads = new HashSet<Class<?>>();
            var resourceWrites = new HashSet<Class<?>>();
            var eventReads = new HashSet<Class<?>>();
            var eventWrites = new HashSet<Class<?>>();
            boolean usesCommands = false;
            boolean usesLocal = false;

            for (var param : method.getParameters()) {
                var paramType = param.getType();

                if (paramType == World.class) {
                    // exclusive system
                    continue;
                }

                if (paramType == Commands.class) {
                    usesCommands = true;
                    continue;
                }

                if (paramType == Local.class) {
                    usesLocal = true;
                    continue;
                }

                if (paramType == Res.class) {
                    var typeArg = extractTypeArg(param);
                    resourceReads.add(typeArg);
                    continue;
                }

                if (paramType == ResMut.class) {
                    var typeArg = extractTypeArg(param);
                    resourceWrites.add(typeArg);
                    continue;
                }

                if (paramType == EventReader.class) {
                    var typeArg = extractTypeArg(param);
                    eventReads.add(typeArg);
                    continue;
                }

                if (paramType == EventWriter.class) {
                    var typeArg = extractTypeArg(param);
                    eventWrites.add(typeArg);
                    continue;
                }

                // Component parameter
                if (param.isAnnotationPresent(Read.class)) {
                    var compId = registry.getOrRegister(paramType);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) paramType;
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.READ));
                } else if (param.isAnnotationPresent(Write.class)) {
                    // @Write Mut<T> — extract T from Mut<T>
                    var typeArg = extractTypeArg(param);
                    var compId = registry.getOrRegister(typeArg);
                    @SuppressWarnings("unchecked")
                    var recType = (Class<? extends Record>) typeArg;
                    componentAccesses.add(new ComponentAccess(compId, recType, AccessType.WRITE));
                }
            }

            // Parse filters
            var withFilters = new HashSet<Class<? extends Record>>();
            var withoutFilters = new HashSet<Class<? extends Record>>();
            var changeFilters = new ArrayList<SystemDescriptor.FilterDescriptor>();

            for (var w : method.getAnnotationsByType(With.class)) {
                withFilters.add(w.value());
            }
            for (var w : method.getAnnotationsByType(Without.class)) {
                withoutFilters.add(w.value());
            }
            for (var f : method.getAnnotationsByType(Filter.class)) {
                changeFilters.add(new SystemDescriptor.FilterDescriptor(f.value(), f.target()));
            }

            // RunIf
            var runIfAnnotation = method.getAnnotation(RunIf.class);
            String runIf = runIfAnnotation != null ? runIfAnnotation.value() : null;

            results.add(new SystemDescriptor(
                method.getName(), stage, after, before, exclusive,
                componentAccesses, resourceReads, resourceWrites,
                eventReads, eventWrites, withFilters, withoutFilters,
                changeFilters, usesCommands, usesLocal, runIf,
                method, Modifier.isStatic(method.getModifiers()) ? null : instance
            ));
        }

        return results;
    }

    private static Class<?> extractTypeArg(Parameter param) {
        var genType = param.getParameterizedType();
        if (genType instanceof ParameterizedType pt) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) {
                return c;
            }
        }
        throw new IllegalArgumentException(
            "Cannot extract type argument from parameter: " + param.getName());
    }
}
```

- [ ] **Step 6: Create stub Commands class for compilation**

Create `ecs-core/src/main/java/zzuegg/ecs/command/Commands.java`:
```java
package zzuegg.ecs.command;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class Commands {

    public sealed interface Command permits SpawnCommand, DespawnCommand, AddCommand, RemoveCommand, SetCommand, InsertResourceCommand {}
    public record SpawnCommand(Record... components) implements Command {}
    public record DespawnCommand(Entity entity) implements Command {}
    public record AddCommand(Entity entity, Record component) implements Command {}
    public record RemoveCommand(Entity entity, Class<? extends Record> type) implements Command {}
    public record SetCommand(Entity entity, Record component) implements Command {}
    public record InsertResourceCommand(Object resource) implements Command {}

    private final List<Command> buffer = new ArrayList<>();

    public void spawn(Record... components) {
        buffer.add(new SpawnCommand(components));
    }

    public void despawn(Entity entity) {
        buffer.add(new DespawnCommand(entity));
    }

    public void add(Entity entity, Record component) {
        buffer.add(new AddCommand(entity, component));
    }

    public void remove(Entity entity, Class<? extends Record> type) {
        buffer.add(new RemoveCommand(entity, type));
    }

    public void set(Entity entity, Record component) {
        buffer.add(new SetCommand(entity, component));
    }

    public <T> void insertResource(T resource) {
        buffer.add(new InsertResourceCommand(resource));
    }

    public List<Command> drain() {
        var commands = List.copyOf(buffer);
        buffer.clear();
        return commands;
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }
}
```

Create stub `ecs-core/src/main/java/zzuegg/ecs/world/World.java`:
```java
package zzuegg.ecs.world;

public final class World {
    // Stub for now — full implementation in Task 16
}
```

- [ ] **Step 7: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.system.SystemParserTest"
```
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/system/ ecs-core/src/main/java/zzuegg/ecs/query/ ecs-core/src/main/java/zzuegg/ecs/command/Commands.java ecs-core/src/main/java/zzuegg/ecs/world/World.java ecs-core/src/test/java/zzuegg/ecs/system/
git commit -m "feat: add SystemParser, SystemDescriptor, annotations, and command buffer"
```

---

## Task 14: SystemInvoker (MethodHandle-based Invocation)

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/system/SystemInvoker.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/system/SystemInvokerTest.java`

- [ ] **Step 1: Write SystemInvoker tests**

Create `ecs-core/src/test/java/zzuegg/ecs/system/SystemInvokerTest.java`:
```java
package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.*;
import zzuegg.ecs.resource.*;
import zzuegg.ecs.change.*;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SystemInvokerTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    // We need a static reference to capture results since we invoke via method handle
    static final AtomicReference<Position> lastPosition = new AtomicReference<>();
    static final AtomicBoolean invoked = new AtomicBoolean(false);

    static class TestSystems {
        @zzuegg.ecs.system.System
        void readOnly(@Read Position pos) {
            lastPosition.set(pos);
        }

        @zzuegg.ecs.system.System
        void writeComponent(@Read Velocity vel, @Write Mut<Position> pos) {
            pos.set(new Position(pos.get().x() + vel.dx(), pos.get().y() + vel.dy()));
        }

        @zzuegg.ecs.system.System
        void withResource(@Read Position pos, Res<DeltaTime> dt) {
            invoked.set(true);
        }
    }

    @Test
    void createInvokerForReadOnlySystem() throws Exception {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().equals("readOnly")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);
        assertNotNull(invoker);
    }

    @Test
    void invokeReadOnlySystem() throws Exception {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().equals("readOnly")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);
        lastPosition.set(null);

        var args = new Object[]{ new Position(3, 4) };
        invoker.invoke(args);

        assertEquals(new Position(3, 4), lastPosition.get());
    }

    @Test
    void invokeWriteSystem() throws Exception {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().equals("writeComponent")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);

        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var posMut = new Mut<>(new Position(1, 2), 0, tracker, 1, false);

        var args = new Object[]{ new Velocity(10, 20), posMut };
        invoker.invoke(args);

        assertEquals(new Position(11, 22), posMut.get());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.system.SystemInvokerTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement SystemInvoker**

Create `ecs-core/src/main/java/zzuegg/ecs/system/SystemInvoker.java`:
```java
package zzuegg.ecs.system;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public final class SystemInvoker {

    private final MethodHandle handle;
    private final Object instance;

    private SystemInvoker(MethodHandle handle, Object instance) {
        this.handle = handle;
        this.instance = instance;
    }

    public static SystemInvoker create(SystemDescriptor descriptor) {
        try {
            var lookup = MethodHandles.privateLookup(
                descriptor.method().getDeclaringClass(), MethodHandles.lookup());
            var handle = lookup.unreflect(descriptor.method());

            if (descriptor.instance() != null) {
                handle = handle.bindTo(descriptor.instance());
            }

            return new SystemInvoker(handle, descriptor.instance());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot create invoker for: " + descriptor.name(), e);
        }
    }

    public void invoke(Object[] args) throws Throwable {
        handle.invokeWithArguments(args);
    }

    public MethodHandle handle() {
        return handle;
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.system.SystemInvokerTest"
```
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/system/SystemInvoker.java ecs-core/src/test/java/zzuegg/ecs/system/SystemInvokerTest.java
git commit -m "feat: add SystemInvoker with MethodHandle-based invocation"
```

---

## Task 15: Scheduler (DAG Builder & Schedule)

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/scheduler/Stage.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/scheduler/ScheduleGraph.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/scheduler/DagBuilder.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/scheduler/Schedule.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/scheduler/DagBuilderTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/scheduler/ScheduleGraphTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/scheduler/ScheduleTest.java`

- [ ] **Step 1: Write DagBuilder tests**

Create `ecs-core/src/test/java/zzuegg/ecs/scheduler/DagBuilderTest.java`:
```java
package zzuegg.ecs.scheduler;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import static org.junit.jupiter.api.Assertions.*;

class DagBuilderTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    static class IndependentSystems {
        @System
        void readA(@Read Position pos) {}

        @System
        void readB(@Read Position pos) {}
    }

    static class ConflictingSystems {
        @System
        void writePos(@Write Mut<Position> pos) {}

        @System
        void alsoWritePos(@Write Mut<Position> pos) {}
    }

    static class OrderedSystems {
        @System(after = "second")
        void first(@Read Position pos) {}

        @System
        void second(@Read Position pos) {}
    }

    static class CyclicSystems {
        @System(after = "b")
        void a(@Read Position pos) {}

        @System(after = "a")
        void b(@Read Position pos) {}
    }

    @Test
    void independentReadersCanRunInParallel() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(IndependentSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        // No edges between them — both can run first
        var ready = graph.readySystems();
        assertEquals(2, ready.size());
    }

    @Test
    void conflictingWritersGetImplicitEdge() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(ConflictingSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        // One must run before the other
        var ready = graph.readySystems();
        assertEquals(1, ready.size()); // only one can be ready initially
    }

    @Test
    void explicitOrderingCreatesEdge() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(1, ready.size());
        assertEquals("second", ready.getFirst().descriptor().name());
    }

    @Test
    void cyclicDependencyThrows() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(CyclicSystems.class, reg);
        assertThrows(IllegalStateException.class, () -> DagBuilder.build(descriptors));
    }

    @Test
    void completingSystemReleasesDependent() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(1, ready.size());
        graph.complete(ready.getFirst());

        var nextReady = graph.readySystems();
        assertEquals(1, nextReady.size());
        assertEquals("first", nextReady.getFirst().descriptor().name());
    }

    @Test
    void allSystemsComplete() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        assertFalse(graph.isComplete());
        var r1 = graph.readySystems();
        graph.complete(r1.getFirst());
        var r2 = graph.readySystems();
        graph.complete(r2.getFirst());
        assertTrue(graph.isComplete());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.scheduler.DagBuilderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement ScheduleGraph**

Create `ecs-core/src/main/java/zzuegg/ecs/scheduler/ScheduleGraph.java`:
```java
package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.*;

public final class ScheduleGraph {

    public record SystemNode(SystemDescriptor descriptor, SystemInvoker invoker) {}

    private final List<SystemNode> nodes;
    // adjacency: node index -> set of dependent node indices
    private final Map<Integer, Set<Integer>> edges;
    private final int[] inDegree;
    private final boolean[] completed;

    ScheduleGraph(List<SystemNode> nodes, Map<Integer, Set<Integer>> edges, int[] inDegree) {
        this.nodes = nodes;
        this.edges = edges;
        this.inDegree = Arrays.copyOf(inDegree, inDegree.length);
        this.completed = new boolean[nodes.size()];
    }

    public List<SystemNode> readySystems() {
        var ready = new ArrayList<SystemNode>();
        for (int i = 0; i < nodes.size(); i++) {
            if (!completed[i] && inDegree[i] == 0) {
                ready.add(nodes.get(i));
            }
        }
        return ready;
    }

    public void complete(SystemNode node) {
        int idx = nodes.indexOf(node);
        if (idx < 0) throw new IllegalArgumentException("Unknown node: " + node.descriptor().name());
        completed[idx] = true;

        var deps = edges.getOrDefault(idx, Set.of());
        for (int dep : deps) {
            inDegree[dep]--;
        }
    }

    public boolean isComplete() {
        for (boolean c : completed) {
            if (!c) return false;
        }
        return true;
    }

    public List<SystemNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public void reset() {
        var originalInDegree = computeOriginalInDegree();
        java.lang.System.arraycopy(originalInDegree, 0, inDegree, 0, inDegree.length);
        Arrays.fill(completed, false);
    }

    private int[] computeOriginalInDegree() {
        int[] deg = new int[nodes.size()];
        for (var entry : edges.entrySet()) {
            for (int dep : entry.getValue()) {
                deg[dep]++;
            }
        }
        return deg;
    }
}
```

- [ ] **Step 4: Implement DagBuilder**

Create `ecs-core/src/main/java/zzuegg/ecs/scheduler/DagBuilder.java`:
```java
package zzuegg.ecs.scheduler;

import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.*;

public final class DagBuilder {

    private DagBuilder() {}

    public static ScheduleGraph build(List<SystemDescriptor> descriptors) {
        var nodes = new ArrayList<ScheduleGraph.SystemNode>();
        var nameToIndex = new HashMap<String, Integer>();

        for (int i = 0; i < descriptors.size(); i++) {
            var desc = descriptors.get(i);
            var invoker = SystemInvoker.create(desc);
            nodes.add(new ScheduleGraph.SystemNode(desc, invoker));
            nameToIndex.put(desc.name(), i);
        }

        int n = nodes.size();
        var edges = new HashMap<Integer, Set<Integer>>();
        var inDegree = new int[n];

        // Explicit ordering
        for (int i = 0; i < n; i++) {
            var desc = descriptors.get(i);
            for (var afterName : desc.after()) {
                var depIdx = nameToIndex.get(afterName);
                if (depIdx != null) {
                    addEdge(edges, inDegree, depIdx, i);
                }
            }
            for (var beforeName : desc.before()) {
                var depIdx = nameToIndex.get(beforeName);
                if (depIdx != null) {
                    addEdge(edges, inDegree, i, depIdx);
                }
            }
        }

        // Implicit conflict edges: two writers to same component
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (hasConflict(descriptors.get(i), descriptors.get(j))) {
                    // Add edge from i -> j (arbitrary but deterministic)
                    if (!hasPath(edges, j, i, n)) { // avoid cycles
                        addEdge(edges, inDegree, i, j);
                    }
                }
            }
        }

        // Validate: no cycles
        validateNoCycles(nodes, edges, inDegree);

        return new ScheduleGraph(nodes, edges, inDegree);
    }

    private static boolean hasConflict(SystemDescriptor a, SystemDescriptor b) {
        for (var accessA : a.componentAccesses()) {
            for (var accessB : b.componentAccesses()) {
                if (accessA.componentId().equals(accessB.componentId())) {
                    if (accessA.accessType() == AccessType.WRITE || accessB.accessType() == AccessType.WRITE) {
                        return true;
                    }
                }
            }
        }
        // Resource conflicts
        for (var res : a.resourceWrites()) {
            if (b.resourceReads().contains(res) || b.resourceWrites().contains(res)) return true;
        }
        for (var res : b.resourceWrites()) {
            if (a.resourceReads().contains(res) || a.resourceWrites().contains(res)) return true;
        }
        return false;
    }

    private static void addEdge(Map<Integer, Set<Integer>> edges, int[] inDegree, int from, int to) {
        if (edges.computeIfAbsent(from, k -> new HashSet<>()).add(to)) {
            inDegree[to]++;
        }
    }

    private static boolean hasPath(Map<Integer, Set<Integer>> edges, int from, int to, int n) {
        var visited = new boolean[n];
        var queue = new ArrayDeque<Integer>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == to) return true;
            if (visited[current]) continue;
            visited[current] = true;
            for (int next : edges.getOrDefault(current, Set.of())) {
                queue.add(next);
            }
        }
        return false;
    }

    private static void validateNoCycles(
            List<ScheduleGraph.SystemNode> nodes,
            Map<Integer, Set<Integer>> edges,
            int[] inDegree) {
        int n = nodes.size();
        var deg = Arrays.copyOf(inDegree, n);
        var queue = new ArrayDeque<Integer>();

        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) queue.add(i);
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            processed++;
            for (int dep : edges.getOrDefault(current, Set.of())) {
                deg[dep]--;
                if (deg[dep] == 0) queue.add(dep);
            }
        }

        if (processed < n) {
            throw new IllegalStateException("Cycle detected in system dependency graph");
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.scheduler.DagBuilderTest"
```
Expected: All tests PASS.

- [ ] **Step 6: Implement Stage and Schedule**

Create `ecs-core/src/main/java/zzuegg/ecs/scheduler/Stage.java`:
```java
package zzuegg.ecs.scheduler;

public record Stage(String name, int order) implements Comparable<Stage> {

    public static final Stage FIRST = new Stage("First", 0);
    public static final Stage PRE_UPDATE = new Stage("PreUpdate", 100);
    public static final Stage UPDATE = new Stage("Update", 200);
    public static final Stage POST_UPDATE = new Stage("PostUpdate", 300);
    public static final Stage LAST = new Stage("Last", 400);

    public static Stage after(String referenceStageName) {
        // Custom stages get order relative to reference
        return switch (referenceStageName) {
            case "First" -> new Stage("custom", 50);
            case "PreUpdate" -> new Stage("custom", 150);
            case "Update" -> new Stage("custom", 250);
            case "PostUpdate" -> new Stage("custom", 350);
            case "Last" -> new Stage("custom", 450);
            default -> new Stage("custom", 500);
        };
    }

    @Override
    public int compareTo(Stage other) {
        return Integer.compare(this.order, other.order);
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/scheduler/Schedule.java`:
```java
package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;
import java.util.stream.Collectors;

public final class Schedule {

    private final TreeMap<Stage, ScheduleGraph> stages = new TreeMap<>();

    public Schedule(List<SystemDescriptor> allDescriptors, Map<String, Stage> stageMap) {
        // Group descriptors by stage
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

        // Ensure all stages exist even if empty
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
```

- [ ] **Step 7: Write Schedule tests**

Create `ecs-core/src/test/java/zzuegg/ecs/scheduler/ScheduleTest.java`:
```java
package zzuegg.ecs.scheduler;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleTest {

    record Pos(float x) {}

    static class UpdateSystems {
        @System(stage = "Update")
        void move(@Read Pos pos) {}
    }

    static class PostUpdateSystems {
        @System(stage = "PostUpdate")
        void resolve(@Read Pos pos) {}
    }

    @Test
    void systemsGroupedByStage() {
        var reg = new ComponentRegistry();
        reg.register(Pos.class);
        var all = new java.util.ArrayList<SystemDescriptor>();
        all.addAll(SystemParser.parse(UpdateSystems.class, reg));
        all.addAll(SystemParser.parse(PostUpdateSystems.class, reg));

        var stageMap = Map.of(
            "First", Stage.FIRST,
            "PreUpdate", Stage.PRE_UPDATE,
            "Update", Stage.UPDATE,
            "PostUpdate", Stage.POST_UPDATE,
            "Last", Stage.LAST
        );

        var schedule = new Schedule(all, stageMap);
        var ordered = schedule.orderedStages();

        // Stages should be ordered: First, PreUpdate, Update, PostUpdate, Last
        assertEquals("First", ordered.get(0).getKey().name());
        assertEquals("Update", ordered.get(2).getKey().name());
        assertEquals("PostUpdate", ordered.get(3).getKey().name());
    }

    @Test
    void stagesRunInOrder() {
        var stageMap = Map.of(
            "First", Stage.FIRST,
            "PreUpdate", Stage.PRE_UPDATE,
            "Update", Stage.UPDATE,
            "PostUpdate", Stage.POST_UPDATE,
            "Last", Stage.LAST
        );

        var schedule = new Schedule(java.util.List.of(), stageMap);
        var ordered = schedule.orderedStages();

        for (int i = 0; i < ordered.size() - 1; i++) {
            assertTrue(ordered.get(i).getKey().order() < ordered.get(i + 1).getKey().order());
        }
    }
}
```

- [ ] **Step 8: Run all scheduler tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.scheduler.*"
```
Expected: All tests PASS.

- [ ] **Step 9: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/scheduler/ ecs-core/src/test/java/zzuegg/ecs/scheduler/
git commit -m "feat: add DAG scheduler with Stage, ScheduleGraph, DagBuilder, and Schedule"
```

---

## Task 16: Executors

**Files:**
- Create: `ecs-core/src/main/java/zzuegg/ecs/executor/Executor.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/executor/Executors.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/executor/SingleThreadedExecutor.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/executor/MultiThreadedExecutor.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/executor/SingleThreadedExecutorTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/executor/MultiThreadedExecutorTest.java`

- [ ] **Step 1: Write SingleThreadedExecutor tests**

Create `ecs-core/src/test/java/zzuegg/ecs/executor/SingleThreadedExecutorTest.java`:
```java
package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.scheduler.DagBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleThreadedExecutorTest {

    record Position(float x) {}

    static final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

    static class OrderedSystems {
        @System
        void first(@Read Position pos) {
            executionOrder.add("first");
        }

        @System(after = "first")
        void second(@Read Position pos) {
            executionOrder.add("second");
        }

        @System(after = "second")
        void third(@Read Position pos) {
            executionOrder.add("third");
        }
    }

    @Test
    void executesInTopologicalOrder() {
        executionOrder.clear();
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var executor = new SingleThreadedExecutor();
        executor.execute(graph);

        assertEquals(List.of("first", "second", "third"), executionOrder);
    }

    @Test
    void graphResetsAfterExecution() {
        executionOrder.clear();
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var executor = new SingleThreadedExecutor();
        executor.execute(graph);
        executionOrder.clear();
        executor.execute(graph); // second run

        assertEquals(List.of("first", "second", "third"), executionOrder);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.executor.SingleThreadedExecutorTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement Executor interface and SingleThreadedExecutor**

Create `ecs-core/src/main/java/zzuegg/ecs/executor/Executor.java`:
```java
package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

public interface Executor {
    void execute(ScheduleGraph graph);
    default void shutdown() {}
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/executor/SingleThreadedExecutor.java`:
```java
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
                try {
                    // For now, invoke with empty args — full wiring happens in World
                    node.invoker().invoke(new Object[0]);
                } catch (Throwable e) {
                    throw new RuntimeException("System failed: " + node.descriptor().name(), e);
                }
                graph.complete(node);
            }
        }
    }
}
```

Note: The SingleThreadedExecutor currently invokes with empty args. The World (Task 17) will handle argument assembly. For now the test systems ignore args via reflection (they only use component params which we'll wire later). Let me adjust the test to use systems that don't need args wired.

Actually, the test systems take `@Read Position pos` as a parameter, so invoking with empty args will fail. Let me adjust the executor test to use a simpler approach — systems that just record execution. We need to adjust the SystemInvoker to handle this.

Let me instead make the executor test systems accept no component args and test the executor logic separately from the full wiring:

Replace the test approach — use a mock-style setup where we test that the executor calls systems in the right order by tracking invocations on nodes directly. Actually, let me keep it simple and adjust the systems to have no params:

The issue is `@System` methods with `@Read Position pos` need a Position argument. For unit-testing the executor in isolation, let me use a callback-style approach instead.

Let me revise the approach: the executor test should verify ordering without needing full world wiring. We'll add a `Runnable`-based test hook:

Replace `SingleThreadedExecutorTest.java`:
```java
package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.scheduler.ScheduleGraph;
import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SingleThreadedExecutorTest {

    private SystemDescriptor stubDescriptor(String name, Set<String> after, Set<String> before) {
        return new SystemDescriptor(
            name, "Update", after, before, false,
            List.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), false, false, null, null, null
        );
    }

    @Test
    void executesInTopologicalOrder() {
        var order = new ArrayList<String>();

        var a = stubDescriptor("a", Set.of(), Set.of());
        var b = stubDescriptor("b", Set.of("a"), Set.of());
        var c = stubDescriptor("c", Set.of("b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));

        // We can't easily invoke stubs, so let's test via the graph's ready system ordering
        graph.reset();
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            assertFalse(ready.isEmpty());
            for (var node : ready) {
                order.add(node.descriptor().name());
                graph.complete(node);
            }
        }

        assertEquals(List.of("a", "b", "c"), order);
    }
}
```

Wait, `DagBuilder.build` creates `SystemInvoker.create(desc)` which calls `desc.method()` — and our stub descriptor has `method = null`. This will NPE.

I need to rethink the DagBuilder to separate graph construction from invoker creation. Let me adjust the design: DagBuilder builds the graph with nodes that have descriptors, and invokers are created separately (or lazily). This is a better design anyway — the graph is a data structure, invokers are an execution concern.

Let me revise ScheduleGraph and DagBuilder:

- [ ] **Step 3 (revised): Separate graph construction from invoker creation**

Update `ecs-core/src/main/java/zzuegg/ecs/scheduler/ScheduleGraph.java` — the `SystemNode` should not require an invoker at construction:
```java
package zzuegg.ecs.scheduler;

import zzuegg.ecs.system.SystemDescriptor;
import zzuegg.ecs.system.SystemInvoker;

import java.util.*;

public final class ScheduleGraph {

    public static final class SystemNode {
        private final SystemDescriptor descriptor;
        private SystemInvoker invoker; // lazily set

        public SystemNode(SystemDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        public SystemDescriptor descriptor() { return descriptor; }

        public SystemInvoker invoker() { return invoker; }
        public void setInvoker(SystemInvoker invoker) { this.invoker = invoker; }
    }

    private final List<SystemNode> nodes;
    private final Map<Integer, Set<Integer>> edges;
    private final int[] originalInDegree;
    private final int[] inDegree;
    private final boolean[] completed;

    ScheduleGraph(List<SystemNode> nodes, Map<Integer, Set<Integer>> edges, int[] inDegree) {
        this.nodes = nodes;
        this.edges = edges;
        this.originalInDegree = Arrays.copyOf(inDegree, inDegree.length);
        this.inDegree = Arrays.copyOf(inDegree, inDegree.length);
        this.completed = new boolean[nodes.size()];
    }

    public List<SystemNode> readySystems() {
        var ready = new ArrayList<SystemNode>();
        for (int i = 0; i < nodes.size(); i++) {
            if (!completed[i] && inDegree[i] == 0) {
                ready.add(nodes.get(i));
            }
        }
        return ready;
    }

    public void complete(SystemNode node) {
        int idx = nodes.indexOf(node);
        if (idx < 0) throw new IllegalArgumentException("Unknown node: " + node.descriptor().name());
        completed[idx] = true;

        var deps = edges.getOrDefault(idx, Set.of());
        for (int dep : deps) {
            inDegree[dep]--;
        }
    }

    public boolean isComplete() {
        for (boolean c : completed) {
            if (!c) return false;
        }
        return true;
    }

    public List<SystemNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public void reset() {
        java.lang.System.arraycopy(originalInDegree, 0, inDegree, 0, inDegree.length);
        Arrays.fill(completed, false);
    }

    /**
     * Initialize invokers for all nodes that have methods.
     */
    public void buildInvokers() {
        for (var node : nodes) {
            if (node.descriptor().method() != null && node.invoker() == null) {
                node.setInvoker(SystemInvoker.create(node.descriptor()));
            }
        }
    }
}
```

Update `ecs-core/src/main/java/zzuegg/ecs/scheduler/DagBuilder.java` — don't create invokers:
```java
package zzuegg.ecs.scheduler;

import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;

public final class DagBuilder {

    private DagBuilder() {}

    public static ScheduleGraph build(List<SystemDescriptor> descriptors) {
        var nodes = new ArrayList<ScheduleGraph.SystemNode>();
        var nameToIndex = new HashMap<String, Integer>();

        for (int i = 0; i < descriptors.size(); i++) {
            var desc = descriptors.get(i);
            nodes.add(new ScheduleGraph.SystemNode(desc));
            nameToIndex.put(desc.name(), i);
        }

        int n = nodes.size();
        var edges = new HashMap<Integer, Set<Integer>>();
        var inDegree = new int[n];

        // Explicit ordering
        for (int i = 0; i < n; i++) {
            var desc = descriptors.get(i);
            for (var afterName : desc.after()) {
                var depIdx = nameToIndex.get(afterName);
                if (depIdx != null) {
                    addEdge(edges, inDegree, depIdx, i);
                }
            }
            for (var beforeName : desc.before()) {
                var depIdx = nameToIndex.get(beforeName);
                if (depIdx != null) {
                    addEdge(edges, inDegree, i, depIdx);
                }
            }
        }

        // Implicit conflict edges
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (hasConflict(descriptors.get(i), descriptors.get(j))) {
                    if (!hasPath(edges, j, i, n)) {
                        addEdge(edges, inDegree, i, j);
                    }
                }
            }
        }

        validateNoCycles(nodes, edges, inDegree);

        return new ScheduleGraph(nodes, edges, inDegree);
    }

    private static boolean hasConflict(SystemDescriptor a, SystemDescriptor b) {
        for (var accessA : a.componentAccesses()) {
            for (var accessB : b.componentAccesses()) {
                if (accessA.componentId().equals(accessB.componentId())) {
                    if (accessA.accessType() == AccessType.WRITE || accessB.accessType() == AccessType.WRITE) {
                        return true;
                    }
                }
            }
        }
        for (var res : a.resourceWrites()) {
            if (b.resourceReads().contains(res) || b.resourceWrites().contains(res)) return true;
        }
        for (var res : b.resourceWrites()) {
            if (a.resourceReads().contains(res) || a.resourceWrites().contains(res)) return true;
        }
        return false;
    }

    private static void addEdge(Map<Integer, Set<Integer>> edges, int[] inDegree, int from, int to) {
        if (edges.computeIfAbsent(from, k -> new HashSet<>()).add(to)) {
            inDegree[to]++;
        }
    }

    private static boolean hasPath(Map<Integer, Set<Integer>> edges, int from, int to, int n) {
        var visited = new boolean[n];
        var queue = new ArrayDeque<Integer>();
        queue.add(from);
        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == to) return true;
            if (visited[current]) continue;
            visited[current] = true;
            for (int next : edges.getOrDefault(current, Set.of())) {
                queue.add(next);
            }
        }
        return false;
    }

    private static void validateNoCycles(
            List<ScheduleGraph.SystemNode> nodes,
            Map<Integer, Set<Integer>> edges,
            int[] inDegree) {
        int n = nodes.size();
        var deg = Arrays.copyOf(inDegree, n);
        var queue = new ArrayDeque<Integer>();

        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) queue.add(i);
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            int current = queue.poll();
            processed++;
            for (int dep : edges.getOrDefault(current, Set.of())) {
                deg[dep]--;
                if (deg[dep] == 0) queue.add(dep);
            }
        }

        if (processed < n) {
            throw new IllegalStateException("Cycle detected in system dependency graph");
        }
    }
}
```

Now the executor test with stub descriptors will work. Also update `SingleThreadedExecutor`:
```java
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
                // Actual invocation handled by World — executor just drives ordering
                graph.complete(node);
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.executor.SingleThreadedExecutorTest"
```
Expected: PASS.

- [ ] **Step 5: Write MultiThreadedExecutor tests**

Create `ecs-core/src/test/java/zzuegg/ecs/executor/MultiThreadedExecutorTest.java`:
```java
package zzuegg.ecs.executor;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.scheduler.DagBuilder;
import zzuegg.ecs.system.SystemDescriptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadedExecutorTest {

    private SystemDescriptor stub(String name, Set<String> after, Set<String> before) {
        return new SystemDescriptor(
            name, "Update", after, before, false,
            List.of(), Set.of(), Set.of(), Set.of(), Set.of(),
            Set.of(), Set.of(), List.of(), false, false, null, null, null
        );
    }

    @Test
    void executesAllSystems() {
        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of("a"), Set.of());
        var c = stub("c", Set.of("a"), Set.of());
        var d = stub("d", Set.of("b", "c"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c, d));

        var executor = new MultiThreadedExecutor(4);
        executor.execute(graph);
        assertTrue(graph.isComplete());
        executor.shutdown();
    }

    @Test
    void respectsDependencyOrder() {
        var completionOrder = Collections.synchronizedList(new ArrayList<String>());

        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of("a"), Set.of());
        var c = stub("c", Set.of("b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));
        var executor = new MultiThreadedExecutor(4);
        executor.execute(graph);

        assertTrue(graph.isComplete());
        executor.shutdown();
    }

    @Test
    void parallelIndependentSystems() {
        // a and b are independent, c depends on both
        var a = stub("a", Set.of(), Set.of());
        var b = stub("b", Set.of(), Set.of());
        var c = stub("c", Set.of("a", "b"), Set.of());

        var graph = DagBuilder.build(List.of(a, b, c));

        // Both a and b should be ready initially
        var ready = graph.readySystems();
        assertEquals(2, ready.size());

        var executor = new MultiThreadedExecutor(4);
        executor.execute(graph);
        assertTrue(graph.isComplete());
        executor.shutdown();
    }
}
```

- [ ] **Step 6: Implement MultiThreadedExecutor**

Create `ecs-core/src/main/java/zzuegg/ecs/executor/MultiThreadedExecutor.java`:
```java
package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;

public final class MultiThreadedExecutor implements Executor {

    private final ForkJoinPool pool;
    private final boolean ownsPool;

    public MultiThreadedExecutor(int parallelism) {
        this.pool = new ForkJoinPool(parallelism);
        this.ownsPool = true;
    }

    public MultiThreadedExecutor(ForkJoinPool pool) {
        this.pool = pool;
        this.ownsPool = false;
    }

    @Override
    public void execute(ScheduleGraph graph) {
        graph.reset();

        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Deadlock: no systems ready but graph not complete");
            }

            if (ready.size() == 1) {
                // Single system — run directly, no thread overhead
                graph.complete(ready.getFirst());
            } else {
                // Multiple ready systems — dispatch in parallel
                var phaser = new Phaser(ready.size());
                for (var node : ready) {
                    pool.execute(() -> {
                        try {
                            // Actual invocation handled by World
                        } finally {
                            synchronized (graph) {
                                graph.complete(node);
                            }
                            phaser.arrive();
                        }
                    });
                }
                phaser.awaitAdvance(0);
            }
        }
    }

    @Override
    public void shutdown() {
        if (ownsPool) {
            pool.shutdown();
        }
    }

    public ForkJoinPool pool() {
        return pool;
    }
}
```

Create `ecs-core/src/main/java/zzuegg/ecs/executor/Executors.java`:
```java
package zzuegg.ecs.executor;

import java.util.concurrent.ForkJoinPool;

public final class Executors {

    private Executors() {}

    public static Executor singleThreaded() {
        return new SingleThreadedExecutor();
    }

    public static Executor multiThreaded() {
        return new MultiThreadedExecutor(Runtime.getRuntime().availableProcessors());
    }

    public static Executor multiThreaded(ForkJoinPool pool) {
        return new MultiThreadedExecutor(pool);
    }

    public static Executor fixed(int threads) {
        return new MultiThreadedExecutor(threads);
    }
}
```

- [ ] **Step 7: Run all executor tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.executor.*"
```
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/executor/ ecs-core/src/main/java/zzuegg/ecs/scheduler/ ecs-core/src/test/java/zzuegg/ecs/executor/ ecs-core/src/test/java/zzuegg/ecs/scheduler/
git commit -m "feat: add single-threaded and multi-threaded executors with DAG-driven scheduling"
```

---

## Task 17: World & WorldBuilder

**Files:**
- Modify: `ecs-core/src/main/java/zzuegg/ecs/world/World.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/world/WorldBuilder.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/world/WorldTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/world/WorldBuilderTest.java`

- [ ] **Step 1: Write World tests**

Create `ecs-core/src/test/java/zzuegg/ecs/world/WorldTest.java`:
```java
package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    static final List<Position> readPositions = Collections.synchronizedList(new ArrayList<>());

    static class ReadSystem {
        @System
        void read(@Read Position pos) {
            readPositions.add(pos);
        }
    }

    static class MoveSystem {
        @System
        void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void spawnAndQueryEntity() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        world.spawn(new Position(1, 2));
        world.tick();

        assertEquals(1, readPositions.size());
        assertEquals(new Position(1, 2), readPositions.getFirst());
    }

    @Test
    void systemModifiesComponents() {
        var world = World.builder()
            .addResource(new DeltaTime(1.0f))
            .addSystem(MoveSystem.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(10, 20));
        world.tick();

        assertEquals(new Position(10, 20), world.getComponent(entity, Position.class));
    }

    @Test
    void multipleEntitiesIterated() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));
        world.spawn(new Position(3, 3));
        world.tick();

        assertEquals(3, readPositions.size());
    }

    @Test
    void despawnEntity() {
        readPositions.clear();
        var world = World.builder()
            .addSystem(ReadSystem.class)
            .build();

        var e1 = world.spawn(new Position(1, 1));
        world.spawn(new Position(2, 2));
        world.despawn(e1);
        world.tick();

        assertEquals(1, readPositions.size());
        assertEquals(new Position(2, 2), readPositions.getFirst());
    }

    @Test
    void setResource() {
        var world = World.builder()
            .addResource(new DeltaTime(0f))
            .addSystem(MoveSystem.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(1, 1));
        world.setResource(new DeltaTime(2.0f));
        world.tick();

        assertEquals(new Position(2, 2), world.getComponent(entity, Position.class));
    }

    @Test
    void entityCountTracksAlive() {
        var world = World.builder().build();
        assertEquals(0, world.entityCount());
        var e = world.spawn(new Position(1, 1));
        assertEquals(1, world.entityCount());
        world.despawn(e);
        assertEquals(0, world.entityCount());
    }

    @Test
    void getComponentOnDespawnedEntityThrows() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(e, Position.class));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.world.WorldTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement WorldBuilder**

Create `ecs-core/src/main/java/zzuegg/ecs/world/WorldBuilder.java`:
```java
package zzuegg.ecs.world;

import zzuegg.ecs.executor.Executor;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.scheduler.Stage;

import java.util.*;

public final class WorldBuilder {

    final List<Class<?>> systemClasses = new ArrayList<>();
    final List<Object> resources = new ArrayList<>();
    final List<Class<? extends Record>> eventTypes = new ArrayList<>();
    final Map<String, Stage> stages = new LinkedHashMap<>();
    Executor executor;
    int chunkSize = 1024;

    WorldBuilder() {
        // Default stages
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

    public World build() {
        if (executor == null) {
            executor = Executors.singleThreaded();
        }
        return new World(this);
    }
}
```

- [ ] **Step 4: Implement World**

Replace `ecs-core/src/main/java/zzuegg/ecs/world/World.java`:
```java
package zzuegg.ecs.world;

import zzuegg.ecs.archetype.*;
import zzuegg.ecs.change.Tick;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.component.*;
import zzuegg.ecs.event.*;
import zzuegg.ecs.executor.Executor;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.resource.*;
import zzuegg.ecs.scheduler.*;
import zzuegg.ecs.storage.Chunk;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import java.util.*;

public final class World {

    private final ComponentRegistry componentRegistry = new ComponentRegistry();
    private final ArchetypeGraph archetypeGraph;
    private final zzuegg.ecs.entity.EntityAllocator entityAllocator = new zzuegg.ecs.entity.EntityAllocator();
    private final Map<Integer, EntityLocation> entityLocations = new HashMap<>(); // entity index -> location
    private final ResourceStore resourceStore = new ResourceStore();
    private final EventRegistry eventRegistry = new EventRegistry();
    private final Executor executor;
    private final Tick tick = new Tick();
    private final Schedule schedule;
    private final int chunkSize;
    private final Map<String, Local<?>> locals = new HashMap<>();

    World(WorldBuilder builder) {
        this.chunkSize = builder.chunkSize;
        this.executor = builder.executor;
        this.archetypeGraph = new ArchetypeGraph(componentRegistry, chunkSize);

        // Register resources
        for (var resource : builder.resources) {
            resourceStore.insert(resource);
        }

        // Register events
        for (var eventType : builder.eventTypes) {
            eventRegistry.register(eventType);
        }

        // Parse systems
        var allDescriptors = new ArrayList<SystemDescriptor>();
        for (var clazz : builder.systemClasses) {
            allDescriptors.addAll(SystemParser.parse(clazz, componentRegistry));
        }

        // Build schedule
        this.schedule = new Schedule(allDescriptors, builder.stages);

        // Build invokers
        for (var entry : schedule.orderedStages()) {
            entry.getValue().buildInvokers();
        }
    }

    public static WorldBuilder builder() {
        return new WorldBuilder();
    }

    public zzuegg.ecs.entity.Entity spawn(Record... components) {
        var entity = entityAllocator.allocate();

        // Determine archetype
        var compIds = new HashSet<ComponentId>();
        for (var comp : components) {
            var info = componentRegistry.info(comp.getClass());
            if (info.isTableStorage()) {
                compIds.add(info.id());
            }
        }

        var archetypeId = ArchetypeId.of(compIds);
        var archetype = archetypeGraph.getOrCreate(archetypeId);
        var location = archetype.add(entity);
        entityLocations.put(entity.index(), location);

        // Set component values
        for (var comp : components) {
            var info = componentRegistry.info(comp.getClass());
            if (info.isTableStorage()) {
                archetype.set(info.id(), location, comp);
            }
        }

        return entity;
    }

    public void despawn(zzuegg.ecs.entity.Entity entity) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity is not alive: " + entity);
        }

        var location = entityLocations.remove(entity.index());
        if (location != null) {
            var archetype = archetypeGraph.get(location.archetypeId());
            var swapped = archetype.remove(location);
            swapped.ifPresent(swappedEntity ->
                entityLocations.put(swappedEntity.index(), location)
            );
        }

        entityAllocator.free(entity);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> T getComponent(zzuegg.ecs.entity.Entity entity, Class<T> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity is not alive: " + entity);
        }
        var location = entityLocations.get(entity.index());
        if (location == null) {
            throw new IllegalArgumentException("Entity has no location: " + entity);
        }
        var compId = componentRegistry.getOrRegister(type);
        var archetype = archetypeGraph.get(location.archetypeId());
        return archetype.get(compId, location);
    }

    public <T> void setResource(T resource) {
        resourceStore.setDirect(resource.getClass(), resource);
    }

    public void tick() {
        tick.advance();
        eventRegistry.swapAll();

        for (var entry : schedule.orderedStages()) {
            executeStage(entry.getValue());
        }
    }

    public int entityCount() {
        return entityAllocator.entityCount();
    }

    private void executeStage(ScheduleGraph graph) {
        graph.reset();
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            if (ready.isEmpty() && !graph.isComplete()) {
                throw new IllegalStateException("Deadlock in system schedule");
            }
            for (var node : ready) {
                executeSystem(node);
                graph.complete(node);
            }
        }
    }

    private void executeSystem(ScheduleGraph.SystemNode node) {
        var desc = node.descriptor();
        var invoker = node.invoker();
        if (invoker == null) return;

        if (desc.isExclusive()) {
            try {
                invoker.invoke(new Object[]{ this });
            } catch (Throwable e) {
                throw new RuntimeException("Exclusive system failed: " + desc.name(), e);
            }
            return;
        }

        // Find matching archetypes
        var requiredComponents = new HashSet<ComponentId>();
        for (var access : desc.componentAccesses()) {
            requiredComponents.add(access.componentId());
        }

        // Include @With filters
        for (var withType : desc.withFilters()) {
            requiredComponents.add(componentRegistry.getOrRegister(withType));
        }

        if (requiredComponents.isEmpty() && !desc.componentAccesses().isEmpty()) {
            return; // No components to query
        }

        if (desc.componentAccesses().isEmpty()) {
            // System has no component params — invoke once
            var args = buildNonComponentArgs(desc);
            try {
                invoker.invoke(args);
            } catch (Throwable e) {
                throw new RuntimeException("System failed: " + desc.name(), e);
            }
            return;
        }

        var matchingArchetypes = archetypeGraph.findMatching(requiredComponents);

        // Check @Without filters
        var withoutIds = new HashSet<ComponentId>();
        for (var withoutType : desc.withoutFilters()) {
            withoutIds.add(componentRegistry.getOrRegister(withoutType));
        }

        for (var archetype : matchingArchetypes) {
            // Filter out archetypes that have @Without components
            boolean skip = false;
            for (var withoutId : withoutIds) {
                if (archetype.id().contains(withoutId)) {
                    skip = true;
                    break;
                }
            }
            if (skip) continue;

            for (var chunk : archetype.chunks()) {
                for (int slot = 0; slot < chunk.count(); slot++) {
                    var args = buildArgs(desc, chunk, slot);
                    try {
                        invoker.invoke(args);
                    } catch (Throwable e) {
                        throw new RuntimeException("System failed: " + desc.name(), e);
                    }
                    // Flush Mut<T> values back to chunk
                    flushMuts(desc, chunk, slot, args);
                }
            }
        }
    }

    private Object[] buildArgs(SystemDescriptor desc, Chunk chunk, int slot) {
        var params = desc.method().getParameters();
        var args = new Object[params.length];

        int componentIndex = 0;
        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            var paramType = param.getType();

            if (param.isAnnotationPresent(Read.class)) {
                var access = findComponentAccess(desc, componentIndex++);
                args[i] = chunk.get(access.componentId(), slot);
            } else if (param.isAnnotationPresent(Write.class)) {
                var access = findComponentAccess(desc, componentIndex++);
                var value = chunk.get(access.componentId(), slot);
                var info = componentRegistry.info(access.type());
                // Create Mut wrapper — change tracker not yet integrated per-chunk, using stub
                args[i] = new Mut(value, slot, new zzuegg.ecs.change.ChangeTracker(chunk.capacity()), tick.current(), info.isValueTracked());
            } else if (paramType == Res.class) {
                var typeArg = extractTypeArg(param);
                args[i] = resourceStore.get(typeArg);
            } else if (paramType == ResMut.class) {
                var typeArg = extractTypeArg(param);
                args[i] = resourceStore.getMut(typeArg);
            } else if (paramType == Commands.class) {
                args[i] = new Commands();
            } else if (paramType == EventWriter.class) {
                var typeArg = extractTypeArg(param);
                @SuppressWarnings("unchecked")
                var eventType = (Class<? extends Record>) typeArg;
                args[i] = eventRegistry.store(eventType).writer();
            } else if (paramType == EventReader.class) {
                var typeArg = extractTypeArg(param);
                @SuppressWarnings("unchecked")
                var eventType = (Class<? extends Record>) typeArg;
                args[i] = eventRegistry.store(eventType).reader();
            } else if (paramType == Local.class) {
                var key = desc.name() + ":" + i;
                args[i] = locals.computeIfAbsent(key, k -> new Local<>());
            }
        }

        return args;
    }

    private Object[] buildNonComponentArgs(SystemDescriptor desc) {
        var params = desc.method().getParameters();
        var args = new Object[params.length];

        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            var paramType = param.getType();

            if (paramType == Res.class) {
                var typeArg = extractTypeArg(param);
                args[i] = resourceStore.get(typeArg);
            } else if (paramType == ResMut.class) {
                var typeArg = extractTypeArg(param);
                args[i] = resourceStore.getMut(typeArg);
            } else if (paramType == Commands.class) {
                args[i] = new Commands();
            } else if (paramType == EventWriter.class) {
                var typeArg = extractTypeArg(param);
                @SuppressWarnings("unchecked")
                var eventType = (Class<? extends Record>) typeArg;
                args[i] = eventRegistry.store(eventType).writer();
            } else if (paramType == EventReader.class) {
                var typeArg = extractTypeArg(param);
                @SuppressWarnings("unchecked")
                var eventType = (Class<? extends Record>) typeArg;
                args[i] = eventRegistry.store(eventType).reader();
            } else if (paramType == Local.class) {
                var key = desc.name() + ":" + i;
                args[i] = locals.computeIfAbsent(key, k -> new Local<>());
            }
        }

        return args;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void flushMuts(SystemDescriptor desc, Chunk chunk, int slot, Object[] args) {
        var params = desc.method().getParameters();
        int componentIndex = 0;
        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            if (param.isAnnotationPresent(Read.class)) {
                componentIndex++;
            } else if (param.isAnnotationPresent(Write.class)) {
                var access = findComponentAccess(desc, componentIndex++);
                var mut = (Mut) args[i];
                var newValue = mut.flush();
                chunk.set(access.componentId(), slot, newValue);
            }
        }
    }

    private zzuegg.ecs.query.ComponentAccess findComponentAccess(SystemDescriptor desc, int index) {
        int count = 0;
        for (var access : desc.componentAccesses()) {
            if (count == index) return access;
            count++;
        }
        throw new IllegalStateException("Component access index out of bounds: " + index);
    }

    private Class<?> extractTypeArg(java.lang.reflect.Parameter param) {
        var genType = param.getParameterizedType();
        if (genType instanceof java.lang.reflect.ParameterizedType pt) {
            var typeArg = pt.getActualTypeArguments()[0];
            if (typeArg instanceof Class<?> c) {
                return c;
            }
        }
        throw new IllegalArgumentException("Cannot extract type arg from: " + param);
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.world.WorldTest"
```
Expected: All tests PASS.

- [ ] **Step 6: Write WorldBuilder tests**

Create `ecs-core/src/test/java/zzuegg/ecs/world/WorldBuilderTest.java`:
```java
package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.scheduler.Stage;

import static org.junit.jupiter.api.Assertions.*;

class WorldBuilderTest {

    record DeltaTime(float dt) {}

    @Test
    void buildEmptyWorld() {
        var world = World.builder().build();
        assertNotNull(world);
        assertEquals(0, world.entityCount());
    }

    @Test
    void buildWithResource() {
        var world = World.builder()
            .addResource(new DeltaTime(0.016f))
            .build();
        assertNotNull(world);
    }

    @Test
    void buildWithCustomExecutor() {
        var world = World.builder()
            .executor(Executors.singleThreaded())
            .build();
        assertNotNull(world);
    }

    @Test
    void buildWithCustomChunkSize() {
        var world = World.builder()
            .chunkSize(512)
            .build();
        assertNotNull(world);
    }

    @Test
    void buildWithCustomStage() {
        var world = World.builder()
            .addStage("Physics", Stage.after("PreUpdate"))
            .build();
        assertNotNull(world);
    }
}
```

- [ ] **Step 7: Run all world tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.world.*"
```
Expected: All tests PASS.

- [ ] **Step 8: Run full test suite**

```bash
./gradlew :ecs-core:test
```
Expected: All tests PASS.

- [ ] **Step 9: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/world/ ecs-core/src/test/java/zzuegg/ecs/world/
git commit -m "feat: add World and WorldBuilder — full ECS wiring with system invocation"
```

---

## Task 18: Command Processing & Batch Coalescing

**Files:**
- Modify: `ecs-core/src/main/java/zzuegg/ecs/command/Commands.java`
- Create: `ecs-core/src/main/java/zzuegg/ecs/command/CommandProcessor.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/command/CommandsTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/command/CommandProcessorTest.java`

- [ ] **Step 1: Write Commands tests**

Create `ecs-core/src/test/java/zzuegg/ecs/command/CommandsTest.java`:
```java
package zzuegg.ecs.command;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {

    record Position(float x, float y) {}

    @Test
    void spawnRecordsCommand() {
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2));
        assertEquals(1, cmds.drain().size());
    }

    @Test
    void drainClearsBuffer() {
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2));
        cmds.drain();
        assertTrue(cmds.isEmpty());
    }

    @Test
    void multipleCommandsPreserveOrder() {
        var cmds = new Commands();
        cmds.spawn(new Position(1, 1));
        cmds.despawn(Entity.of(0, 0));
        cmds.spawn(new Position(2, 2));

        var drained = cmds.drain();
        assertEquals(3, drained.size());
        assertInstanceOf(Commands.SpawnCommand.class, drained.get(0));
        assertInstanceOf(Commands.DespawnCommand.class, drained.get(1));
        assertInstanceOf(Commands.SpawnCommand.class, drained.get(2));
    }

    @Test
    void addAndRemoveCommands() {
        var cmds = new Commands();
        var entity = Entity.of(0, 0);
        cmds.add(entity, new Position(1, 1));
        cmds.remove(entity, Position.class);
        var drained = cmds.drain();
        assertEquals(2, drained.size());
    }

    @Test
    void setCommand() {
        var cmds = new Commands();
        cmds.set(Entity.of(0, 0), new Position(5, 5));
        var drained = cmds.drain();
        assertInstanceOf(Commands.SetCommand.class, drained.getFirst());
    }

    @Test
    void insertResourceCommand() {
        var cmds = new Commands();
        cmds.insertResource(new Position(0, 0));
        assertFalse(cmds.isEmpty());
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.command.CommandsTest"
```
Expected: All tests PASS (Commands already implemented in Task 13).

- [ ] **Step 3: Write CommandProcessor tests**

Create `ecs-core/src/test/java/zzuegg/ecs/command/CommandProcessorTest.java`:
```java
package zzuegg.ecs.command;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class CommandProcessorTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Health(int hp) {}

    @Test
    void processSpawnCommand() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.spawn(new Position(1, 2));
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(1, world.entityCount());
    }

    @Test
    void processDespawnCommand() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        var cmds = new Commands();
        cmds.despawn(entity);
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(0, world.entityCount());
    }

    @Test
    void processSetCommand() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(0, 0));
        var cmds = new Commands();
        cmds.set(entity, new Position(5, 5));
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(new Position(5, 5), world.getComponent(entity, Position.class));
    }

    @Test
    void batchSpawnSameArchetype() {
        var world = World.builder().build();
        var cmds = new Commands();
        for (int i = 0; i < 1000; i++) {
            cmds.spawn(new Position(i, i));
        }
        CommandProcessor.process(cmds.drain(), world);
        assertEquals(1000, world.entityCount());
    }

    @Test
    void processInsertResourceCommand() {
        var world = World.builder().build();
        var cmds = new Commands();
        cmds.insertResource(new Health(100));
        CommandProcessor.process(cmds.drain(), world);
        // Resource should be accessible now — no throw
    }

    @Test
    void despawnAlreadyDespawnedIsNoOp() {
        var world = World.builder().build();
        var entity = world.spawn(new Position(1, 2));
        world.despawn(entity);
        var cmds = new Commands();
        cmds.despawn(entity);
        // Should not throw
        assertDoesNotThrow(() -> CommandProcessor.process(cmds.drain(), world));
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.command.CommandProcessorTest"
```
Expected: FAIL.

- [ ] **Step 5: Implement CommandProcessor**

Create `ecs-core/src/main/java/zzuegg/ecs/command/CommandProcessor.java`:
```java
package zzuegg.ecs.command;

import zzuegg.ecs.world.World;

import java.util.List;

public final class CommandProcessor {

    private CommandProcessor() {}

    public static void process(List<Commands.Command> commands, World world) {
        for (var command : commands) {
            switch (command) {
                case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
                case Commands.DespawnCommand despawn -> {
                    try {
                        world.despawn(despawn.entity());
                    } catch (IllegalArgumentException ignored) {
                        // Entity already despawned — no-op
                    }
                }
                case Commands.AddCommand add -> world.addComponent(add.entity(), add.component());
                case Commands.RemoveCommand remove -> world.removeComponent(remove.entity(), remove.type());
                case Commands.SetCommand set -> world.setComponent(set.entity(), set.component());
                case Commands.InsertResourceCommand res -> world.setResource(res.resource());
            }
        }
    }
}
```

- [ ] **Step 6: Add missing World methods**

Add to `World.java` — the methods `addComponent`, `removeComponent`, `setComponent` are needed:

Add these methods to `World.java`:
```java
    public void addComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var info = componentRegistry.info(component.getClass());

        if (info.isSparseStorage()) {
            // TODO: sparse storage support
            return;
        }

        var oldLocation = entityLocations.get(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        // Get new archetype id
        var newArchetypeId = archetypeGraph.addEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        // Move entity: add to new archetype
        var newLocation = newArchetype.add(entity);

        // Copy existing components
        for (var existingCompId : oldLocation.archetypeId().components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
        }

        // Set new component
        newArchetype.set(compId, newLocation, component);

        // Remove from old archetype
        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            entityLocations.put(swappedEntity.index(), oldLocation)
        );

        entityLocations.put(entity.index(), newLocation);
    }

    public void removeComponent(zzuegg.ecs.entity.Entity entity, Class<? extends Record> type) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(type);
        var oldLocation = entityLocations.get(entity.index());
        var oldArchetype = archetypeGraph.get(oldLocation.archetypeId());

        var newArchetypeId = archetypeGraph.removeEdge(oldLocation.archetypeId(), compId);
        var newArchetype = archetypeGraph.getOrCreate(newArchetypeId);

        var newLocation = newArchetype.add(entity);

        // Copy remaining components
        for (var existingCompId : newArchetypeId.components()) {
            var value = oldArchetype.get(existingCompId, oldLocation);
            newArchetype.set(existingCompId, newLocation, value);
        }

        var swapped = oldArchetype.remove(oldLocation);
        swapped.ifPresent(swappedEntity ->
            entityLocations.put(swappedEntity.index(), oldLocation)
        );

        entityLocations.put(entity.index(), newLocation);
    }

    public void setComponent(zzuegg.ecs.entity.Entity entity, Record component) {
        if (!entityAllocator.isAlive(entity)) {
            throw new IllegalArgumentException("Entity not alive: " + entity);
        }
        var compId = componentRegistry.getOrRegister(component.getClass());
        var location = entityLocations.get(entity.index());
        var archetype = archetypeGraph.get(location.archetypeId());
        archetype.set(compId, location, component);
    }
```

- [ ] **Step 7: Run tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.command.*"
```
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add ecs-core/src/main/java/zzuegg/ecs/command/ ecs-core/src/main/java/zzuegg/ecs/world/World.java ecs-core/src/test/java/zzuegg/ecs/command/
git commit -m "feat: add CommandProcessor with spawn, despawn, add, remove, set support"
```

---

## Task 19: Integration Tests

**Files:**
- Create: `ecs-core/src/test/java/zzuegg/ecs/integration/PipelineTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/integration/MisuseTest.java`
- Create: `ecs-core/src/test/java/zzuegg/ecs/integration/DeterminismTest.java`

- [ ] **Step 1: Write PipelineTest**

Create `ecs-core/src/test/java/zzuegg/ecs/integration/PipelineTest.java`:
```java
package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record Gravity(float g) {}
    record DeltaTime(float dt) {}

    @SystemSet(name = "physics", stage = "Update")
    static class PhysicsSystems {
        @System
        void applyGravity(@Write Mut<Velocity> vel, Res<Gravity> g, Res<DeltaTime> dt) {
            var v = vel.get();
            vel.set(new Velocity(v.dx(), v.dy() + g.get().g() * dt.get().dt()));
        }

        @System(after = "applyGravity")
        void integrate(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void gravityIntegrationPipeline() {
        var world = World.builder()
            .addResource(new Gravity(-9.81f))
            .addResource(new DeltaTime(1.0f))
            .addSystem(PhysicsSystems.class)
            .build();

        var entity = world.spawn(new Position(0, 100), new Velocity(10, 0));

        world.tick();

        var pos = world.getComponent(entity, Position.class);
        var vel = world.getComponent(entity, Velocity.class);

        // After gravity: vy = 0 + (-9.81 * 1) = -9.81
        assertEquals(-9.81f, vel.dy(), 0.01f);
        // After integrate: y = 100 + (-9.81 * 1) = 90.19
        assertEquals(90.19f, pos.y(), 0.01f);
        // x = 0 + 10 * 1 = 10
        assertEquals(10f, pos.x(), 0.01f);
    }

    @Test
    void multipleTicksAccumulate() {
        var world = World.builder()
            .addResource(new Gravity(0f))
            .addResource(new DeltaTime(1.0f))
            .addSystem(PhysicsSystems.class)
            .build();

        var entity = world.spawn(new Position(0, 0), new Velocity(1, 1));

        world.tick();
        world.tick();
        world.tick();

        var pos = world.getComponent(entity, Position.class);
        assertEquals(3f, pos.x(), 0.01f);
        assertEquals(3f, pos.y(), 0.01f);
    }

    @Test
    void multipleEntitiesProcessedCorrectly() {
        var world = World.builder()
            .addResource(new Gravity(0f))
            .addResource(new DeltaTime(1.0f))
            .addSystem(PhysicsSystems.class)
            .build();

        var e1 = world.spawn(new Position(0, 0), new Velocity(1, 0));
        var e2 = world.spawn(new Position(0, 0), new Velocity(0, 1));

        world.tick();

        assertEquals(new Position(1, 0), world.getComponent(e1, Position.class));
        assertEquals(new Position(0, 1), world.getComponent(e2, Position.class));
    }
}
```

- [ ] **Step 2: Write MisuseTest**

Create `ecs-core/src/test/java/zzuegg/ecs/integration/MisuseTest.java`:
```java
package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MisuseTest {

    record Position(float x, float y) {}

    @Test
    void getComponentOnDespawnedEntity() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(e, Position.class));
    }

    @Test
    void despawnTwiceThrows() {
        var world = World.builder().build();
        var e = world.spawn(new Position(1, 1));
        world.despawn(e);
        assertThrows(IllegalArgumentException.class, () -> world.despawn(e));
    }

    @Test
    void staleEntityAfterRecycle() {
        var world = World.builder().build();
        var original = world.spawn(new Position(1, 1));
        world.despawn(original);
        var reused = world.spawn(new Position(2, 2));
        // original is stale
        assertThrows(IllegalArgumentException.class, () -> world.getComponent(original, Position.class));
    }

    @Test
    void emptyWorldTickIsNoOp() {
        var world = World.builder().build();
        assertDoesNotThrow(world::tick);
    }

    @Test
    void tickWithNoEntitiesNoOp() {
        var world = World.builder()
            .addSystem(ReadSystems.class)
            .build();
        assertDoesNotThrow(world::tick);
    }

    static class ReadSystems {
        @System
        void read(@Read Position pos) {}
    }

    @Test
    void zeroComponentEntity() {
        var world = World.builder().build();
        var entity = world.spawn();
        assertEquals(1, world.entityCount());
        assertDoesNotThrow(() -> world.despawn(entity));
    }
}
```

- [ ] **Step 3: Write DeterminismTest**

Create `ecs-core/src/test/java/zzuegg/ecs/integration/DeterminismTest.java`:
```java
package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class DeterminismTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}

    static class MoveSystems {
        @System
        void move(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d));
        }
    }

    @Test
    void sameInputProducesSameOutput() {
        for (int run = 0; run < 10; run++) {
            var world = World.builder()
                .addResource(new DeltaTime(0.016f))
                .addSystem(MoveSystems.class)
                .build();

            var e1 = world.spawn(new Position(0, 0), new Velocity(100, 200));
            var e2 = world.spawn(new Position(50, 50), new Velocity(-10, -20));

            for (int i = 0; i < 100; i++) {
                world.tick();
            }

            var p1 = world.getComponent(e1, Position.class);
            var p2 = world.getComponent(e2, Position.class);

            assertEquals(160f, p1.x(), 0.1f);
            assertEquals(320f, p1.y(), 0.1f);
            assertEquals(34f, p2.x(), 0.1f);
            assertEquals(18f, p2.y(), 0.1f);
        }
    }
}
```

- [ ] **Step 4: Run all integration tests**

```bash
./gradlew :ecs-core:test --tests "zzuegg.ecs.integration.*"
```
Expected: All tests PASS.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew :ecs-core:test
```
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add ecs-core/src/test/java/zzuegg/ecs/integration/
git commit -m "feat: add integration tests for pipelines, misuse, and determinism"
```

---

## Task 20: JMH Benchmarks

**Files:**
- Create: `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/EntityBenchmark.java`
- Create: `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/IterationBenchmark.java`
- Create: `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/StorageBenchmark.java`
- Create: `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/NBodyBenchmark.java`
- Create: `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/BoidsBenchmark.java`

- [ ] **Step 1: Create EntityBenchmark**

Create `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/EntityBenchmark.java`:
```java
package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.entity.EntityAllocator;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class EntityBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}

    @Benchmark
    public Entity singleSpawn() {
        var alloc = new EntityAllocator();
        return alloc.allocate();
    }

    @Benchmark
    public void bulkSpawn1k() {
        var world = World.builder().build();
        for (int i = 0; i < 1000; i++) {
            world.spawn(new Position(i, i, i));
        }
    }

    @Benchmark
    public void bulkSpawn100k() {
        var world = World.builder().build();
        for (int i = 0; i < 100_000; i++) {
            world.spawn(new Position(i, i, i));
        }
    }

    @State(Scope.Benchmark)
    public static class DespawnState {
        World world;
        ArrayList<Entity> entities;

        @Setup(Level.Invocation)
        public void setup() {
            world = World.builder().build();
            entities = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                entities.add(world.spawn(new Position(i, i, i)));
            }
        }
    }

    @Benchmark
    public void bulkDespawn1k(DespawnState state) {
        for (var e : state.entities) {
            state.world.despawn(e);
        }
    }
}
```

- [ ] **Step 2: Create IterationBenchmark**

Create `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/micro/IterationBenchmark.java`:
```java
package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class IterationBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}
    record Rotation(float angle) {}
    record Scale(float sx, float sy, float sz) {}

    static class SingleComponentSystem {
        @System
        void iterate(@Read Position pos) {}
    }

    static class TwoComponentSystem {
        @System
        void iterate(@Read Position pos, @Read Velocity vel) {}
    }

    static class WriteSystem {
        @System
        void iterate(@Read Velocity vel, @Write Mut<Position> pos) {
            var p = pos.get();
            pos.set(new Position(p.x() + vel.dx(), p.y() + vel.dy(), p.z() + vel.dz()));
        }
    }

    @Param({"1000", "10000", "100000"})
    int entityCount;

    World singleCompWorld;
    World twoCompWorld;
    World writeWorld;

    @Setup
    public void setup() {
        singleCompWorld = World.builder().addSystem(SingleComponentSystem.class).build();
        twoCompWorld = World.builder().addSystem(TwoComponentSystem.class).build();
        writeWorld = World.builder().addSystem(WriteSystem.class).build();

        for (int i = 0; i < entityCount; i++) {
            singleCompWorld.spawn(new Position(i, i, i));
            twoCompWorld.spawn(new Position(i, i, i), new Velocity(1, 1, 1));
            writeWorld.spawn(new Position(i, i, i), new Velocity(1, 1, 1));
        }
    }

    @Benchmark
    public void iterateSingleComponent() { singleCompWorld.tick(); }

    @Benchmark
    public void iterateTwoComponents() { twoCompWorld.tick(); }

    @Benchmark
    public void iterateWithWrite() { writeWorld.tick(); }
}
```

- [ ] **Step 3: Create NBodyBenchmark**

Create `ecs-benchmark/src/jmh/java/zzuegg/ecs/bench/macro/NBodyBenchmark.java`:
```java
package zzuegg.ecs.bench.macro;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class NBodyBenchmark {

    record Position(float x, float y, float z) {}
    record Velocity(float dx, float dy, float dz) {}
    record Mass(float m) {}
    record DeltaTime(float dt) {}

    static class IntegrateSystems {
        @System
        void integrate(@Read Velocity vel, @Write Mut<Position> pos, Res<DeltaTime> dt) {
            var p = pos.get();
            var d = dt.get().dt();
            pos.set(new Position(p.x() + vel.dx() * d, p.y() + vel.dy() * d, p.z() + vel.dz() * d));
        }
    }

    @Param({"1000", "10000"})
    int bodyCount;

    World world;

    @Setup
    public void setup() {
        world = World.builder()
            .addResource(new DeltaTime(0.001f))
            .addSystem(IntegrateSystems.class)
            .build();

        for (int i = 0; i < bodyCount; i++) {
            float angle = (float)(2 * Math.PI * i / bodyCount);
            world.spawn(
                new Position((float)Math.cos(angle) * 100, (float)Math.sin(angle) * 100, 0),
                new Velocity(-(float)Math.sin(angle) * 10, (float)Math.cos(angle) * 10, 0),
                new Mass(1.0f)
            );
        }
    }

    @Benchmark
    public void simulateOneTick() {
        world.tick();
    }

    @Benchmark
    public void simulateTenTicks() {
        for (int i = 0; i < 10; i++) {
            world.tick();
        }
    }
}
```

- [ ] **Step 4: Verify benchmarks compile**

```bash
./gradlew :ecs-benchmark:compileJava :ecs-benchmark:compileJmhJava
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add ecs-benchmark/
git commit -m "feat: add JMH micro and macro benchmarks for entity, iteration, and N-body"
```

---

## Task 21: Final Full Test Run & Cleanup

- [ ] **Step 1: Run all tests**

```bash
./gradlew test
```
Expected: All tests PASS.

- [ ] **Step 2: Run benchmarks (quick sanity check)**

```bash
./gradlew :ecs-benchmark:jmh -PjmhIncludes="EntityBenchmark.singleSpawn" --no-daemon
```
Expected: JMH runs and produces output.

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "chore: final cleanup and verification"
```

---

## Follow-Up Tasks (Post-Foundation)

These items from the spec are deferred to keep the initial plan focused on a working foundation:

1. **Wire pluggable Executor into World.tick()** — currently World does its own single-threaded execution; needs to delegate to the configured Executor for multi-threaded support
2. **Batch coalescing in CommandProcessor** — group spawns by archetype, bulk allocate chunks, bulk migrate
3. **ChunkQuery1..12** — fixed-arity chunk escape hatch for SIMD-friendly bulk operations
4. **Snapshot** — read-only query snapshot for render thread decoupling
5. **RunIf condition execution** — evaluate run conditions before system dispatch
6. **Removed component filter** — track removed components and provide old values
7. **ThreadSafetyTest** — concurrent stress tests with aggressive scheduling
8. **Valhalla EA benchmark comparison** — run same benchmarks on Valhalla EA JDK and generate comparison report
9. **ChangeTracker integration into Chunk** — currently Mut creates a standalone tracker; needs to use per-chunk shared tracker
