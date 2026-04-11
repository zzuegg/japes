use bevy_ecs::prelude::*;
use criterion::{criterion_group, criterion_main, Criterion, BenchmarkId};

// Same components as the Java ECS benchmarks
#[derive(Component, Clone, Copy)]
struct Position {
    x: f32,
    y: f32,
    z: f32,
}

#[derive(Component, Clone, Copy)]
struct Velocity {
    dx: f32,
    dy: f32,
    dz: f32,
}

#[derive(Component, Clone, Copy)]
struct Mass {
    m: f32,
}

#[derive(Resource)]
struct DeltaTime {
    dt: f32,
}

// === Iteration Systems (same as Java benchmarks) ===

fn iterate_single_component(query: Query<&Position>) {
    for pos in &query {
        std::hint::black_box(pos);
    }
}

fn iterate_two_components(query: Query<(&Position, &Velocity)>) {
    for (pos, vel) in &query {
        std::hint::black_box((pos, vel));
    }
}

fn iterate_with_write(mut query: Query<(&Velocity, &mut Position)>) {
    for (vel, mut pos) in &mut query {
        pos.x += vel.dx;
        pos.y += vel.dy;
        pos.z += vel.dz;
    }
}

// N-body integrate system
fn nbody_integrate(dt: Res<DeltaTime>, mut query: Query<(&Velocity, &mut Position)>) {
    for (vel, mut pos) in &mut query {
        pos.x += vel.dx * dt.dt;
        pos.y += vel.dy * dt.dt;
        pos.z += vel.dz * dt.dt;
    }
}

// === Benchmark Setup ===

fn setup_iteration_world(entity_count: usize) -> World {
    let mut world = World::new();
    for i in 0..entity_count {
        let f = i as f32;
        world.spawn((
            Position { x: f, y: f, z: f },
            Velocity { dx: 1.0, dy: 1.0, dz: 1.0 },
        ));
    }
    world
}

fn setup_single_component_world(entity_count: usize) -> World {
    let mut world = World::new();
    for i in 0..entity_count {
        let f = i as f32;
        world.spawn(Position { x: f, y: f, z: f });
    }
    world
}

fn setup_nbody_world(body_count: usize) -> World {
    let mut world = World::new();
    world.insert_resource(DeltaTime { dt: 0.001 });
    for i in 0..body_count {
        let angle = 2.0 * std::f32::consts::PI * (i as f32) / (body_count as f32);
        world.spawn((
            Position {
                x: angle.cos() * 100.0,
                y: angle.sin() * 100.0,
                z: 0.0,
            },
            Velocity {
                dx: -angle.sin() * 10.0,
                dy: angle.cos() * 10.0,
                dz: 0.0,
            },
            Mass { m: 1.0 },
        ));
    }
    world
}

// === Criterion Benchmarks ===

fn iteration_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("iteration");

    for entity_count in [1000, 10000, 100000] {
        // Single component read
        group.bench_with_input(
            BenchmarkId::new("single_read", entity_count),
            &entity_count,
            |b, &n| {
                let mut world = setup_single_component_world(n);
                let mut schedule = Schedule::default();
                schedule.add_systems(iterate_single_component);
                schedule.run(&mut world); // warmup
                b.iter(|| {
                    schedule.run(&mut world);
                });
            },
        );

        // Two component read
        group.bench_with_input(
            BenchmarkId::new("two_read", entity_count),
            &entity_count,
            |b, &n| {
                let mut world = setup_iteration_world(n);
                let mut schedule = Schedule::default();
                schedule.add_systems(iterate_two_components);
                schedule.run(&mut world);
                b.iter(|| {
                    schedule.run(&mut world);
                });
            },
        );

        // Read + Write
        group.bench_with_input(
            BenchmarkId::new("read_write", entity_count),
            &entity_count,
            |b, &n| {
                let mut world = setup_iteration_world(n);
                let mut schedule = Schedule::default();
                schedule.add_systems(iterate_with_write);
                schedule.run(&mut world);
                b.iter(|| {
                    schedule.run(&mut world);
                });
            },
        );
    }

    group.finish();
}

