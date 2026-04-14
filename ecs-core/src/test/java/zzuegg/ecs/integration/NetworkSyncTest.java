package zzuegg.ecs.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.persistence.ComponentCodec;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating a network server/client sync pattern
 * using the plugin API. The "network" is a byte[] passed between two
 * World instances in the same JVM -- no real sockets involved.
 */
class NetworkSyncTest {

    // ---- Component types ----

    /** Synced over network AND persisted. */
    @NetworkSync @Persistent
    record Position(float x, float y) {}

    /** Synced over network only. */
    @NetworkSync
    record Velocity(float dx, float dy) {}

    /** Synced over network -- health is authoritative on server. */
    @NetworkSync
    record Health(int hp) {}

    /** Server-only, NOT synced to clients. */
    record AiState(String behavior) {}

    /** Client-only rendering data, never synced. */
    record SpriteIndex(int idx) {}

    // ---- NetworkSyncSerializer: encodes/decodes @NetworkSync components ----

    /**
     * Serializes a full or delta snapshot of all entities that have at least
     * one {@link NetworkSync}-annotated component. The wire format:
     * <pre>
     *   despawnCount (int)
     *   for each: entityId (long)
     *   entityCount (int)
     *   for each entity:
     *     entityId (long)
     *     componentCount (int)
     *     for each component:
     *       className (UTF)
     *       [fields via BinaryCodec]
     * </pre>
     * Despawns are written first so the client frees entity indices
     * before processing spawns (avoids index-reuse collisions).
     */
    static class NetworkSyncSerializer {

        private final Map<Class<? extends Record>, ComponentCodec<?>> codecs = new HashMap<>();

        @SuppressWarnings("unchecked")
        private <T extends Record> ComponentCodec<T> codec(Class<T> type) {
            return (ComponentCodec<T>) codecs.computeIfAbsent(type, t -> {
                @SuppressWarnings("unchecked")
                var c = (Class<? extends Record>) t;
                return new BinaryCodec<>(c);
            });
        }

        /** Encode a full snapshot of all @NetworkSync entities. */
        byte[] encodeFullSnapshot(World serverWorld) throws IOException {
            var accessor = serverWorld.accessor();
            var buf = new ByteArrayOutputStream();
            var out = new DataOutputStream(buf);

            // Collect entities that have at least one @NetworkSync component
            var entities = new ArrayList<Entity>();
            for (var entity : accessor.allEntities()) {
                var syncComps = accessor.networkSyncComponents(entity);
                if (!syncComps.isEmpty()) {
                    entities.add(entity);
                }
            }

            // No despawns in a full snapshot
            out.writeInt(0);

            out.writeInt(entities.size());
            for (var entity : entities) {
                writeEntity(accessor, entity, out);
            }
            out.flush();
            return buf.toByteArray();
        }

        /** Encode a delta containing only the specified entities plus despawns. */
        byte[] encodeDelta(World serverWorld, Set<Entity> changedEntities, Set<Entity> despawnedEntities) throws IOException {
            var accessor = serverWorld.accessor();
            var buf = new ByteArrayOutputStream();
            var out = new DataOutputStream(buf);

            // Write despawns first so client frees indices before spawning
            out.writeInt(despawnedEntities.size());
            for (var entity : despawnedEntities) {
                out.writeLong(entity.id());
            }

            // Write changed/spawned entities
            var alive = new ArrayList<Entity>();
            for (var e : changedEntities) {
                if (serverWorld.isAlive(e)) alive.add(e);
            }
            out.writeInt(alive.size());
            for (var entity : alive) {
                writeEntity(accessor, entity, out);
            }

            out.flush();
            return buf.toByteArray();
        }

