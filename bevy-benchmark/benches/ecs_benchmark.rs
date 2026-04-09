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

criterion_group!(benches, iteration_benchmarks, nbody_benchmarks, entity_benchmarks);
criterion_main!(benches);
