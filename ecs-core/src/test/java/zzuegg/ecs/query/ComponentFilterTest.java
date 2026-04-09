package zzuegg.ecs.query;

import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.Mut;
import zzuegg.ecs.system.*;
import zzuegg.ecs.system.System;
import zzuegg.ecs.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFilterTest {

    record Health(int hp) {}
    record Position(float x, float y) {}
    record Armor(int defense) {}

    // === System-level @Where filters ===

    static final List<Integer> processedHp = Collections.synchronizedList(new ArrayList<>());

    static class FilteredSystem {
        @System
        void healLiving(@Read @Where("hp > 0") Health health) {
            processedHp.add(health.hp());
        }
    }

    static class MultiFilterSystem {
        @System
        void healTanky(@Read @Where("hp > 0") Health health, @Read @Where("defense >= 10") Armor armor) {
            processedHp.add(health.hp());
        }
    }

    @Test
    void systemSkipsEntitiesThatDontMatchFilter() {
        processedHp.clear();
        var world = World.builder()
            .addSystem(FilteredSystem.class)
            .useGeneratedProcessors(true)
            .build();

        world.spawn(new Health(100));
        world.spawn(new Health(0));   // should be skipped
        world.spawn(new Health(50));

        world.tick();

        assertEquals(2, processedHp.size());
        assertTrue(processedHp.contains(100));
        assertTrue(processedHp.contains(50));
        assertFalse(processedHp.contains(0));
    }

    @Test
    void multipleFiltersAllMustMatch() {
        processedHp.clear();
        var world = World.builder()
            .addSystem(MultiFilterSystem.class)
            .useGeneratedProcessors(true)
            .build();

        world.spawn(new Health(100), new Armor(50));   // matches both
        world.spawn(new Health(100), new Armor(5));    // armor too low
        world.spawn(new Health(0), new Armor(50));     // hp too low

        world.tick();

        assertEquals(1, processedHp.size());
        assertEquals(100, processedHp.getFirst());
    }

    // === World-level ad-hoc queries ===

    @Test
    void worldFindEntitiesWithFilter() {
        var world = World.builder().build();
        world.spawn(new Health(100), new Position(0, 0));
        world.spawn(new Health(0), new Position(1, 1));
        world.spawn(new Health(50), new Position(2, 2));

        var alive = world.findEntities(
            FieldFilter.of(Health.class, "hp").greaterThan(0),
            Health.class
        );
        assertEquals(2, alive.size());
    }

    @Test
    void worldFindEntitiesEquals() {
        var world = World.builder().build();
        world.spawn(new Health(100));
        world.spawn(new Health(50));
        world.spawn(new Health(100));

        var fullHp = world.findEntities(
            FieldFilter.of(Health.class, "hp").equalTo(100),
            Health.class
        );
        assertEquals(2, fullHp.size());
    }

    @Test
    void worldFindEntitiesLessThan() {
        var world = World.builder().build();
        world.spawn(new Health(10));
        world.spawn(new Health(50));
        world.spawn(new Health(90));

        var low = world.findEntities(
            FieldFilter.of(Health.class, "hp").lessThan(50),
            Health.class
        );
        assertEquals(1, low.size());
    }

    @Test
    void worldFindEntitiesAnd() {
        var world = World.builder().build();
        world.spawn(new Health(100), new Armor(50));
        world.spawn(new Health(100), new Armor(0));
        world.spawn(new Health(0), new Armor(50));

        var tanky = world.findEntities(
            FieldFilter.and(
                FieldFilter.of(Health.class, "hp").greaterThan(0),
                FieldFilter.of(Armor.class, "defense").greaterThan(0)
            ),
            Health.class, Armor.class
        );
        assertEquals(1, tanky.size());
    }

    @Test
    void worldFindEntitiesOr() {
        var world = World.builder().build();
        world.spawn(new Health(100), new Armor(0));
        world.spawn(new Health(0), new Armor(50));
        world.spawn(new Health(0), new Armor(0));

        var hasSomething = world.findEntities(
            FieldFilter.or(
                FieldFilter.of(Health.class, "hp").greaterThan(0),
                FieldFilter.of(Armor.class, "defense").greaterThan(0)
            ),
            Health.class, Armor.class
        );
        assertEquals(2, hasSomething.size());
    }

    @Test
    void worldFindEntitiesNoMatch() {
        var world = World.builder().build();
        world.spawn(new Health(0));
        world.spawn(new Health(0));

        var alive = world.findEntities(
            FieldFilter.of(Health.class, "hp").greaterThan(0),
            Health.class
        );
        assertTrue(alive.isEmpty());
    }

    @Test
    void worldFindEntitiesReturnsEntityIds() {
        var world = World.builder().build();
        var e1 = world.spawn(new Health(100));
        var e2 = world.spawn(new Health(0));

        var results = world.findEntities(
            FieldFilter.of(Health.class, "hp").greaterThan(0),
            Health.class
        );
        assertEquals(1, results.size());
        assertTrue(results.contains(e1));
        assertFalse(results.contains(e2));
    }

    // === Filters work without generated processors too ===

    @Test
    void systemFilterWorksWithReflectivePath() {
        processedHp.clear();
        var world = World.builder()
            .addSystem(FilteredSystem.class)
            .useGeneratedProcessors(false)
            .build();

        world.spawn(new Health(100));
        world.spawn(new Health(0));

        world.tick();

        assertEquals(1, processedHp.size());
        assertEquals(100, processedHp.getFirst());
    }
}