fn nbody_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("nbody");

    for body_count in [1000, 10000] {
        group.bench_with_input(
            BenchmarkId::new("one_tick", body_count),
            &body_count,
            |b, &n| {
                let mut world = setup_nbody_world(n);
                let mut schedule = Schedule::default();
                schedule.add_systems(nbody_integrate);
                schedule.run(&mut world);
                b.iter(|| {
                    schedule.run(&mut world);
                });
            },
        );

        group.bench_with_input(
            BenchmarkId::new("ten_ticks", body_count),
            &body_count,
            |b, &n| {
                let mut world = setup_nbody_world(n);
                let mut schedule = Schedule::default();
                schedule.add_systems(nbody_integrate);
                schedule.run(&mut world);
                b.iter(|| {
                    for _ in 0..10 {
                        schedule.run(&mut world);
                    }
                });
            },
        );
    }

    group.finish();
}

fn entity_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("entity");

    group.bench_function("spawn_1k", |b| {
        b.iter(|| {
            let mut world = World::new();
            for i in 0..1000 {
                let f = i as f32;
                world.spawn(Position { x: f, y: f, z: f });
            }
        });
    });

    group.bench_function("spawn_100k", |b| {
        b.iter(|| {
            let mut world = World::new();
            for i in 0..100_000 {
                let f = i as f32;
                world.spawn(Position { x: f, y: f, z: f });
            }
        });
    });

    group.bench_function("despawn_1k", |b| {
        b.iter_with_setup(
            || {
                let mut world = World::new();
                let entities: Vec<Entity> = (0..1000)
                    .map(|i| {
                        let f = i as f32;
                        world.spawn(Position { x: f, y: f, z: f }).id()
                    })
                    .collect();
                (world, entities)
            },
            |(mut world, entities)| {
                for entity in entities {
                    world.despawn(entity);
                }
            },
        );
    });

    group.finish();
}

// === Change detection + RemovedComponents benchmarks ===

fn move_all(mut q: Query<(&Velocity, &mut Position)>) {
    for (v, mut p) in &mut q {
        p.x += v.dx;
        p.y += v.dy;
        p.z += v.dz;
    }
}

fn observe_changed(q: Query<&Position, Changed<Position>>, mut count: Local<u64>) {
    for p in &q {
        *count += 1;
        std::hint::black_box(p);
    }
    std::hint::black_box(*count);
}

fn drain_removed(mut removed: RemovedComponents<Position>, mut count: Local<u64>) {
    for e in removed.read() {
        *count += 1;
        std::hint::black_box(e);
    }
    std::hint::black_box(*count);
}

fn change_detection_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("change_detection");

    // Equivalent to ChangeDetectionBenchmark.changedFilterAllEntitiesDirty:
    // every tick MoveAll writes every Position, so the Changed<Position>
    // observer sees every entity as changed.
    group.bench_with_input(
        BenchmarkId::new("changed_all_dirty", 10000),
        &10000usize,
        |b, &n| {
            let mut world = World::new();
            for i in 0..n {
                let f = i as f32;
                world.spawn((
                    Position { x: f, y: f, z: f },
                    Velocity { dx: 1.0, dy: 1.0, dz: 1.0 },
                ));
            }
            let mut schedule = Schedule::default();
            schedule.add_systems((move_all, observe_changed).chain());
            schedule.run(&mut world); // warmup
            b.iter(|| {
                schedule.run(&mut world);
            });
        },
    );

    // Equivalent to ChangeDetectionBenchmark.removedComponentsDrainAfterBulkDespawn:
    // despawn N entities then run the drain observer in a schedule tick.
    // We include the respawn in the measured section so both sides are symmetric.
    group.bench_with_input(
        BenchmarkId::new("removed_drain_bulk_despawn", 10000),
        &10000usize,
        |b, &n| {
            b.iter_with_setup(
                || {
                    let mut world = World::new();
                    let mut schedule = Schedule::default();
                    schedule.add_systems(drain_removed);
                    let entities: Vec<Entity> = (0..n)
                        .map(|i| {
                            let f = i as f32;
                            world.spawn(Position { x: f, y: f, z: f }).id()
                        })
                        .collect();
                    schedule.run(&mut world); // prime past the spawn observation
                    (world, schedule, entities)
                },
                |(mut world, mut schedule, entities)| {
                    for e in entities {
                        world.despawn(e);
                    }
                    schedule.run(&mut world);
                },
            );
        },
    );

    group.finish();
}

