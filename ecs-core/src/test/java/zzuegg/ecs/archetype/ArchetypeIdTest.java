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
