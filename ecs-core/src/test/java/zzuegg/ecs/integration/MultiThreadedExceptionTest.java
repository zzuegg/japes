package zzuegg.ecs.integration;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.executor.Executors;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadedExceptionTest {

    record Position(float x, float y) {}

    static class ExplodingSystem {
        @System
        void boom(@Read Position pos) {
            throw new IllegalStateException("boom");
        }
    }

    static class NoopPeer {
        @System
        void peer(@Read Position pos) {
            // independent work; exists so ExplodingSystem and this are both
            // "ready" in the same wave, forcing the phaser path in
            // MultiThreadedExecutor instead of the 1-ready fast path.
        }
    }

    @Test
    void failingSystemInMultiThreadedTickSurfaces() {
        var world = World.builder()
            .executor(Executors.fixed(2))
            .addSystem(ExplodingSystem.class)
            .addSystem(NoopPeer.class) // ensures two ready systems → phaser path
            .build();
        world.spawn(new Position(1, 2));

        var ex = assertThrows(RuntimeException.class, world::tick,
            "a system that throws under the multi-threaded executor must surface from world.tick()");

        // The original cause should be reachable via the chain.
        Throwable root = ex;
        boolean found = false;
        while (root != null) {
            if (root.getMessage() != null && root.getMessage().contains("boom")) { found = true; break; }
            root = root.getCause();
        }
        assertTrue(found, "expected 'boom' in exception chain; got: " + ex);

        world.close();
    }

    @Test
    void singleThreadedPathStillSurfacesException() {
        // Sanity check: the single-threaded baseline also propagates, so the
        // MT test above isn't just validating trivial behaviour.
        var world = World.builder()
            .addSystem(ExplodingSystem.class)
            .build();
        world.spawn(new Position(1, 2));

        assertThrows(RuntimeException.class, world::tick);
        world.close();
    }
}