// === Cross-library "particle system tick" scenario ===
//
// Matches ParticleScenarioBenchmark (ecs-benchmark) and
// ZayEsParticleScenarioBenchmark (ecs-benchmark-zayes). Per tick:
//   1. move     — Position += Velocity
//   2. damage   — Health.hp -= 1
//   3. reap     — despawn entities with hp <= 0
//   4. stats    — count deaths (RemovedComponents<Health>) and alive
//   5. respawn  — keep total entity count at N

#[derive(Component, Clone, Copy)]
struct Lifetime {
    ttl: i32,
}

#[derive(Component, Clone, Copy)]
struct Health {
    hp: i32,
}

#[derive(Resource, Default)]
struct TotalDeaths(u64);

#[derive(Resource, Default)]
struct AliveCount(u64);

fn scenario_move(mut q: Query<(&Velocity, &mut Position)>) {
    for (v, mut p) in &mut q {
        p.x += v.dx;
        p.y += v.dy;
        p.z += v.dz;
    }
}

fn scenario_damage(mut q: Query<&mut Health>) {
    for mut h in &mut q {
        h.hp -= 1;
    }
}

fn scenario_reap(mut commands: Commands, q: Query<(Entity, &Health)>) {
    for (e, h) in &q {
        if h.hp <= 0 {
            commands.entity(e).despawn();
        }
    }
}

fn scenario_stats(
    mut removed: RemovedComponents<Health>,
    alive_q: Query<&Lifetime>,
    mut total: ResMut<TotalDeaths>,
    mut alive: ResMut<AliveCount>,
) {
    let mut deaths = 0u64;
    for _ in removed.read() { deaths += 1; }
    total.0 += deaths;
    alive.0 = alive_q.iter().filter(|l| l.ttl > 0).count() as u64;
}

fn scenario_respawn(mut commands: Commands, q: Query<&Position>) {
    const TARGET: usize = 10_000;
    let current = q.iter().count();
    for _ in current..TARGET {
        commands.spawn((
            Position { x: 0.0, y: 0.0, z: 0.0 },
            Velocity { dx: 1.0, dy: 1.0, dz: 1.0 },
            Lifetime { ttl: 1000 },
            Health { hp: 100 },
        ));
    }
}

fn scenario_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("scenario");

    group.bench_with_input(
        BenchmarkId::new("particle_tick", 10000),
        &10000usize,
        |b, &n| {
            let mut world = World::new();
            world.insert_resource(TotalDeaths::default());
            world.insert_resource(AliveCount::default());
            for i in 0..n {
                let f = i as f32;
                let start_hp = 1 + (i as i32) % 100;
                world.spawn((
                    Position { x: f, y: f, z: f },
                    Velocity { dx: 1.0, dy: 1.0, dz: 1.0 },
                    Lifetime { ttl: 1000 },
                    Health { hp: start_hp },
                ));
            }
            let mut schedule = Schedule::default();
            schedule.add_systems((
                scenario_move,
                scenario_damage,
                scenario_reap,
                scenario_stats,
                scenario_respawn,
            ).chain());
            // Prime one tick so the baseline is consistent.
            schedule.run(&mut world);
            b.iter(|| {
                schedule.run(&mut world);
            });
        },
    );

    group.finish();
}

// === Sparse-delta scenario ===
//
// Matches SparseDeltaBenchmark (ecs-benchmark) and
// ZayEsSparseDeltaBenchmark (ecs-benchmark-zayes). 10k entities exist, but
// only 100 are touched per tick. The observer reacts only to entities whose
// Health component actually changed — Changed<Health> in Bevy's idiom.

#[derive(Component)]
struct SparseHealth {
    hp: i32,
}

#[derive(Resource, Default)]
struct SparseObserved(u64);

fn observe_changed_sparse_health(
    q: Query<&SparseHealth, Changed<SparseHealth>>,
    mut counter: ResMut<SparseObserved>,
) {
    for h in &q {
        counter.0 += 1;
        std::hint::black_box(h);
    }
}

