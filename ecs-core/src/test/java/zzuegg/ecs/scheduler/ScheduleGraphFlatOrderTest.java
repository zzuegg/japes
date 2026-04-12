package zzuegg.ecs.scheduler;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.system.System;
import zzuegg.ecs.system.SystemParser;
import zzuegg.ecs.component.ComponentRegistry;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScheduleGraph#flatOrder()} — a pre-computed
 * topological order that the single-threaded executor can iterate
 * instead of running the DAG loop on every tick.
 */
class ScheduleGraphFlatOrderTest {

    // Minimal system classes for DAG construction.
    public static class IndependentSystems {
        @System void a() {}
        @System void b() {}
        @System void c() {}
    }

    public static class OrderedSystems {
        @System void first() {}
        @System(after = "first") void second() {}
        @System(after = "second") void third() {}
    }

    @Test
    void flatOrderVisitsEveryNodeExactlyOnce() {
        var descs = SystemParser.parse(IndependentSystems.class, new ComponentRegistry());
        var graph = DagBuilder.build(descs);

        var order = graph.flatOrder();
        assertEquals(3, order.length);

        var names = new java.util.HashSet<String>();
        for (var node : order) names.add(node.descriptor().name());
        assertTrue(names.contains("IndependentSystems.a"));
        assertTrue(names.contains("IndependentSystems.b"));
        assertTrue(names.contains("IndependentSystems.c"));
    }

    @Test
    void flatOrderRespectsAfterOrdering() {
        var descs = SystemParser.parse(OrderedSystems.class, new ComponentRegistry());
        var graph = DagBuilder.build(descs);

        var order = graph.flatOrder();
        assertEquals(3, order.length);

        var nameOrder = new ArrayList<String>();
        for (var node : order) nameOrder.add(node.descriptor().name());
        assertTrue(
            nameOrder.indexOf("OrderedSystems.first") < nameOrder.indexOf("OrderedSystems.second"),
            "first must precede second: " + nameOrder);
        assertTrue(
            nameOrder.indexOf("OrderedSystems.second") < nameOrder.indexOf("OrderedSystems.third"),
            "second must precede third: " + nameOrder);
    }

    @Test
    void flatOrderIsCachedAcrossCalls() {
        var descs = SystemParser.parse(IndependentSystems.class, new ComponentRegistry());
        var graph = DagBuilder.build(descs);

        var order1 = graph.flatOrder();
        var order2 = graph.flatOrder();
        assertSame(order1, order2, "flatOrder must be cached, not recomputed");
    }

    @Test
    void emptyGraphHasEmptyFlatOrder() {
        var graph = DagBuilder.build(java.util.List.of());
        assertEquals(0, graph.flatOrder().length);
    }
}