        /** Apply a snapshot/delta to a client world. */
        void applyToClient(World clientWorld, byte[] data) throws IOException {
            var in = new DataInputStream(new ByteArrayInputStream(data));

            // Process despawns first to free entity indices
            int despawnCount = in.readInt();
            for (int i = 0; i < despawnCount; i++) {
                long entityId = in.readLong();
                var entity = new Entity(entityId);
                clientWorld.despawnIfAlive(entity);
            }

            // Process entity spawns/updates
            int entityCount = in.readInt();
            for (int i = 0; i < entityCount; i++) {
                long entityId = in.readLong();
                int compCount = in.readInt();
                var components = new Record[compCount];
                for (int c = 0; c < compCount; c++) {
                    String className = in.readUTF();
                    @SuppressWarnings("unchecked")
                    var type = (Class<? extends Record>) resolveClass(className);
                    var codec = codec(type);
                    components[c] = codec.decode(in);
                }

                var entity = new Entity(entityId);
                if (clientWorld.isAlive(entity)) {
                    // Update existing entity: set each component
                    for (var comp : components) {
                        clientWorld.setComponent(entity, comp);
                    }
                } else {
                    // New entity: spawn with exact ID
                    clientWorld.spawnWithId(entity, components);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void writeEntity(WorldAccessor accessor, Entity entity, DataOutputStream out) throws IOException {
            out.writeLong(entity.id());
            var syncComps = accessor.networkSyncComponents(entity);
            out.writeInt(syncComps.size());
            for (var comp : syncComps) {
                out.writeUTF(comp.getClass().getName());
                var codec = (ComponentCodec<Record>) codec(comp.getClass());
                codec.encode(comp, out);
            }
        }

        private Class<?> resolveClass(String name) throws IOException {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IOException("Unknown component class: " + name, e);
            }
        }
    }

    // ---- Test fixtures ----

    World server;
    World client;
    NetworkSyncSerializer serializer;

    @BeforeEach
    void setUp() {
        server = World.builder().build();
        client = World.builder().build();
        serializer = new NetworkSyncSerializer();
    }

    // ---- Test: initial full sync ----

    @Test
    void fullSyncTransfersAllNetworkSyncEntities() throws Exception {
        var e1 = server.spawn(new Position(10, 20), new Velocity(1, 0), new Health(100));
        var e2 = server.spawn(new Position(30, 40), new Health(50));

        byte[] snapshot = serializer.encodeFullSnapshot(server);
        serializer.applyToClient(client, snapshot);

        assertEquals(2, client.entityCount());
        assertTrue(client.isAlive(e1));
        assertTrue(client.isAlive(e2));

        assertEquals(new Position(10, 20), client.getComponent(e1, Position.class));
        assertEquals(new Velocity(1, 0), client.getComponent(e1, Velocity.class));
        assertEquals(new Health(100), client.getComponent(e1, Health.class));

        assertEquals(new Position(30, 40), client.getComponent(e2, Position.class));
        assertEquals(new Health(50), client.getComponent(e2, Health.class));
    }

    // ---- Test: server-only components are NOT synced ----

    @Test
    void serverOnlyComponentsAreNotSynced() throws Exception {
        var e = server.spawn(new Position(1, 2), new AiState("patrol"));

        byte[] snapshot = serializer.encodeFullSnapshot(server);
        serializer.applyToClient(client, snapshot);

        assertTrue(client.isAlive(e));
        assertEquals(new Position(1, 2), client.getComponent(e, Position.class));
        assertFalse(client.hasComponent(e, AiState.class));
    }

    // ---- Test: entity with no @NetworkSync components is skipped ----

    @Test
    void entitiesWithoutNetworkSyncAreSkipped() throws Exception {
        server.spawn(new AiState("idle")); // no @NetworkSync component
        var synced = server.spawn(new Position(5, 5));

        byte[] snapshot = serializer.encodeFullSnapshot(server);
        serializer.applyToClient(client, snapshot);

        assertEquals(1, client.entityCount());
        assertTrue(client.isAlive(synced));
    }

    // ---- Test: delta sync updates changed components ----

    @Test
    void deltaSyncUpdatesChangedComponents() throws Exception {
        var e1 = server.spawn(new Position(0, 0), new Health(100));
        var e2 = server.spawn(new Position(10, 10), new Health(80));

        // Initial full sync
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));

        // Mutate e1 on server
        server.setComponent(e1, new Position(5, 5));
        server.setComponent(e1, new Health(90));

        // Send delta with only e1
        byte[] delta = serializer.encodeDelta(server, Set.of(e1), Set.of());
        serializer.applyToClient(client, delta);

