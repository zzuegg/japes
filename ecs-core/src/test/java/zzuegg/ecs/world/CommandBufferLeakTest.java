package zzuegg.ecs.world;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.command.Commands;
import zzuegg.ecs.system.Read;
import zzuegg.ecs.system.System;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandBufferLeakTest {

    record Tag() {}

    static class SpawnerA {
        @System
        void tick(@Read Tag tag, Commands cmd) {
            // no-op; test only cares about plan wiring
        }
    }

    static class SpawnerB {
        @System
        void tick(@Read Tag tag, Commands cmd) {}
    }

    static class Noop {
        @System
        void tick(@Read Tag tag) {}
    }

    @SuppressWarnings("unchecked")
    private static List<Commands> peekBuffers(World world) throws Exception {
        Field f = World.class.getDeclaredField("allCommandBuffers");
        f.setAccessible(true);
        return (List<Commands>) f.get(world);
    }

    @Test
    void commandBuffersDoNotAccumulateAcrossRebuilds() throws Exception {
        var world = World.builder().addSystem(SpawnerA.class).build();
        world.spawn(new Tag());

        int initial = peekBuffers(world).size();

        // Each addSystem triggers rebuildSchedule. Before the fix, resolveServiceParam
        // was called twice per Commands param per system per rebuild, unbounded.
        for (int i = 0; i < 20; i++) {
            world.addSystem(Noop.class);
        }

        int afterRebuilds = peekBuffers(world).size();

        // With 1 Commands-using system and 21 Noop systems, there should be at most
        // one buffer per Commands-using system (here: 1). Allow a small constant slack
        // for the "two code paths share" case (expected: 1, max 2).
        assertTrue(afterRebuilds <= 2,
            "command buffer list leaked across rebuilds; initial=" + initial +
                " after=" + afterRebuilds);
    }

    @Test
    void commandsStillFunctionAfterManyRebuilds() {
        var world = World.builder()
            .addSystem(new Object() {
                @System
                void spawn(@Read Tag tag, Commands cmd) {
                    cmd.spawn(new Tag());
                }
            })
            .build();
        world.spawn(new Tag());

        for (int i = 0; i < 5; i++) {
            world.addSystem(Noop.class);
        }

        int before = world.entityCount();
        world.tick();
        int after = world.entityCount();

        // The queued command must still actually execute after repeated schedule rebuilds.
        assertTrue(after > before,
            "spawned command was not processed; before=" + before + " after=" + after);
    }
}