fn sparse_delta_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("sparse_delta");

    group.bench_with_input(
        BenchmarkId::new("observe_changed_health", 10000),
        &10000usize,
        |b, &n| {
            const BATCH: usize = 100;
            let mut world = World::new();
            world.insert_resource(SparseObserved::default());
            let handles: Vec<Entity> = (0..n)
                .map(|_| world.spawn(SparseHealth { hp: 1000 }).id())
                .collect();
            let mut schedule = Schedule::default();
            schedule.add_systems(observe_changed_sparse_health);
            schedule.run(&mut world); // prime

            let mut cursor = 0usize;
            b.iter(|| {
                // Driver: damage 100 entities via rotating cursor.
                for _ in 0..BATCH {
                    let e = handles[cursor];
                    cursor = (cursor + 1) % handles.len();
                    if let Some(mut h) = world.get_mut::<SparseHealth>(e) {
                        h.hp -= 1;
                    }
                }
                schedule.run(&mut world);
            });
        },
    );

    group.finish();
}

// === Realistic multi-observer tick ===
//
// Matches RealisticTickBenchmark (ecs-benchmark), plus its Dominion and
// Artemis counterparts. 10 000 entities with {Position, Velocity, Health,
// Mana}; per tick, 100 entities each have Position / Health / Mana
// mutated via three rotating cursors (different offsets so the slices
// don't overlap); three Changed<T> observers react to the mutations.
//
// japes and Dominion/Artemis had counterparts from the start but the
// Bevy reference was missing — added after a cross-library audit caught
// the gap. Written in the idiomatic Bevy shape: Changed<T> in the
// observer query is the library's native primitive for this exact
// workload.

#[derive(Component, Clone, Copy)]
struct RtHealth { hp: i32 }

#[derive(Component, Clone, Copy)]
struct RtMana { points: i32 }

// Position and Velocity are reused from the iteration benchmarks above.

#[derive(Resource, Default)]
struct RtStats { sum_x: i64, sum_hp: i64, sum_mana: i64 }

fn rt_observe_position(q: Query<&Position, Changed<Position>>, mut stats: ResMut<RtStats>) {
    for p in &q {
        stats.sum_x += p.x as i64;
    }
}

fn rt_observe_health(q: Query<&RtHealth, Changed<RtHealth>>, mut stats: ResMut<RtStats>) {
    for h in &q {
        stats.sum_hp += h.hp as i64;
    }
}

fn rt_observe_mana(q: Query<&RtMana, Changed<RtMana>>, mut stats: ResMut<RtStats>) {
    for m in &q {
        stats.sum_mana += m.points as i64;
    }
}

fn realistic_tick_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("realistic_tick");

    const BATCH: usize = 100;

    // Two entity counts so the scaling story is measurable: 10k is the
    // same knob japes/Dominion/Artemis/Zay-ES use, 100k pressure-tests
    // whether the cost of the Changed<T> observer scales with total
    // entity count (Bevy's archetype-scan model) or with the dirty
    // count (japes's dirty-slot-list model).
    for &n in &[10000usize, 100000usize] {
        group.bench_with_input(
            BenchmarkId::new("tick", n),
            &n,
            |b, &n| {
                let mut world = World::new();
                world.insert_resource(RtStats::default());
                let handles: Vec<Entity> = (0..n)
                    .map(|i| {
                        let f = i as f32;
                        world.spawn((
                            Position { x: f, y: f, z: f },
                            Velocity { dx: 1.0, dy: 1.0, dz: 1.0 },
                            RtHealth { hp: 1_000_000 },
                            RtMana { points: 0 },
                        )).id()
                    })
                    .collect();

                let mut schedule = Schedule::default();
                schedule.add_systems((rt_observe_position, rt_observe_health, rt_observe_mana));
                // Prime once so the Changed<T> trackers have a baseline.
                schedule.run(&mut world);

                // Three rotating cursors offset so the Position / Health / Mana
                // slices don't overlap each tick — same shape as the japes
                // RealisticTickBenchmark driver.
                let mut pos_cursor: usize = 0;
                let mut hp_cursor: usize = BATCH;
                let mut mana_cursor: usize = 2 * BATCH;

                b.iter(|| {
                    // Driver: 300 sparse mutations, rotating cursors.
                    for _ in 0..BATCH {
                        let e = handles[pos_cursor];
                        pos_cursor = (pos_cursor + 1) % handles.len();
                        if let Some(mut p) = world.get_mut::<Position>(e) {
                            p.x += 1.0;
                        }
                    }
                    for _ in 0..BATCH {
                        let e = handles[hp_cursor];
                        hp_cursor = (hp_cursor + 1) % handles.len();
                        if let Some(mut h) = world.get_mut::<RtHealth>(e) {
                            h.hp -= 1;
                        }
                    }
                    for _ in 0..BATCH {
                        let e = handles[mana_cursor];
                        mana_cursor = (mana_cursor + 1) % handles.len();
                        if let Some(mut m) = world.get_mut::<RtMana>(e) {
                            m.points += 1;
                        }
                    }
                    // Observer tick — runs the three Changed<T> observers.
                    schedule.run(&mut world);
                    // DCE safety net: force the compiler to treat the
                    // RtStats accumulator fields as observed outside the
                    // closure body. Without this, there's nothing stopping
                    // rustc / llvm from deleting the observer bodies if it
                    // can prove no one ever reads sum_x / sum_hp / sum_mana.
                    // The japes benchmark does the equivalent via
                    // bh.consume(stats.sumX) at the end of tick().
                    let stats = world.resource::<RtStats>();
                    std::hint::black_box(stats.sum_x);
                    std::hint::black_box(stats.sum_hp);
                    std::hint::black_box(stats.sum_mana);
                });
            },
        );
    }

    group.finish();
}

