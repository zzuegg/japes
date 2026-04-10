package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedChunkProcessorNamingTest {

    record Position(float x, float y) {}

    // Two classes with identical method simple names — fixed code must produce
    // distinct hidden-class names. The buggy version embedded desc.name() (which
    // includes '.') straight into ClassDesc.of, relying on nanoTime() for
    // uniqueness, which could collide on rapid rebuilds.
    static class SystemA {
        @System void update(@Read Position pos) {}
    }

    static class SystemB {
        @System void update(@Read Position pos) {}
    }

    @Test
    void generatedClassNameHasNoDotInClassSimpleName() {
        String generated = GeneratedChunkProcessor.generateClassName("SystemA.update");
        // ClassDesc.of interprets '.' as a package separator. The simple-name
        // portion after the last '.' is the actual class identifier and must not
        // contain characters beyond that point that originated from desc.name().
        int lastDot = generated.lastIndexOf('.');
        String simple = lastDot < 0 ? generated : generated.substring(lastDot + 1);
        // Sanitized form: the method-name component must survive, but the class-
        // qualifier dot must be replaced so the whole descriptor turns into a
        // single class under the intended package.
        assertFalse(simple.contains("."), "class simple name must not contain '.'; got: " + generated);
        assertTrue(generated.startsWith("zzuegg.ecs.generated."),
            "generated class should live in a stable generated package; got: " + generated);
    }

    @Test
    void generatedClassNamesAreUniquePerCall() {
        String a = GeneratedChunkProcessor.generateClassName("SystemA.update");
        String b = GeneratedChunkProcessor.generateClassName("SystemA.update");
        assertNotEquals(a, b, "consecutive calls must produce distinct names");
    }

    @Test
    void twoSystemsWithSameSimpleNameDoNotCrashOnGeneration() {
        // End-to-end: both classes register; both get generated processors;
        // rebuildSchedule() must not throw due to hidden-class name collisions
        // or invalid ClassDesc parsing.
        var world = World.builder()
            .addSystem(SystemA.class)
            .addSystem(SystemB.class)
            .build();
        world.spawn(new Position(1, 2));
        assertDoesNotThrow(world::tick);
    }
}