        // e1 updated
        assertEquals(new Position(5, 5), client.getComponent(e1, Position.class));
        assertEquals(new Health(90), client.getComponent(e1, Health.class));

        // e2 unchanged
        assertEquals(new Position(10, 10), client.getComponent(e2, Position.class));
        assertEquals(new Health(80), client.getComponent(e2, Health.class));
    }

    // ---- Test: new entity spawned on server appears on client via delta ----

    @Test
    void deltaSyncSpawnsNewEntities() throws Exception {
        var e1 = server.spawn(new Position(0, 0));

        // Initial sync
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));
        assertEquals(1, client.entityCount());

        // Spawn new entity on server
        var e2 = server.spawn(new Position(99, 99), new Velocity(1, 1));

        // Delta with the new entity
        byte[] delta = serializer.encodeDelta(server, Set.of(e2), Set.of());
        serializer.applyToClient(client, delta);

        assertEquals(2, client.entityCount());
        assertTrue(client.isAlive(e2));
        assertEquals(new Position(99, 99), client.getComponent(e2, Position.class));
        assertEquals(new Velocity(1, 1), client.getComponent(e2, Velocity.class));
    }

    // ---- Test: entity despawned on server is removed from client ----

    @Test
    void deltaSyncDespawnsEntities() throws Exception {
        var e1 = server.spawn(new Position(0, 0));
        var e2 = server.spawn(new Position(10, 10));

        // Initial sync
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));
        assertEquals(2, client.entityCount());

        // Despawn e1 on server
        server.despawn(e1);

        // Delta with despawn info
        byte[] delta = serializer.encodeDelta(server, Set.of(), Set.of(e1));
        serializer.applyToClient(client, delta);

        assertEquals(1, client.entityCount());
        assertFalse(client.isAlive(e1));
        assertTrue(client.isAlive(e2));
    }

    // ---- Test: multiple component types with @NetworkSync ----

    @Test
    void multipleNetworkSyncComponentTypesAreSynced() throws Exception {
        var e = server.spawn(new Position(1, 2), new Velocity(3, 4), new Health(75));

        byte[] snapshot = serializer.encodeFullSnapshot(server);
        serializer.applyToClient(client, snapshot);

        assertEquals(new Position(1, 2), client.getComponent(e, Position.class));
        assertEquals(new Velocity(3, 4), client.getComponent(e, Velocity.class));
        assertEquals(new Health(75), client.getComponent(e, Health.class));
    }

    // ---- Test: combined spawn, update, despawn in a single delta ----

    @Test
    void combinedSpawnUpdateDespawnInSingleDelta() throws Exception {
        var e1 = server.spawn(new Position(0, 0));
        var e2 = server.spawn(new Position(10, 10));

        // Initial sync
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));

        // Server: update e1, despawn e2, spawn e3
        server.setComponent(e1, new Position(5, 5));
        server.despawn(e2);
        var e3 = server.spawn(new Position(20, 20));

        byte[] delta = serializer.encodeDelta(server, Set.of(e1, e3), Set.of(e2));
        serializer.applyToClient(client, delta);

        assertEquals(2, client.entityCount());
        assertEquals(new Position(5, 5), client.getComponent(e1, Position.class));
        assertFalse(client.isAlive(e2));
        assertTrue(client.isAlive(e3));
        assertEquals(new Position(20, 20), client.getComponent(e3, Position.class));
    }

    // ---- Test: empty snapshot produces empty client ----

    @Test
    void emptyServerProducesEmptySnapshot() throws Exception {
        byte[] snapshot = serializer.encodeFullSnapshot(server);
        serializer.applyToClient(client, snapshot);

        assertEquals(0, client.entityCount());
    }

    // ---- Test: full re-sync overwrites stale client state ----

    @Test
    void fullResyncOverwritesStaleClientState() throws Exception {
        var e = server.spawn(new Position(0, 0), new Health(100));

        // Initial sync
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));
        assertEquals(new Health(100), client.getComponent(e, Health.class));

        // Server mutates
        server.setComponent(e, new Health(42));

        // Full re-sync (not delta) should overwrite
        serializer.applyToClient(client, serializer.encodeFullSnapshot(server));
        assertEquals(new Health(42), client.getComponent(e, Health.class));
    }
}
