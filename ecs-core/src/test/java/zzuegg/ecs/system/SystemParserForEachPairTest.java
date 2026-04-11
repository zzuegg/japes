package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-level tests for {@code @ForEachPair}. Walks the
 * {@link SystemDescriptor} output to verify the annotation is read
 * and classified correctly before the scheduler wiring kicks in.
 * Keeping this separate from the main {@code SystemParserTest} so
 * the ForEachPair plumbing can land in small TDD steps without
 * touching the pre-existing suite.
 */
class SystemParserForEachPairTest {

    record Position(float x, float y) {}
    record Hunting(int power) {}

    static class PursuitSystem {
        @zzuegg.ecs.system.System
        @ForEachPair(Hunting.class)
        void pursuit(
                @Read Position sourcePos,
                Hunting hunting
        ) {}
    }

    @Test
    void parsesForEachPairDrivingType() {
        var descriptors = SystemParser.parse(PursuitSystem.class, new ComponentRegistry());
        assertEquals(1, descriptors.size());
        var desc = descriptors.getFirst();
        assertEquals(Hunting.class, desc.pairIterationType(),
            "descriptor must carry the @ForEachPair relation type");
    }

    record Velocity(float dx, float dy) {}

    static class BothSidesSystem {
        @zzuegg.ecs.system.System
        @ForEachPair(Hunting.class)
        void pursuit(
                @Read Position sourcePos,                  // source (default)
                @FromTarget @Read Position targetPos,      // target (opt-in)
                Hunting hunting
        ) {}
    }

    @Test
    void classifiesSourceAndTargetReadParams() {
        var descriptors = SystemParser.parse(BothSidesSystem.class, new ComponentRegistry());
        var desc = descriptors.getFirst();
        var accesses = desc.componentAccesses();
        assertEquals(2, accesses.size());

        // The parser must tag the second Position read as target-side
        // so the pair-iteration plan can resolve it against the
        // target archetype instead of the source's chunk.
        assertFalse(accesses.get(0).fromTarget(),
            "first @Read Position must be source-side (default)");
        assertTrue(accesses.get(1).fromTarget(),
            "second @Read Position must be target-side via @FromTarget");
    }

    static class BothPairAndForEachPair {
        @zzuegg.ecs.system.System
        @Pair(Hunting.class)
        @ForEachPair(Hunting.class)
        void conflicted(@Read Position pos) {}
    }

    @Test
    void rejectsPairAndForEachPairOnSameMethod() {
        var ex = assertThrows(RuntimeException.class,
            () -> SystemParser.parse(BothPairAndForEachPair.class, new ComponentRegistry()));
        assertTrue(ex.getMessage() == null || ex.getMessage().contains("@Pair")
                || (ex.getCause() != null && String.valueOf(ex.getCause().getMessage()).contains("@Pair")),
            "parser must reject a method carrying both @Pair and @ForEachPair");
    }

    static class WriteToTarget {
        @zzuegg.ecs.system.System
        @ForEachPair(Hunting.class)
        void bad(@FromTarget @Write Mut<Velocity> targetVel) {}
    }

    @Test
    void rejectsFromTargetWrite() {
        var ex = assertThrows(RuntimeException.class,
            () -> SystemParser.parse(WriteToTarget.class, new ComponentRegistry()));
        assertTrue(ex.getMessage() == null || ex.getMessage().contains("@FromTarget")
                || (ex.getCause() != null && String.valueOf(ex.getCause().getMessage()).contains("@FromTarget")),
            "parser must reject @FromTarget @Write — target-side writes are v1 forbidden");
    }
}
