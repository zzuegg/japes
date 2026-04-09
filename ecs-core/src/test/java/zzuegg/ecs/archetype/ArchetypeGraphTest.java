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
        assertEquals(2, matching.size());
    }
}
