package zzuegg.ecs.archetype;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.*;
import zzuegg.ecs.entity.Entity;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ArchetypeTest {

    record Position(float x, float y) {}
    record Velocity(float dx, float dy) {}

    private ComponentRegistry registry() {
        var reg = new ComponentRegistry();
        reg.register(Position.class);
        reg.register(Velocity.class);
        return reg;
    }

    @Test
    void addEntityReturnsLocation() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var velId = reg.getOrRegister(Velocity.class);
        var archId = ArchetypeId.of(Set.of(posId, velId));
        var arch = new Archetype(archId, reg, 4, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        var loc = arch.add(Entity.of(0, 0));

        assertEquals(0, loc.chunkIndex());
        assertEquals(0, loc.slotIndex());
    }

    @Test
    void addCreatesNewChunkWhenFull() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        var loc = arch.add(Entity.of(2, 0));

        assertEquals(1, loc.chunkIndex());
        assertEquals(0, loc.slotIndex());
    }

    @Test
    void setAndGetComponent() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        var loc = arch.add(Entity.of(0, 0));
        arch.set(posId, loc, new Position(1, 2));

        assertEquals(new Position(1, 2), arch.get(posId, loc));
    }

    @Test
    void removeEntityAndSwapRemove() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        arch.add(Entity.of(0, 0));
        arch.set(posId, new EntityLocation(arch, 0, 0), new Position(0, 0));
        arch.add(Entity.of(1, 0));
        arch.set(posId, new EntityLocation(arch, 0, 1), new Position(1, 1));

        var swapped = arch.remove(new EntityLocation(arch, 0, 0));

        assertEquals(1, arch.entityCount());
        assertTrue(swapped.isPresent());
        assertEquals(Entity.of(1, 0), swapped.get());
    }

    @Test
    void removeLastEntityReturnsEmpty() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 16, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        arch.add(Entity.of(0, 0));
        var swapped = arch.remove(new EntityLocation(arch, 0, 0));
        assertTrue(swapped.isEmpty());
    }

    @Test
    void entityCountAcrossChunks() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        arch.add(Entity.of(2, 0));

        assertEquals(3, arch.entityCount());
    }

    @Test
    void chunkCount() {
        var reg = registry();
        var posId = reg.getOrRegister(Position.class);
        var archId = ArchetypeId.of(Set.of(posId));
        var arch = new Archetype(archId, reg, 2, zzuegg.ecs.storage.ComponentStorage.defaultFactory(), java.util.Set.of());

        arch.add(Entity.of(0, 0));
        arch.add(Entity.of(1, 0));
        assertEquals(1, arch.chunkCount());

        arch.add(Entity.of(2, 0));
        assertEquals(2, arch.chunkCount());
    }
}
