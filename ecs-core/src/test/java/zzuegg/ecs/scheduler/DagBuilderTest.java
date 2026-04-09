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
