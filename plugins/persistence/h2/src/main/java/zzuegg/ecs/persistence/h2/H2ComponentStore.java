package zzuegg.ecs.persistence.h2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import zzuegg.ecs.persistence.BinaryCodec;

/**
 * Handles all JDBC operations for persisting ECS component data to H2.
 *
 * <p>Schema:
 * <ul>
 *   <li>{@code entities (entity_id BIGINT PRIMARY KEY, archetype VARCHAR)} —
 *       one row per persistent entity</li>
 *   <li>{@code comp_<ClassName> (entity_id BIGINT PRIMARY KEY, data VARBINARY)} —
 *       one table per component type, keyed by entity</li>
 * </ul>
 *
 * <p>Tables are auto-created on first use. Uses {@link BinaryCodec} for
 * serialization and batch inserts for performance.
 */
final class H2ComponentStore {

    private final Connection connection;
    private final Set<String> createdTables = new HashSet<>();

    H2ComponentStore(Connection connection) {
        this.connection = connection;
        ensureEntitiesTable();
    }

    private void ensureEntitiesTable() {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS entities (
                    entity_id BIGINT PRIMARY KEY,
                    archetype VARCHAR(4096) NOT NULL
                )
                """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create entities table", e);
        }
    }

    private String tableNameFor(Class<?> type) {
        return "comp_" + type.getSimpleName();
    }

    private void ensureComponentTable(Class<?> type) {
        var tableName = tableNameFor(type);
        if (createdTables.contains(tableName)) return;
        try (var stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + tableName +
                " (entity_id BIGINT PRIMARY KEY, data VARBINARY(65536))"
            );
            createdTables.add(tableName);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create table " + tableName, e);
        }
    }

    /** Clear all data from all known tables. */
    void clearAll() {
        try (var stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM entities");
            for (var tableName : createdTables) {
                stmt.execute("DELETE FROM " + tableName);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear tables", e);
        }
    }

    /** Insert or update an entity's archetype descriptor. */
    void upsertEntity(long entityId, String archetype, PreparedStatement batch) throws SQLException {
        batch.setLong(1, entityId);
        batch.setString(2, archetype);
        batch.addBatch();
    }

    /** Delete an entity and all its component rows. */
    void deleteEntity(long entityId) {
        try (var stmt = connection.prepareStatement("DELETE FROM entities WHERE entity_id = ?")) {
            stmt.setLong(1, entityId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete entity " + entityId, e);
        }
        // Delete from all known component tables
        for (var tableName : createdTables) {
            try (var stmt = connection.prepareStatement("DELETE FROM " + tableName + " WHERE entity_id = ?")) {
                stmt.setLong(1, entityId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete entity " + entityId + " from " + tableName, e);
            }
        }
    }

    /** Encode a component to bytes using BinaryCodec. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    byte[] encode(Record component, BinaryCodec codec) {
        var baos = new ByteArrayOutputStream(64);
        var dos = new DataOutputStream(baos);
        try {
            codec.encode(component, dos);
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode " + component.getClass().getSimpleName(), e);
        }
        return baos.toByteArray();
    }

    /** Decode a component from bytes using BinaryCodec. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Record decode(byte[] data, BinaryCodec codec) {
        var bais = new ByteArrayInputStream(data);
        var dis = new DataInputStream(bais);
        try {
            return codec.decode(dis);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode " + codec.type().getSimpleName(), e);
        }
    }

    /**
     * Batch-save a set of components for a given type. Uses MERGE INTO for
     * upsert semantics.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void batchSaveComponents(Class type, List entries, BinaryCodec codec) {
        ensureComponentTable(type);
        var tableName = tableNameFor(type);
        try (var stmt = connection.prepareStatement(
                "MERGE INTO " + tableName + " (entity_id, data) KEY (entity_id) VALUES (?, ?)")) {
            for (var e : entries) {
                var ec = (EntityComponent<?>) e;
                stmt.setLong(1, ec.entityId());
                stmt.setBytes(2, encode((Record) ec.component(), codec));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch save " + tableName, e);
        }
    }

    /**
     * Load all rows from a component table.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void loadComponents(Class type, BinaryCodec codec,
                        BiConsumer<Long, Record> consumer) {
        ensureComponentTable(type);
        var tableName = tableNameFor(type);
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT entity_id, data FROM " + tableName)) {
            while (rs.next()) {
                long entityId = rs.getLong(1);
                byte[] data = rs.getBytes(2);
                consumer.accept(entityId, decode(data, codec));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load " + tableName, e);
        }
    }

    /**
     * Load all entity rows (entity_id -> archetype descriptor).
     */
    Map<Long, String> loadEntities() {
        var result = new HashMap<Long, String>();
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT entity_id, archetype FROM entities")) {
            while (rs.next()) {
                result.put(rs.getLong(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load entities", e);
        }
        return result;
    }

    /**
     * Batch-save entity rows.
     */
    void batchSaveEntities(List<EntityArchetype> entries) {
        try (var stmt = connection.prepareStatement(
                "MERGE INTO entities (entity_id, archetype) KEY (entity_id) VALUES (?, ?)")) {
            for (var entry : entries) {
                stmt.setLong(1, entry.entityId());
                stmt.setString(2, entry.archetype());
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch save entities", e);
        }
    }

    /** Delete component rows for a given entity from a specific component table. */
    void deleteComponentRow(long entityId, Class<?> type) {
        ensureComponentTable(type);
        var tableName = tableNameFor(type);
        try (var stmt = connection.prepareStatement("DELETE FROM " + tableName + " WHERE entity_id = ?")) {
            stmt.setLong(1, entityId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete component for entity " + entityId, e);
        }
    }

    /** Delete an entity row (not components). */
    void deleteEntityRow(long entityId) {
        try (var stmt = connection.prepareStatement("DELETE FROM entities WHERE entity_id = ?")) {
            stmt.setLong(1, entityId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete entity row " + entityId, e);
        }
    }

    /**
     * Get the set of distinct component table names that actually exist in the DB.
     */
    Set<String> existingComponentTableNames() {
        var result = new HashSet<String>();
        try (var rs = connection.getMetaData().getTables(null, null, "COMP_%", null)) {
            while (rs.next()) {
                result.add(rs.getString("TABLE_NAME"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list component tables", e);
        }
        return result;
    }

    Connection connection() {
        return connection;
    }

    void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close connection", e);
        }
    }

    // ----------------------------------------------------------------
    // Internal records
    // ----------------------------------------------------------------

    record EntityComponent<T extends Record>(long entityId, T component) {}
    record EntityArchetype(long entityId, String archetype) {}
}