// === Predator / Prey scenario — Bevy reference for the japes relations benchmark ===
//
// Matches PredatorPreyBenchmark (ecs-benchmark). The japes version uses the
// first-class relation API (`@Pair(Hunting.class)` + `PairReader`). Bevy 0.15
// has no generic relations primitive — the idiomatic equivalent is to store
// the target entity in a component field (`Hunting { target }`) and query
// for it directly. That loses the reverse-index that japes gets for free:
// a prey asking "who is hunting me?" has to scan every predator's Hunting
// component, which is O(predators) per prey = O(predators × prey) per tick.
//
// This is *exactly* the workload the relation system is designed to obsolete,
// so the comparison is the one that matters.
//
// Systems (chained):
//   1. pp_movement       — integrate Position += Velocity
//   2. pp_acquire_hunt   — idle predators pick a random prey; predators
//                          whose Hunting target has died drop the component
//   3. pp_pursuit        — hunters steer toward their target's position
//   4. pp_awareness      — O(pred × prey) reverse scan counting attackers
//   5. pp_resolve_catches— despawn prey within catch distance
//   6. pp_respawn_prey   — top up to the baseline count
//   7. pp_observe_catches— drain RemovedComponents<PreyMarker>

#[derive(Component, Clone, Copy)]
struct PpPosition { x: f32, y: f32 }

#[derive(Component, Clone, Copy)]
struct PpVelocity { dx: f32, dy: f32 }

#[derive(Component)]
struct PpPredator;

#[derive(Component)]
struct PpPrey;

/// The "relation" expressed the only way Bevy 0.15 offers out of the box —
/// an entity reference stored in a component. A predator either has this
/// component (active hunt) or doesn't (idle).
#[derive(Component, Clone, Copy)]
struct PpHunting {
    target: Entity,
}

#[derive(Resource, Default)]
struct PpPreyRoster { alive: Vec<Entity> }

#[derive(Resource)]
struct PpConfig {
    catch_distance_sq: f32,
    arena_size: f32,
    rng_state: u64,
    baseline_prey: usize,
}

impl PpConfig {
    /// PCG-style 64-bit LCG. Deterministic and dependency-free — same
    /// shape as the `Random(1234)` seed in the japes Java benchmark.
    fn next_u32(&mut self) -> u32 {
        self.rng_state = self
            .rng_state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (self.rng_state >> 33) as u32
    }
    fn next_f32(&mut self) -> f32 {
        (self.next_u32() as f32) / (u32::MAX as f32)
    }
    fn next_range(&mut self, bound: usize) -> usize {
        (self.next_u32() as usize) % bound.max(1)
    }
}

#[derive(Resource, Default)]
struct PpCounters {
    pursuit_walks: u64,
    with_target_walks: u64,
    catches: u64,
}

fn pp_movement(mut q: Query<(&PpVelocity, &mut PpPosition)>) {
    for (v, mut p) in &mut q {
        p.x += v.dx;
        p.y += v.dy;
    }
}

