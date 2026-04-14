package zzuegg.ecs.persistence;

import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * Archetype-grouped binary serializer for a {@link World}. Unlike
 * {@link WorldSerializer} which writes entity-by-entity, this format
 * groups entities by archetype. On load, archetype resolution (HashSet,
 * ArchetypeId.of, ComponentInfo lookup) happens once per group instead
 * of once per entity, drastically reducing heap allocation.
 *
 * <p>The load path uses {@link zzuegg.ecs.world.BulkSpawnWithIdBuilder}
 * with {@link BinaryCodec#decodeDirect} to write primitives directly into
 * SoA backing arrays. No intermediate Record objects are created for
 * SoA-eligible types.
 *
 * <h3>Wire format</h3>
 * <pre>
 * MAGIC (4 bytes: 0x4A415047 = "JAPG")
 * VERSION (int: 1)
 * totalEntityCount (int)
 * archetypeCount (int)
 *   for each archetype:
 *     componentCount (int)
 *     componentClassNames (UTF[])
 *     entityCount (int)
 *     for each entity:
 *       entityId (long)
 *       for each component: [fields via codec]
 * </pre>
 */
public final class GroupedWorldSerializer {

    private static final int MAGIC = 0x4A415047; // "JAPG"
    private static final int VERSION = 1;

    private final Map<Class<? extends Record>, ComponentCodec<?>> customCodecs = new HashMap<>();

    public <T extends Record> void registerCodec(ComponentCodec<T> codec) {
        customCodecs.put(codec.type(), codec);
    }

    /** Save all entities with @Persistent components. */
    public void save(World world, DataOutput out) throws IOException {
        save(world, out, type -> type.isAnnotationPresent(Persistent.class));
    }

    @SuppressWarnings("unchecked")
    public void save(World world, DataOutput out, Predicate<Class<?>> componentFilter) throws IOException {
        var registry = world.componentRegistry();
        var groups = new ArrayList<Object[]>();
        int totalEntities = 0;

        for (zzuegg.ecs.archetype.Archetype archetype : world.archetypeGraph().allArchetypes()) {
            var compIds = new ArrayList<zzuegg.ecs.component.ComponentId>();
            var types = new ArrayList<Class<? extends Record>>();
            var codecs = new ArrayList<ComponentCodec<?>>();
            for (zzuegg.ecs.component.ComponentId compId : archetype.id().components()) {
                var info = registry.info(compId);
                if (componentFilter.test(info.type())) {
                    compIds.add(compId);
                    types.add(info.type());
                    codecs.add(getOrCreateCodec(info.type()));
                }
            }
            if (compIds.isEmpty() || archetype.entityCount() == 0) continue;
            groups.add(new Object[]{archetype, compIds, types, codecs});
            totalEntities += archetype.entityCount();
        }

        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(totalEntities);
        out.writeInt(groups.size());

        for (Object[] group : groups) {
            var archetype = (zzuegg.ecs.archetype.Archetype) group[0];
            var compIds = (List<zzuegg.ecs.component.ComponentId>) group[1];
            var types = (List<Class<? extends Record>>) group[2];
            var codecs = (List<ComponentCodec<?>>) group[3];
            out.writeInt(types.size());
            for (Class<? extends Record> type : types) out.writeUTF(type.getName());
            out.writeInt(archetype.entityCount());
            for (var chunk : archetype.chunks()) {
                for (int slot = 0; slot < chunk.count(); slot++) {
                    out.writeLong(chunk.entity(slot).id());
                    for (int c = 0; c < compIds.size(); c++) {
                        ((ComponentCodec<Record>) codecs.get(c)).encode(
                            (Record) chunk.get(compIds.get(c), slot), out);
                    }
                }
            }
        }
    }

    /** Load from archetype-grouped format. Clears world first. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void load(World world, DataInput in) throws IOException {
        world.clear();
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Bad version: " + version);

        int totalEntities = in.readInt();
        int archetypeCount = in.readInt();
        world.prepareForLoad(totalEntities, totalEntities);

        for (int a = 0; a < archetypeCount; a++) {
            int compCount = in.readInt();
            Class[] types = new Class[compCount];
            for (int c = 0; c < compCount; c++) {
                try { types[c] = Class.forName(in.readUTF()); }
                catch (ClassNotFoundException e) { throw new IOException("Class not found", e); }
            }

            // Create BulkSpawnWithIdBuilder ONCE per archetype group -- caches
            // archetype, chunk, and storage references so per-entity cost is
            // just allocateExact + add + SoA array writes.
            var builder = world.bulkSpawnWithIdBuilder(types);

            var binaryCodecs = new BinaryCodec[compCount];
            boolean allDirect = true;
            for (int c = 0; c < compCount; c++) {
                var codec = getOrCreateCodec((Class<? extends Record>) types[c]);
                if (codec instanceof BinaryCodec bc && bc.supportsDirectDecode()) {
                    binaryCodecs[c] = bc;
                } else {
                    allDirect = false;
                }
            }

            int entityCount = in.readInt();

            if (allDirect) {
                // Fast path: decode directly into SoA arrays, no Record allocation
                for (int e = 0; e < entityCount; e++) {
                    int slot = builder.allocateSlot(new Entity(in.readLong()));
                    for (int c = 0; c < compCount; c++) {
                        binaryCodecs[c].decodeDirect(in, builder.soaArrays(c), slot);
                    }
                    builder.markAdded(slot);
                }
            } else {
                // Fallback: decode to Record objects
                var codecs = new ComponentCodec[compCount];
                for (int c = 0; c < compCount; c++) {
                    codecs[c] = getOrCreateCodec((Class<? extends Record>) types[c]);
                }
                for (int e = 0; e < entityCount; e++) {
                    var components = new Record[compCount];
                    long entityId = in.readLong();
                    for (int c = 0; c < compCount; c++) {
                        components[c] = (Record) codecs[c].decode(in);
                    }
                    builder.spawnWithId(new Entity(entityId), components);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> ComponentCodec<T> getOrCreateCodec(Class<T> type) {
        var custom = (ComponentCodec<T>) customCodecs.get(type);
        return custom != null ? custom : new BinaryCodec<>(type);
    }
}
