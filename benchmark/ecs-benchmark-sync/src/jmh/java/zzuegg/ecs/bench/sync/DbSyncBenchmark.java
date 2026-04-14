package zzuegg.ecs.bench.sync;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Measures throughput of syncing ECS world state to an H2 in-memory database.
 *
 * <p>Two benchmarks:
 * <ul>
 *   <li><b>fullSync</b> — writes ALL persistent entities to H2 via batch JDBC inserts.</li>
 *   <li><b>deltaSync</b> — mutates ~1% of entities per tick, then syncs only changed ones
 *       by comparing against a snapshot of last-synced state.</li>
 * </ul>
 *
 * <p>The full round-trip is measured: WorldAccessor read, BinaryCodec encode, JDBC batch write.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class DbSyncBenchmark {

    // ----------------------------------------------------------------
    // Components
    // ----------------------------------------------------------------

    @Persistent public record Position(float x, float y, float z) {}
    @Persistent public record Velocity(float vx, float vy, float vz) {}
    @Persistent public record Health(int hp) {}

    // ----------------------------------------------------------------
    // Parameters
    // ----------------------------------------------------------------

    @Param({"1000", "10000", "100000"})
    int entityCount;

    // ----------------------------------------------------------------
    // State
    // ----------------------------------------------------------------

    World world;
    List<Entity> entities;
    Connection connection;
    WorldAccessor accessor;

    // Codecs for binary encoding
    BinaryCodec<Position> posCodec;
    BinaryCodec<Velocity> velCodec;
    BinaryCodec<Health> healthCodec;

    // Reusable encoding buffer
    ByteArrayOutputStream baos;
    DataOutputStream dos;

    // Delta-sync: snapshot of last-synced state (entity_id -> component_type -> encoded bytes)
    Map<Long, Map<String, byte[]>> lastSyncedSnapshot;

    // Delta-sync: rotating cursor for mutations
    int cursor;

    // Prepared statements
    PreparedStatement upsertStmt;

    @Setup(Level.Iteration)
    public void setup() throws SQLException {
        // --- ECS world ---
        world = World.builder().build();
        entities = new ArrayList<>(entityCount);
        var rng = new Random(42);
        for (int i = 0; i < entityCount; i++) {
            entities.add(world.spawn(
                new Position(rng.nextFloat() * 100, rng.nextFloat() * 100, rng.nextFloat() * 100),
                new Velocity(rng.nextFloat() * 10, rng.nextFloat() * 10, rng.nextFloat() * 10),
                new Health(rng.nextInt(1000))
            ));
        }
        world.tick(); // prime the world
        accessor = world.accessor();

        // --- Codecs ---
        posCodec = new BinaryCodec<>(Position.class);
        velCodec = new BinaryCodec<>(Velocity.class);
        healthCodec = new BinaryCodec<>(Health.class);
        baos = new ByteArrayOutputStream(64);
        dos = new DataOutputStream(baos);

        // --- H2 in-memory database ---
        connection = DriverManager.getConnection("jdbc:h2:mem:bench;DB_CLOSE_DELAY=-1");
        try (var stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS components");
            stmt.execute("""
                CREATE TABLE components (
                    entity_id      BIGINT       NOT NULL,
                    component_type VARCHAR(128) NOT NULL,
                    data           VARBINARY(4096),
                    PRIMARY KEY (entity_id, component_type)
                )
                """);
        }
        upsertStmt = connection.prepareStatement(
            "MERGE INTO components (entity_id, component_type, data) KEY (entity_id, component_type) VALUES (?, ?, ?)"
        );

        // --- Delta-sync state ---
        lastSyncedSnapshot = new HashMap<>(entityCount * 2);
        cursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() throws SQLException {
        if (upsertStmt != null) upsertStmt.close();
        if (connection != null) connection.close();
        if (world != null) world.close();
    }

    // ----------------------------------------------------------------
    // Benchmark: full sync
    // ----------------------------------------------------------------

    /**
     * Writes every persistent entity and all its persistent components to H2
     * via a single MERGE INTO batch.
     */
    @Benchmark
    public int fullSync() throws SQLException, IOException {
        int count = 0;
        for (var entity : accessor.persistentEntities()) {
            long eid = entity.id();
            for (var comp : accessor.persistentComponents(entity)) {
                baos.reset();
                encodeComponent(comp);
                upsertStmt.setLong(1, eid);
                upsertStmt.setString(2, comp.getClass().getSimpleName());
                upsertStmt.setBytes(3, baos.toByteArray());
                upsertStmt.addBatch();
                count++;
            }
        }
        upsertStmt.executeBatch();
        return count;
    }

    // ----------------------------------------------------------------
    // Benchmark: delta sync
    // ----------------------------------------------------------------

    /**
     * Mutates ~1% of entities (rotating window), then syncs only the ones
     * whose encoded representation differs from the last snapshot.
     */
    @Benchmark
    public int deltaSync() throws SQLException, IOException {
        // --- Mutate ~1% of entities ---
        int batch = Math.max(1, entityCount / 100);
        for (int i = 0; i < batch; i++) {
            var e = entities.get(cursor);
            cursor = (cursor + 1) % entities.size();
            var pos = world.getComponent(e, Position.class);
            world.setComponent(e, new Position(pos.x() + 0.1f, pos.y() + 0.1f, pos.z() + 0.1f));
        }
        world.tick();

        // --- Diff and sync ---
        int written = 0;
        for (var entity : accessor.persistentEntities()) {
            long eid = entity.id();
            var prevComps = lastSyncedSnapshot.get(eid);
            for (var comp : accessor.persistentComponents(entity)) {
                baos.reset();
                encodeComponent(comp);
                byte[] encoded = baos.toByteArray();
                String typeName = comp.getClass().getSimpleName();

                boolean changed;
                if (prevComps == null) {
                    changed = true;
                } else {
                    byte[] prev = prevComps.get(typeName);
                    changed = prev == null || !Arrays.equals(prev, encoded);
                }

                if (changed) {
                    upsertStmt.setLong(1, eid);
                    upsertStmt.setString(2, typeName);
                    upsertStmt.setBytes(3, encoded);
                    upsertStmt.addBatch();
                    written++;
                    lastSyncedSnapshot
                        .computeIfAbsent(eid, _ -> new HashMap<>(4))
                        .put(typeName, encoded);
                }
            }
        }
        if (written > 0) {
            upsertStmt.executeBatch();
        }
        return written;
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void encodeComponent(Record comp) throws IOException {
        switch (comp) {
            case Position p -> posCodec.encode(p, dos);
            case Velocity v -> velCodec.encode(v, dos);
            case Health h  -> healthCodec.encode(h, dos);
            default -> throw new IllegalArgumentException("Unknown component: " + comp.getClass());
        }
        dos.flush();
    }
}