/// Idle predators acquire a target; predators whose target has died drop
/// their Hunting component. The "dead target" case is the manual equivalent
/// of the japes `RELEASE_TARGET` cleanup policy — Bevy doesn't do this
/// automatically on component-stored entity references, so it's on the user.
fn pp_acquire_hunt(
    mut commands: Commands,
    idle: Query<Entity, (With<PpPredator>, Without<PpHunting>)>,
    hunting: Query<(Entity, &PpHunting), With<PpPredator>>,
    prey_check: Query<(), With<PpPrey>>,
    roster: Res<PpPreyRoster>,
    mut config: ResMut<PpConfig>,
) {
    // Drop stale Hunting components whose target died last tick (or earlier).
    for (predator, hunt) in &hunting {
        if prey_check.get(hunt.target).is_err() {
            commands.entity(predator).remove::<PpHunting>();
        }
    }

    let alive = &roster.alive;
    if alive.is_empty() {
        return;
    }
    for predator in &idle {
        let idx = config.next_range(alive.len());
        let target = alive[idx];
        commands.entity(predator).insert(PpHunting { target });
    }
}

/// Forward walk equivalent: `reader.fromSource(self)` collapsed into a
/// direct `Hunting` component read. One pair per predator, no reverse
/// index. Reads the target's Position via a secondary query.
fn pp_pursuit(
    mut hunters: Query<(&PpPosition, &mut PpVelocity, &PpHunting), With<PpPredator>>,
    positions: Query<&PpPosition, Without<PpPredator>>,
    mut counters: ResMut<PpCounters>,
) {
    let mut walks: u64 = 0;
    for (self_pos, mut self_vel, hunt) in &mut hunters {
        if let Ok(target_pos) = positions.get(hunt.target) {
            let dx = target_pos.x - self_pos.x;
            let dy = target_pos.y - self_pos.y;
            let mag = (dx * dx + dy * dy).sqrt();
            if mag > 1e-4 {
                self_vel.dx = dx / mag * 0.1;
                self_vel.dy = dy / mag * 0.1;
            }
            walks += 1;
        }
    }
    counters.pursuit_walks += walks;
    std::hint::black_box(&counters.pursuit_walks);
}

/// Reverse walk equivalent: "who is hunting me?" implemented naively by
/// scanning every predator's Hunting component for each prey. This is
/// O(predators × prey) per tick — the cost the japes reverse index is
/// designed to eliminate.
fn pp_awareness(
    prey_q: Query<Entity, With<PpPrey>>,
    hunters: Query<&PpHunting, With<PpPredator>>,
    mut counters: ResMut<PpCounters>,
) {
    let mut total: u64 = 0;
    for prey_entity in &prey_q {
        let mut incoming = 0u64;
        for hunt in &hunters {
            if hunt.target == prey_entity {
                incoming += 1;
            }
        }
        total += incoming;
    }
    counters.with_target_walks += total;
    std::hint::black_box(&counters.with_target_walks);
}

/// Check distances and despawn prey. The japes version does the same via
/// `resolveCatches`; the cleanup semantics differ — here the predator's
/// stale `PpHunting` is fixed up next tick by `pp_acquire_hunt`, because
/// Bevy has no automatic reverse-index cleanup.
fn pp_resolve_catches(
    mut commands: Commands,
    predators: Query<(&PpPosition, &PpHunting), With<PpPredator>>,
    prey: Query<&PpPosition, With<PpPrey>>,
    mut roster: ResMut<PpPreyRoster>,
    config: Res<PpConfig>,
) {
    let mut caught: Vec<Entity> = Vec::new();
    for (pred_pos, hunt) in &predators {
        if let Ok(prey_pos) = prey.get(hunt.target) {
            let dx = pred_pos.x - prey_pos.x;
            let dy = pred_pos.y - prey_pos.y;
            if dx * dx + dy * dy <= config.catch_distance_sq {
                caught.push(hunt.target);
            }
        }
    }
    // De-dup: two predators can both catch the same prey in one tick.
    caught.sort_unstable();
    caught.dedup();
    for prey_entity in &caught {
        commands.entity(*prey_entity).despawn();
    }
    roster.alive.retain(|e| !caught.contains(e));
}

/// Steady-state top-up: spawn fresh prey to replace every catch so entity
/// counts stay stable across iterations.
fn pp_respawn_prey(
    mut commands: Commands,
    mut roster: ResMut<PpPreyRoster>,
    mut config: ResMut<PpConfig>,
) {
    while roster.alive.len() < config.baseline_prey {
        let x = config.next_f32() * config.arena_size;
        let y = config.next_f32() * config.arena_size;
        let e = commands
            .spawn((
                PpPosition { x, y },
                PpVelocity { dx: 0.0, dy: 0.0 },
                PpPrey,
            ))
            .id();
        roster.alive.push(e);
    }
}

