package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the tier-1 bytecode generator for {@code @Exclusive}
 * service-only systems. The generator emits a hidden class whose
 * {@code run()} method unboxes a pre-resolved {@code Object[]} args
 * array and calls the user method via direct {@code invokevirtual},
 * replacing the reflective {@link SystemInvoker} spread-invoke path.
 */
class GeneratedExclusiveProcessorTest {

    public static final class Counter {
        public int value;
        public String tag = "";
    }

    public static class WorldOnlySystem {
        public static World CAPTURED;
        public static int calls;

        @System
        @Exclusive
        public void tick(World world) {
            assertNotNull(world);
            CAPTURED = world;
            calls++;
        }
    }

    public static class MixedParamsSystem {
        @System
        @Exclusive
        public void tick(World world, zzuegg.ecs.resource.ResMut<Counter> counter) {
            counter.get().value += 7;
            counter.get().tag = "ran";
        }
    }

    @Test
    void generatesRunnerForWorldOnlyExclusive() {
        WorldOnlySystem.CAPTURED = null;
        WorldOnlySystem.calls = 0;
        var world = World.builder()
            .addSystem(WorldOnlySystem.class)
            .build();

        var runner = world.generatedExclusiveRunner("WorldOnlySystem.tick");
        assertNotNull(runner,
            "tier-1 generator must produce a runner for a single-World @Exclusive system");

        runner.run();
        assertEquals(1, WorldOnlySystem.calls, "tier-1 runner must invoke the user method exactly once");
        assertSame(world, WorldOnlySystem.CAPTURED, "World arg must be routed through");
        runner.run();
        assertEquals(2, WorldOnlySystem.calls, "tier-1 runner must be re-callable across ticks");
    }

    @Test
    void generatesRunnerForMixedServiceParams() {
        var counter = new Counter();
        var world = World.builder()
            .addResource(counter)
            .addSystem(MixedParamsSystem.class)
            .build();

        var runner = world.generatedExclusiveRunner("MixedParamsSystem.tick");
        assertNotNull(runner,
            "tier-1 generator must handle World + ResMut @Exclusive signatures");

        runner.run();
        assertEquals(7, counter.value);
        assertEquals("ran", counter.tag);
    }

    @Test
    void tickRoutesExclusiveThroughTier1Runner() {
        // Full integration: ensure the behaviour contract of
        // @Exclusive is preserved when the scheduler routes through
        // the tier-1 path.
        WorldOnlySystem.calls = 0;
        var world = World.builder()
            .addSystem(WorldOnlySystem.class)
            .build();

        world.tick();
        world.tick();
        world.tick();

        assertEquals(3, WorldOnlySystem.calls);
    }
}
