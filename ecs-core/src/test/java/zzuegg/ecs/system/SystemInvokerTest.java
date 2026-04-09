package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.*;
import zzuegg.ecs.change.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SystemInvokerTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    static final AtomicReference<Position> lastPosition = new AtomicReference<>();

    static class TestSystems {
        @zzuegg.ecs.system.System
        void readOnly(@Read Position pos) {
            lastPosition.set(pos);
        }

        @zzuegg.ecs.system.System
        void writeComponent(@Read Velocity vel, @Write Mut<Position> pos) {
            pos.set(new Position(pos.get().x() + vel.dx(), pos.get().y() + vel.dy()));
        }
    }

    @Test
    void createInvokerForReadOnlySystem() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().endsWith(".readOnly")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);
        assertNotNull(invoker);
    }

    @Test
    void invokeReadOnlySystem() throws Throwable {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().endsWith(".readOnly")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);
        lastPosition.set(null);

        invoker.invoke(new Object[]{ new Position(3, 4) });

        assertEquals(new Position(3, 4), lastPosition.get());
    }

    @Test
    void invokeWriteSystem() throws Throwable {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(TestSystems.class, reg);
        var desc = descriptors.stream().filter(d -> d.name().endsWith(".writeComponent")).findFirst().orElseThrow();

        var invoker = SystemInvoker.create(desc);

        var tracker = new ChangeTracker(16);
        tracker.markAdded(0, 0);
        var posMut = new Mut<>(new Position(1, 2), 0, tracker, 1, false);

        invoker.invoke(new Object[]{ new Velocity(10, 20), posMut });

        assertEquals(new Position(11, 22), posMut.get());
    }
}
