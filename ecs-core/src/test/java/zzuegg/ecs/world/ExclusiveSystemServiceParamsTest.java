package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.system.System;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract that an {@code @Exclusive} system is allowed
 * extra service parameters beyond {@code World}. Previously the
 * exclusive branch in {@code World.executeSystem} hard-coded a
 * single-element argument array containing only the world, so
 * any exclusive system declaring {@code Res}, {@code ResMut},
 * {@code Commands}, etc. blew up at invocation time with
 * "array is not of length N".
 *
 * <p>The fix routes exclusive invocations through the same
 * {@code plan.args()} array that non-exclusive systems use.
 * The plan build already resolves every service slot, including
 * the {@code World} slot via {@code resolveServiceParam}.
 */
class ExclusiveSystemServiceParamsTest {

    public static final class Counter {
        public int value;
    }

    public static final class Greeting {
        public String text = "hi";
    }

    public static class Systems {
        public static int worldOnlyCalls;
        public static int resMutCalls;
        public static int mixedCalls;

        /** Single-parameter @Exclusive — the path that always worked. */
        @System(stage = "First")
        @Exclusive
        public void worldOnly(World world) {
            assertNotNull(world);
            worldOnlyCalls++;
        }

        /** @Exclusive with a ResMut<T> — new. */
        @System(stage = "PreUpdate")
        @Exclusive
        public void withResMut(World world, ResMut<Counter> counter) {
            assertNotNull(world);
            counter.get().value++;
            resMutCalls++;
        }

        /**
         * @Exclusive with a mix of params. Exercises the full
         * service-arg resolution path: World, Res, ResMut, Commands.
         */
        @System(stage = "Update")
        @Exclusive
        public void mixed(
                World world,
                Res<Greeting> greeting,
                ResMut<Counter> counter,
                Commands cmds
        ) {
            assertNotNull(world);
            assertEquals("hi", greeting.get().text);
            counter.get().value += 10;
            // Commands is resolved but we don't actually need to
            // enqueue anything — just prove it can be injected.
            assertNotNull(cmds);
            mixedCalls++;
        }
    }

    @Test
    void exclusiveSystemsReceiveTheirFullServiceArgs() {
        Systems.worldOnlyCalls = 0;
        Systems.resMutCalls = 0;
        Systems.mixedCalls = 0;

        var counter = new Counter();
        var greeting = new Greeting();

        var world = World.builder()
            .addResource(counter)
            .addResource(greeting)
            .addSystem(Systems.class)
            .build();

        world.tick();

        assertEquals(1, Systems.worldOnlyCalls);
        assertEquals(1, Systems.resMutCalls);
        assertEquals(1, Systems.mixedCalls);
        assertEquals(11, counter.value,
            "resMut (+1) and mixed (+10) must each have mutated the shared counter");
    }
}