/// Drain `RemovedComponents<PpPrey>` — Bevy's closest equivalent to
/// japes's `RemovedRelations<Hunting>`. Fires once per caught prey.
fn pp_observe_catches(
    mut removed: RemovedComponents<PpPrey>,
    mut counters: ResMut<PpCounters>,
) {
    for _ in removed.read() {
        counters.catches += 1;
    }
    std::hint::black_box(counters.catches);
}

// --- Optimized variant: manually maintained reverse index ---
//
// Same simulation shape as the naive version above, but with a
// `PpHuntedBy(Vec<Entity>)` component on every prey that predators
// push into when they acquire a hunt. `pp_opt_awareness` then reads
// `hunted_by.0.len()` — O(prey), with an O(1) probe per prey —
// instead of scanning every predator.
//
// This is *exactly* the hand-roll the japes relation feature
// replaces. Measuring it isolates "what does the library buy you?"
// from "how fast can a determined user write the same thing?".
// The answer turns out to be: most of the high-N asymptotic win
// comes from the reverse index, not from any magic in japes —
// which is the whole point of the design.

#[derive(Component, Default)]
struct PpHuntedBy(Vec<Entity>);

fn pp_opt_acquire_hunt(
    mut commands: Commands,
    idle: Query<Entity, (With<PpPredator>, Without<PpHunting>)>,
    hunting: Query<(Entity, &PpHunting), With<PpPredator>>,
    mut prey: Query<&mut PpHuntedBy, With<PpPrey>>,
    roster: Res<PpPreyRoster>,
    mut config: ResMut<PpConfig>,
) {
    // Drop stale Hunting whose target died last tick. The dead prey's
    // HuntedBy component went away with the prey, so no reverse-index
    // fixup is needed — that's the cleanup asymmetry of this
    // workload.
    for (predator, hunt) in &hunting {
        if prey.get(hunt.target).is_err() {
            commands.entity(predator).remove::<PpHunting>();
        }
    }

    let alive = &roster.alive;
    if alive.is_empty() {
        return;
    }
    for predator in &idle {
        let idx = config.next_range(alive.len());
        let target = alive[idx];
        // Two writes per acquire: the forward Hunting component and a
        // push into the target's HuntedBy vec. The japes equivalent
        // of this maintenance lives inside `RelationStore.set`.
        if let Ok(mut hunted_by) = prey.get_mut(target) {
            hunted_by.0.push(predator);
            commands.entity(predator).insert(PpHunting { target });
        }
    }
}

fn pp_opt_awareness(
    prey_q: Query<&PpHuntedBy, With<PpPrey>>,
    mut counters: ResMut<PpCounters>,
) {
    let mut total: u64 = 0;
    for hunted_by in &prey_q {
        total += hunted_by.0.len() as u64;
    }
    counters.with_target_walks += total;
    std::hint::black_box(&counters.with_target_walks);
}

fn pp_opt_respawn_prey(
    mut commands: Commands,
    mut roster: ResMut<PpPreyRoster>,
    mut config: ResMut<PpConfig>,
) {
    while roster.alive.len() < config.baseline_prey {
        let x = config.next_f32() * config.arena_size;
        let y = config.next_f32() * config.arena_size;
        let e = commands
            .spawn((
                PpPosition { x, y },
                PpVelocity { dx: 0.0, dy: 0.0 },
                PpPrey,
                PpHuntedBy(Vec::new()),
            ))
            .id();
        roster.alive.push(e);
    }
}

