package zzuegg.ecs.persistence.h2;

import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H2 database persistence plugin for the japes ECS framework.
 *
 * <p>Provides full save/load and incremental delta sync of persistent
 * entities and components to an H2 database. Only components annotated
 * with {@link Persistent} are included.
 *
 * <p>Usage:
 * <pre>{@code
 * var world = World.builder()
 *     .addSystem(Physics.class)
 *     .build();
 *
 * var h2 = H2PersistencePlugin.create(world, "jdbc:h2:./gamedata");
 * h2.saveAll();           // full save to DB
 * h2.loadAll();           // full load from DB
 * h2.syncChanged();       // delta sync (only changed entities)
 * h2.close();
 * }</pre>
 */
public final class H2PersistencePlugin {

    private final World world;
    private final H2ComponentStore store;
    @SuppressWarnings("rawtypes")
    private final Map<Class, BinaryCodec> codecCache = new HashMap<>();

    /**
     * Snapshot of last-synced state for delta sync.
     * Keyed by entity ID, value is a map of component class to its encoded bytes.
     */
    @SuppressWarnings("rawtypes")
    private final Map<Long, Map<Class, byte[]>> lastSyncedSnapshot = new HashMap<>();

    private H2PersistencePlugin(World world, H2ComponentStore store) {
        this.world = world;
        this.store = store;
    }

    /**
     * Create a new plugin instance connected to the given JDBC URL.
     *
     * @param world    the ECS world to persist
     * @param jdbcUrl  H2 JDBC connection URL (e.g. {@code "jdbc:h2:./gamedata"})
     * @return a new plugin instance
     */
    public static H2PersistencePlugin create(World world, String jdbcUrl) {
        try {
            var connection = DriverManager.getConnection(jdbcUrl);
            var store = new H2ComponentStore(connection);
            return new H2PersistencePlugin(world, store);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to H2 at " + jdbcUrl, e);
        }
    }

