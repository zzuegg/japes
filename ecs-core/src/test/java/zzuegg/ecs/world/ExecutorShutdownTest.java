package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.executor.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorShutdownTest {

    record Pos(float x) {}

    @Test
    void worldCloseShutdownsExecutor() {
        var world = World.builder()
            .executor(Executors.fixed(4))
            .build();

        world.spawn(new Pos(1));
        world.tick();

        world.close();
        // Should not hang — executor pool is shut down
    }

    @Test
    void worldCloseIsIdempotent() {
        var world = World.builder().build();
        world.close();
        world.close(); // should not throw
    }

    @Test
    void singleThreadedExecutorCloseIsNoOp() {
        var world = World.builder()
            .executor(Executors.singleThreaded())
            .build();
        assertDoesNotThrow(world::close);
    }
}