/// Spawn predators + prey into a fresh world, return the seeded world
/// plus arena size for respawn bookkeeping. Used by both the naive and
/// the optimized benchmark setup.
fn seed_pp_world(pc: usize, nc: usize, spawn_hunted_by: bool) -> World {
    let mut world = World::new();
    world.insert_resource(PpPreyRoster::default());
    world.insert_resource(PpCounters::default());
    world.insert_resource(PpConfig {
        catch_distance_sq: 0.5 * 0.5,
        arena_size: 20.0,
        rng_state: 1234,
        baseline_prey: nc,
    });

    // Predators cluster near the origin.
    for _ in 0..pc {
        let (jx, jy) = {
            let mut cfg = world.resource_mut::<PpConfig>();
            (cfg.next_f32() * 2.0, cfg.next_f32() * 2.0)
        };
        world.spawn((
            PpPosition { x: jx, y: jy },
            PpVelocity { dx: 0.05, dy: 0.05 },
            PpPredator,
        ));
    }
    let arena = {
        let cfg = world.resource::<PpConfig>();
        cfg.arena_size
    };
    // Prey scattered across the arena. The optimized variant also
    // attaches a HuntedBy reverse-index component per prey.
    for _ in 0..nc {
        let (px, py) = {
            let mut cfg = world.resource_mut::<PpConfig>();
            (cfg.next_f32() * arena, cfg.next_f32() * arena)
        };
        let e = if spawn_hunted_by {
            world
                .spawn((
                    PpPosition { x: px, y: py },
                    PpVelocity { dx: 0.0, dy: 0.0 },
                    PpPrey,
                    PpHuntedBy(Vec::new()),
                ))
                .id()
        } else {
            world
                .spawn((
                    PpPosition { x: px, y: py },
                    PpVelocity { dx: 0.0, dy: 0.0 },
                    PpPrey,
                ))
                .id()
        };
        world.resource_mut::<PpPreyRoster>().alive.push(e);
    }
    world
}

fn predator_prey_benchmarks(c: &mut Criterion) {
    let mut group = c.benchmark_group("predator_prey");

    // Matches the japes PredatorPreyBenchmark parameter grid so the
    // numbers line up cell-by-cell.
    let param_grid: &[(usize, usize)] = &[
        (100, 500),
        (100, 2000),
        (100, 5000),
        (500, 500),
        (500, 2000),
        (500, 5000),
        (1000, 500),
        (1000, 2000),
        (1000, 5000),
    ];

    // --- Naive: Component<Entity>, O(pred × prey) awareness scan ---
    for &(predator_count, prey_count) in param_grid {
        let id = format!("pred_{}_prey_{}", predator_count, prey_count);
        group.bench_with_input(
            BenchmarkId::new("naive_tick", &id),
            &(predator_count, prey_count),
            |b, &(pc, nc)| {
                let mut world = seed_pp_world(pc, nc, false);
                let mut schedule = Schedule::default();
                schedule.add_systems(
                    (
                        pp_movement,
                        pp_acquire_hunt,
                        pp_pursuit,
                        pp_awareness,
                        pp_resolve_catches,
                        pp_respawn_prey,
                        pp_observe_catches,
                    )
                        .chain(),
                );
                for _ in 0..5 { schedule.run(&mut world); }
                b.iter(|| {
                    schedule.run(&mut world);
                    let counters = world.resource::<PpCounters>();
                    std::hint::black_box(counters.pursuit_walks);
                    std::hint::black_box(counters.with_target_walks);
                    std::hint::black_box(counters.catches);
                });
            },
        );
    }

    // --- Optimized: manually maintained HuntedBy reverse index ---
    for &(predator_count, prey_count) in param_grid {
        let id = format!("pred_{}_prey_{}", predator_count, prey_count);
        group.bench_with_input(
            BenchmarkId::new("optimized_tick", &id),
            &(predator_count, prey_count),
            |b, &(pc, nc)| {
                let mut world = seed_pp_world(pc, nc, true);
                let mut schedule = Schedule::default();
                schedule.add_systems(
                    (
                        pp_movement,
                        pp_opt_acquire_hunt,
                        pp_pursuit,
                        pp_opt_awareness,
                        pp_resolve_catches,
                        pp_opt_respawn_prey,
                        pp_observe_catches,
                    )
                        .chain(),
                );
                for _ in 0..5 { schedule.run(&mut world); }
                b.iter(|| {
                    schedule.run(&mut world);
                    let counters = world.resource::<PpCounters>();
                    std::hint::black_box(counters.pursuit_walks);
                    std::hint::black_box(counters.with_target_walks);
                    std::hint::black_box(counters.catches);
                });
            },
        );
    }

    group.finish();
}

criterion_group!(benches, iteration_benchmarks, nbody_benchmarks, entity_benchmarks, change_detection_benchmarks, scenario_benchmarks, sparse_delta_benchmarks, realistic_tick_benchmarks, predator_prey_benchmarks);
criterion_main!(benches);
