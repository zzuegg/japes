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

    group.bench_with_input(
        BenchmarkId::new("tick", 10000),
        &10000usize,
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
                // Observer tick — runs the three Changed<T> observers in parallel.
                schedule.run(&mut world);
            });
        },
    );

    group.finish();
}

criterion_group!(benches, iteration_benchmarks, nbody_benchmarks, entity_benchmarks, change_detection_benchmarks, scenario_benchmarks, sparse_delta_benchmarks, realistic_tick_benchmarks);
criterion_main!(benches);