    /**
     * Full save: writes all persistent entities and their persistent
     * components to the database, replacing any existing data.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void saveAll() {
        var accessor = world.accessor();

        // Clear existing data
        store.clearAll();

        // Collect entities grouped by component type
        Map<Class, List<H2ComponentStore.EntityComponent>> byType = new HashMap<>();
        var entityArchetypes = new ArrayList<H2ComponentStore.EntityArchetype>();

        accessor.forEachPersistentEntityComponent((entity, component) -> {
            var type = component.getClass();
            byType.computeIfAbsent(type, _ -> new ArrayList<>())
                  .add(new H2ComponentStore.EntityComponent<>(entity.id(), component));
        });

        // Collect entity archetype descriptors
        for (var entity : accessor.persistentEntities()) {
            var persistentTypes = new ArrayList<String>();
            accessor.forEachPersistentComponent(entity, comp ->
                persistentTypes.add(comp.getClass().getName()));
            entityArchetypes.add(new H2ComponentStore.EntityArchetype(
                entity.id(), String.join(",", persistentTypes)));
        }

        // Batch save entities
        store.batchSaveEntities(entityArchetypes);

        // Batch save each component type
        for (var entry : byType.entrySet()) {
            var type = entry.getKey();
            var codec = getOrCreateCodec(type);
            store.batchSaveComponents(type, entry.getValue(), codec);
        }

        // Update the snapshot for future delta syncs
        rebuildSnapshot(accessor);
    }

    /**
     * Full load: clears the world and restores all entities and persistent
     * components from the database. Entity IDs are preserved exactly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadAll() {
        world.clear();

        // Load entity -> archetype mapping
        var entityArchetypes = store.loadEntities();
        if (entityArchetypes.isEmpty()) return;

        // Group entities by their archetype descriptor (set of component types)
        Map<String, List<Long>> entitiesByArchetype = new HashMap<>();
        for (var entry : entityArchetypes.entrySet()) {
            entitiesByArchetype.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>())
                               .add(entry.getKey());
        }

        // For each archetype group, load components and spawn entities
        for (var entry : entitiesByArchetype.entrySet()) {
            var archetypeDesc = entry.getKey();
            var entityIds = entry.getValue();

            // Parse the archetype descriptor into component classes
            var typeNames = archetypeDesc.split(",");
            var componentTypes = new Class[typeNames.length];
            for (int i = 0; i < typeNames.length; i++) {
                try {
                    componentTypes[i] = Class.forName(typeNames[i]);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Component class not found: " + typeNames[i], e);
                }
            }

            // Load component data for all entities in this archetype group
            // entityId -> componentType -> component
            Map<Long, Map<Class, Record>> entityComponents = new HashMap<>();
            for (var entityId : entityIds) {
                entityComponents.put(entityId, new HashMap<>());
            }

            var entityIdSet = new HashSet<>(entityIds);
            for (var compType : componentTypes) {
                var codec = getOrCreateCodec(compType);
                store.loadComponents(compType, codec, (entityId, component) -> {
                    if (entityIdSet.contains(entityId)) {
                        entityComponents.get(entityId).put(compType, (Record) component);
                    }
                });
            }

            // Spawn entities using BulkSpawnWithIdBuilder
            var builder = world.bulkSpawnWithIdBuilder(componentTypes);
            builder.ensureCapacity(entityIds.size());

            for (var entityId : entityIds) {
                var entity = new Entity(entityId);
                var components = entityComponents.get(entityId);
                var compArray = new Record[componentTypes.length];
                for (int i = 0; i < componentTypes.length; i++) {
                    compArray[i] = components.get(componentTypes[i]);
                }
                builder.spawnWithId(entity, compArray);
            }
        }

        // Rebuild snapshot after load
        rebuildSnapshot(world.accessor());
    }

    /**
     * Delta sync: compares the current world state against the last-synced
     * snapshot and writes only the changes to the database.
     *
     * <p>Handles new entities, updated components, and despawned entities.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void syncChanged() {
        var accessor = world.accessor();

        // Build current snapshot
        Map<Long, Map<Class, byte[]>> currentSnapshot = new HashMap<>();
        Map<Long, String> currentArchetypes = new HashMap<>();

        for (var entity : accessor.persistentEntities()) {
            long eid = entity.id();
            var compMap = new HashMap<Class, byte[]>();
            var persistentTypes = new ArrayList<String>();

            accessor.forEachPersistentComponent(entity, comp -> {
                var type = comp.getClass();
                var codec = getOrCreateCodec(type);
                compMap.put(type, store.encode(comp, codec));
                persistentTypes.add(type.getName());
            });

            currentSnapshot.put(eid, compMap);
            currentArchetypes.put(eid, String.join(",", persistentTypes));
        }

        // Diff: new or updated entities/components
        Map<Class, List<H2ComponentStore.EntityComponent>> upserts = new HashMap<>();
        var entityUpserts = new ArrayList<H2ComponentStore.EntityArchetype>();

        for (var entry : currentSnapshot.entrySet()) {
            long eid = entry.getKey();
            var currentComps = entry.getValue();
            var prevComps = lastSyncedSnapshot.get(eid);

            boolean isNew = prevComps == null;
            if (isNew) {
                entityUpserts.add(new H2ComponentStore.EntityArchetype(eid, currentArchetypes.get(eid)));
            }

            for (var compEntry : currentComps.entrySet()) {
                var type = compEntry.getKey();
                var encoded = compEntry.getValue();

                boolean changed;
                if (isNew) {
                    changed = true;
                } else {
                    var prev = prevComps.get(type);
                    changed = prev == null || !Arrays.equals(prev, encoded);
                }

                if (changed) {
                    // Decode from bytes to get the component value for saving
                    var codec = getOrCreateCodec(type);
                    var component = store.decode(encoded, codec);
                    upserts.computeIfAbsent(type, _ -> new ArrayList<>())
                           .add(new H2ComponentStore.EntityComponent<>(eid, component));
                }
            }
        }

        // Diff: removed entities
        for (var eid : lastSyncedSnapshot.keySet()) {
            if (!currentSnapshot.containsKey(eid)) {
                store.deleteEntityRow(eid);
                // Delete from all component tables for this entity
                var prevComps = lastSyncedSnapshot.get(eid);
                for (var type : prevComps.keySet()) {
                    store.deleteComponentRow(eid, type);
                }
            }
        }

        // Batch save upserts
        if (!entityUpserts.isEmpty()) {
            store.batchSaveEntities(entityUpserts);
        }
        for (var entry : upserts.entrySet()) {
            var type = entry.getKey();
            var codec = getOrCreateCodec(type);
            store.batchSaveComponents(type, entry.getValue(), codec);
        }

        // Replace snapshot
        lastSyncedSnapshot.clear();
        lastSyncedSnapshot.putAll(currentSnapshot);
    }

    /** Close the database connection. */
    public void close() {
        store.close();
    }

    // ----------------------------------------------------------------
    // Internal
    // ----------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BinaryCodec getOrCreateCodec(Class type) {
        return codecCache.computeIfAbsent(type, t -> new BinaryCodec<>((Class<? extends Record>) t));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void rebuildSnapshot(WorldAccessor accessor) {
        lastSyncedSnapshot.clear();
        for (var entity : accessor.persistentEntities()) {
            long eid = entity.id();
            var compMap = new HashMap<Class, byte[]>();
            accessor.forEachPersistentComponent(entity, comp -> {
                var type = comp.getClass();
                var codec = getOrCreateCodec(type);
                compMap.put(type, store.encode(comp, codec));
            });
            lastSyncedSnapshot.put(eid, compMap);
        }
    }
}
