package zzuegg.ecs.system;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.ComponentRegistry;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.event.EventReader;
import zzuegg.ecs.event.EventWriter;
import zzuegg.ecs.query.AccessType;
import zzuegg.ecs.resource.Res;
import zzuegg.ecs.resource.ResMut;
import zzuegg.ecs.world.World;

import static org.junit.jupiter.api.Assertions.*;

class SystemParserTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}
    record DeltaTime(float dt) {}
    record HitEvent(int damage) {}

    static class SimpleSystems {
        @zzuegg.ecs.system.System
        void move(@Read Velocity vel, @Write Mut<Position> pos) {}
    }

    static class SystemWithResource {
        @zzuegg.ecs.system.System
        void tick(Res<DeltaTime> dt) {}
    }

    static class SystemWithMutResource {
        @zzuegg.ecs.system.System
        void tick(ResMut<DeltaTime> dt) {}
    }

    static class SystemWithEvents {
        @zzuegg.ecs.system.System
        void handle(EventReader<HitEvent> reader, EventWriter<HitEvent> writer) {}
    }

    static class SystemWithFilters {
        @zzuegg.ecs.system.System
        @With(Position.class)
        @Without(Velocity.class)
        void filtered(@Read Position pos) {}
    }

    static class SystemWithOrdering {
        @zzuegg.ecs.system.System(stage = "PostUpdate", after = "physics", before = "render")
        void ordered(@Read Position pos) {}
    }

    static class ExclusiveSystem {
        @zzuegg.ecs.system.System
        @Exclusive
        void exclusive(World world) {}
    }

    static class SystemWithCommands {
        @zzuegg.ecs.system.System
        void spawner(@Read Position pos, zzuegg.ecs.command.Commands cmd) {}
    }

    static class SystemWithLocal {
        @zzuegg.ecs.system.System
        void counter(Local<Integer> count) {}
    }

    @SystemSet(name = "physics", stage = "Update", after = "input")
    static class PhysicsSet {
        @zzuegg.ecs.system.System
        void gravity(@Write Mut<Velocity> vel) {}

        @zzuegg.ecs.system.System(after = "gravity")
        void integrate(@Read Velocity vel, @Write Mut<Position> pos) {}
    }

    @Test
    void parsesComponentAccess() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(SimpleSystems.class, reg);

        assertEquals(1, descriptors.size());
        var desc = descriptors.getFirst();
        assertEquals("move", desc.name());
        assertEquals(2, desc.componentAccesses().size());

        var reads = desc.componentAccesses().stream()
            .filter(a -> a.accessType() == AccessType.READ).toList();
        var writes = desc.componentAccesses().stream()
            .filter(a -> a.accessType() == AccessType.WRITE).toList();

        assertEquals(1, reads.size());
        assertEquals(Velocity.class, reads.getFirst().type());
        assertEquals(1, writes.size());
        assertEquals(Position.class, writes.getFirst().type());
    }

    @Test
    void parsesResourceAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithResource.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.resourceReads().size());
        assertTrue(desc.resourceWrites().isEmpty());
    }

    @Test
    void parsesMutableResourceAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithMutResource.class, reg);
        var desc = descriptors.getFirst();
        assertTrue(desc.resourceReads().isEmpty());
        assertEquals(1, desc.resourceWrites().size());
    }

    @Test
    void parsesEventAccess() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithEvents.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.eventReads().size());
        assertEquals(1, desc.eventWrites().size());
    }

    @Test
    void parsesFilters() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(SystemWithFilters.class, reg);
        var desc = descriptors.getFirst();
        assertEquals(1, desc.withFilters().size());
        assertEquals(1, desc.withoutFilters().size());
    }

    @Test
    void parsesOrdering() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(SystemWithOrdering.class, reg);
        var desc = descriptors.getFirst();
        assertEquals("PostUpdate", desc.stage());
        assertTrue(desc.after().contains("physics"));
        assertTrue(desc.before().contains("render"));
    }

    @Test
    void parsesExclusive() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(ExclusiveSystem.class, reg);
        assertTrue(descriptors.getFirst().isExclusive());
    }

    @Test
    void parsesCommands() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        var descriptors = SystemParser.parse(SystemWithCommands.class, reg);
        assertTrue(descriptors.getFirst().usesCommands());
    }

    @Test
    void parsesLocal() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(SystemWithLocal.class, reg);
        assertTrue(descriptors.getFirst().usesLocal());
    }

    @Test
    void parsesSystemSet() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        var descriptors = SystemParser.parse(PhysicsSet.class, reg);

        assertEquals(2, descriptors.size());
        for (var desc : descriptors) {
            assertEquals("Update", desc.stage());
        }
        var gravity = descriptors.stream().filter(d -> d.name().equals("gravity")).findFirst().orElseThrow();
        assertTrue(gravity.after().contains("input"));
    }

    @Test
    void classWithNoSystemMethodsReturnsEmpty() {
        var reg = new ComponentRegistry();
        var descriptors = SystemParser.parse(String.class, reg);
        assertTrue(descriptors.isEmpty());
    }
}
