package zzuegg.ecs.scheduler;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;

import static org.junit.jupiter.api.Assertions.*;

class DagBuilderTest {

    record Position(float x, float y) {}

    static class IndependentSystems {
        @System void readA(@Read Position pos) {}
        @System void readB(@Read Position pos) {}
    }

    static class ConflictingSystems {
        @System void writePos(@Write Mut<Position> pos) {}
        @System void alsoWritePos(@Write Mut<Position> pos) {}
    }

    static class OrderedSystems {
        @System(after = "second") void first(@Read Position pos) {}
        @System void second(@Read Position pos) {}
    }

    static class CyclicSystems {
        @System(after = "b") void a(@Read Position pos) {}
        @System(after = "a") void b(@Read Position pos) {}
    }

    @Test
    void independentReadersCanRunInParallel() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(IndependentSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(2, ready.size());
    }

    @Test
    void conflictingWritersGetImplicitEdge() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(ConflictingSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(1, ready.size());
    }

    @Test
    void explicitOrderingCreatesEdge() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(OrderedSystems.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(1, ready.size());
        assertTrue(ready.getFirst().descriptor().name().endsWith(".second"));
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
        graph.complete(ready.getFirst());

        var nextReady = graph.readySystems();
        assertEquals(1, nextReady.size());
        assertTrue(nextReady.getFirst().descriptor().name().endsWith(".first"));
    }

    record Alpha(int a) {}
    record Beta(int b) {}
    record GameClock(float dt) {}
    record Budget(int v) {}

    static class BeforeOrdering {
        @System(before = "consumer") void producer(@Read Position pos) {}
        @System void consumer(@Read Position pos) {}
    }

    @Test
    void beforeCreatesEdge() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(BeforeOrdering.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(1, ready.size(),
            "@Before should force the referenced system after this one; only producer ready");
        assertTrue(ready.getFirst().descriptor().name().endsWith(".producer"));

        graph.complete(ready.getFirst());
        var next = graph.readySystems();
        assertEquals(1, next.size());
        assertTrue(next.getFirst().descriptor().name().endsWith(".consumer"));
    }

    static class ResourceConflictWriters {
        @System void writesClock(zzuegg.ecs.resource.ResMut<GameClock> clock) {}
        @System void alsoWritesClock(zzuegg.ecs.resource.ResMut<GameClock> clock) {}
    }

    @Test
    void resourceWriteWriteConflictsSerialize() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(ResourceConflictWriters.class, reg);
        var graph = DagBuilder.build(descriptors);
        assertEquals(1, graph.readySystems().size());
    }

    static class ResourceReadWriteConflict {
        @System void readsClock(zzuegg.ecs.resource.Res<GameClock> clock) {}
        @System void writesClock(zzuegg.ecs.resource.ResMut<GameClock> clock) {}
    }

    @Test
    void resourceReadWriteConflictSerializes() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(ResourceReadWriteConflict.class, reg);
        var graph = DagBuilder.build(descriptors);
        // Read+Write on the same resource: not disjoint, must serialize.
        assertEquals(1, graph.readySystems().size());
    }

    static class ResourceReadOnly {
        @System void readA(zzuegg.ecs.resource.Res<GameClock> clock) {}
        @System void readB(zzuegg.ecs.resource.Res<GameClock> clock) {}
    }

    @Test
    void resourceReadReadRunsInParallel() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(ResourceReadOnly.class, reg);
        var graph = DagBuilder.build(descriptors);
        assertEquals(2, graph.readySystems().size(),
            "two read-only resource accesses must be independent");
    }

    static class TransitiveConflictChain {
        @System void a(@Write Mut<Position> pos) {}
        @System void b(@Read Position pos) {}
        @System void c(@Write Mut<Position> pos) {}
    }

    @Test
    void transitiveWriteReadWriteChainIsTotallyOrdered() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(TransitiveConflictChain.class, reg);
        var graph = DagBuilder.build(descriptors);

        // Only one system can be ready at any time — the chain forces a->b->c
        // (order within a class isn't guaranteed, but the pairwise conflicts
        // plus 'no cycle' leave at most one ready per wave).
        int waves = 0;
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            assertEquals(1, ready.size(),
                "expected a total order across the write/read/write chain; wave " + waves);
            graph.complete(ready.getFirst());
            waves++;
            if (waves > 5) fail("runaway schedule");
        }
        assertEquals(3, waves);
    }

    static class DisjointWriters {
        @System @Without(Beta.class) void writeAlphaWithoutBeta(@Write Mut<Alpha> a) {}
        @System @With(Beta.class)    void writeAlphaWithBeta(@Write Mut<Alpha> a) {}
    }

    @Test
    void writersWithDisjointFiltersRunInParallel() {
        // Both systems write Alpha — normally a conflict — but @With(Beta) and
        // @Without(Beta) guarantee they pick disjoint archetype sets, so the
        // scheduler should leave them independent.
        var reg = new ComponentRegistry();
        reg.register(Alpha.class);
        reg.register(Beta.class);
        var descriptors = SystemParser.parse(DisjointWriters.class, reg);
        var graph = DagBuilder.build(descriptors);

        var ready = graph.readySystems();
        assertEquals(2, ready.size(),
            "writers on disjoint @With/@Without filter sets must be independent");
    }

    static class AmbiguousClassA {
        @System void update(@Read Position pos) {}
    }

    static class AmbiguousClassB {
        @System void update(@Read Position pos) {}
        @System(after = "update") void consumer(@Read Position pos) {}
    }

    @Test
    void ambiguousSimpleNameReferenceThrows() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descA = SystemParser.parse(AmbiguousClassA.class, reg);
        var descB = SystemParser.parse(AmbiguousClassB.class, reg);
        var combined = new java.util.ArrayList<SystemDescriptor>();
        combined.addAll(descA);
        combined.addAll(descB);

        // Two distinct "update" methods exist; "after = update" is ambiguous.
        // Silent binding to the first one is a footgun; the build must throw.
        var ex = assertThrows(IllegalStateException.class,
            () -> DagBuilder.build(combined));
        assertTrue(ex.getMessage().toLowerCase().contains("ambiguous"),
            "expected 'ambiguous' in error; got: " + ex.getMessage());
    }

    static class ExclusiveAndReaders {
        @System void readA(@Read Position pos) {}
        @System void readB(@Read Position pos) {}
        @Exclusive
        @System void exclusive(zzuegg.ecs.world.World world) {}
    }

    @Test
    void exclusiveSystemIsNeverReadyWithOthers() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(ExclusiveAndReaders.class, reg);
        var graph = DagBuilder.build(descriptors);

        // Walk the entire schedule and assert the exclusive system never shares a ready wave
        // with any other system — the cardinal safety property it exists to provide.
        int waves = 0;
        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            assertFalse(ready.isEmpty(), "deadlock — no systems ready");
            boolean hasExclusive = ready.stream().anyMatch(n -> n.descriptor().isExclusive());
            if (hasExclusive) {
                assertEquals(1, ready.size(),
                    "exclusive system must not share a ready wave with others; got " +
                        ready.stream().map(n -> n.descriptor().name()).toList());
            }
            for (var node : ready) graph.complete(node);
            waves++;
            if (waves > 100) fail("runaway schedule");
        }
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
