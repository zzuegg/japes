package zzuegg.ecs.persistence;

import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

public final class WorldSerializer {

    private static final int MAGIC = 0x4A415053;
    private static final int VERSION = 1;
    private static final int VERSION_GROUPED = 2;

    private final Map<Class<? extends Record>, ComponentCodec<?>> customCodecs = new HashMap<>();

    public <T extends Record> void registerCodec(ComponentCodec<T> codec) {
        customCodecs.put(codec.type(), codec);
    }

    public void save(World world, DataOutput out) throws IOException {
        save(world, out, type -> type.isAnnotationPresent(Persistent.class));
    }

    public void save(World world, DataOutput out, Predicate<Class<?>> componentFilter) throws IOException {
        var accessor = world.accessor();
        var typeToIndex = new LinkedHashMap<Class<? extends Record>, Integer>();
        var codecByType = new HashMap<Class<? extends Record>, ComponentCodec<?>>();
        var entityData = new ArrayList<EntityRecord>();

        for (var entity : accessor.allEntities()) {
            var types = accessor.componentTypes(entity);
            var components = new ArrayList<TypedComponent>();
            for (var type : types) {
                if (!componentFilter.test(type)) continue;
                if (!typeToIndex.containsKey(type)) {
                    typeToIndex.put(type, typeToIndex.size());
                    codecByType.put(type, getOrCreateCodec(type));
                }
                components.add(new TypedComponent(type, typeToIndex.get(type), accessor.getComponent(entity, type)));
            }
            if (!components.isEmpty()) entityData.add(new EntityRecord(entity, components));
        }

        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(typeToIndex.size());
        for (var entry : typeToIndex.entrySet()) {
            out.writeUTF(entry.getKey().getName());
            out.writeInt(entry.getValue());
        }
        out.writeInt(entityData.size());
        for (var er : entityData) {
            out.writeLong(er.entity.id());
            out.writeInt(er.components.size());
            for (var tc : er.components) {
                out.writeInt(tc.typeIndex);
                @SuppressWarnings("unchecked")
                var codec = (ComponentCodec<Record>) codecByType.get(tc.type);
                codec.encode(tc.value, out);
            }
        }
    }

    public void load(World world, DataInput in) throws IOException {
        world.clear();
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Bad version: " + version);

        int typeCount = in.readInt();
        var codecByIndex = new HashMap<Integer, ComponentCodec<?>>();
        for (int i = 0; i < typeCount; i++) {
            String className = in.readUTF();
            int typeIndex = in.readInt();
            try {
                @SuppressWarnings("unchecked")
                var type = (Class<? extends Record>) Class.forName(className);
                codecByIndex.put(typeIndex, getOrCreateCodec(type));
            } catch (ClassNotFoundException e) {
                throw new IOException("Class not found: " + className, e);
            }
        }

        int entityCount = in.readInt();
        for (int i = 0; i < entityCount; i++) {
            long entityId = in.readLong();
            int compCount = in.readInt();
            var components = new Record[compCount];
            for (int c = 0; c < compCount; c++) {
                int typeIndex = in.readInt();
                @SuppressWarnings("unchecked")
                var codec = (ComponentCodec<Record>) codecByIndex.get(typeIndex);
                if (codec == null) throw new IOException("Unknown type index: " + typeIndex);
                components[c] = codec.decode(in);
            }
            world.spawnWithId(new Entity(entityId), components);
        }
    }

    /** Save using archetype-grouped format for reduced decode-path allocation. */
    public void saveGrouped(World world, DataOutput out) throws IOException {
        saveGrouped(world, out, type -> type.isAnnotationPresent(Persistent.class));
    }

    @SuppressWarnings("unchecked")
    public void saveGrouped(World world, DataOutput out, Predicate<Class<?>> componentFilter) throws IOException {
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
        out.writeInt(VERSION_GROUPED);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadGrouped(World world, DataInput in) throws IOException {
        world.clear();
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION_GROUPED) throw new IOException("Bad version: " + version);

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
            var builder = world.bulkSpawnWithIdBuilder(types);
            var binaryCodecs = new BinaryCodec[compCount];
            boolean allDirect = true;
            for (int c = 0; c < compCount; c++) {
                var codec = getOrCreateCodec((Class<? extends Record>) types[c]);
                if (codec instanceof BinaryCodec bc && bc.supportsDirectDecode()) {
                    binaryCodecs[c] = bc;
                } else { allDirect = false; }
            }
            int entityCount = in.readInt();
            if (allDirect) {
                for (int e = 0; e < entityCount; e++) {
                    int slot = builder.allocateSlot(new Entity(in.readLong()));
                    for (int c = 0; c < compCount; c++)
                        binaryCodecs[c].decodeDirect(in, builder.soaArrays(c), slot);
                    builder.markAdded(slot);
                }
            } else {
                var codecs = new ComponentCodec[compCount];
                for (int c = 0; c < compCount; c++)
                    codecs[c] = getOrCreateCodec((Class<? extends Record>) types[c]);
                for (int e = 0; e < entityCount; e++) {
                    var components = new Record[compCount];
                    long entityId = in.readLong();
                    for (int c = 0; c < compCount; c++)
                        components[c] = (Record) codecs[c].decode(in);
                    builder.spawnWithId(new Entity(entityId), components);
                }
            }
        }
    }


    // ---------------------------------------------------------------
    // v3: columnar (SoA-native) format
    // ---------------------------------------------------------------

    private static final int VERSION_COLUMNAR = 3;

    public void saveColumnar(World world, DataOutput out) throws IOException {
        saveColumnar(world, out, type -> type.isAnnotationPresent(Persistent.class));
    }

    @SuppressWarnings("unchecked")
    public void saveColumnar(World world, DataOutput out, Predicate<Class<?>> componentFilter) throws IOException {
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
        out.writeInt(VERSION_COLUMNAR);
        out.writeInt(totalEntities);
        out.writeInt(groups.size());

        for (Object[] group : groups) {
            var archetype = (zzuegg.ecs.archetype.Archetype) group[0];
            var compIds = (List<zzuegg.ecs.component.ComponentId>) group[1];
            var types = (List<Class<? extends Record>>) group[2];
            var codecs = (List<ComponentCodec<?>>) group[3];
            out.writeInt(types.size());
            for (Class<? extends Record> type : types) out.writeUTF(type.getName());
            int entityCount = archetype.entityCount();
            out.writeInt(entityCount);
            for (var chunk : archetype.chunks()) {
                for (int slot = 0; slot < chunk.count(); slot++) {
                    out.writeLong(chunk.entity(slot).id());
                }
            }
            for (int c = 0; c < compIds.size(); c++) {
                var codec = (ComponentCodec<Record>) codecs.get(c);
                var compId = compIds.get(c);
                for (var chunk : archetype.chunks()) {
                    for (int slot = 0; slot < chunk.count(); slot++) {
                        codec.encode((Record) chunk.get(compId, slot), out);
                    }
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void loadColumnar(World world, DataInput in) throws IOException {
        world.clear();
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Bad magic: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION_COLUMNAR) throw new IOException("Bad version: " + version);

        int totalEntities = in.readInt();
        int archetypeCount = in.readInt();
        world.prepareForLoad(totalEntities, totalEntities);

        for (int a = 0; a < archetypeCount; a++) {
            int compCount = in.readInt();
            Class[] types = new Class[compCount];
            BinaryCodec[] binaryCodecs = new BinaryCodec[compCount];
            boolean allDirect = true;
            for (int c = 0; c < compCount; c++) {
                String className = in.readUTF();
                try {
                    types[c] = (Class<? extends Record>) Class.forName(className);
                    var codec = getOrCreateCodec((Class<? extends Record>) types[c]);
                    if (codec instanceof BinaryCodec bc && bc.supportsDirectDecode()) {
                        binaryCodecs[c] = bc;
                    } else { allDirect = false; }
                } catch (ClassNotFoundException e) {
                    throw new IOException("Class not found: " + className, e);
                }
            }

            var compIds = new java.util.HashSet<zzuegg.ecs.component.ComponentId>();
            var compInfos = new zzuegg.ecs.component.ComponentInfo[compCount];
            for (int c = 0; c < compCount; c++) {
                var info = world.componentRegistry().getOrRegisterInfo(types[c]);
                compInfos[c] = info;
                if (info.isTableStorage()) compIds.add(info.id());
            }
            var archetypeId = zzuegg.ecs.archetype.ArchetypeId.of(compIds);
            var archetype = world.archetypeGraph().getOrCreate(archetypeId);
            int chunkCapacity = archetype.chunkCapacity();

            int entityCount = in.readInt();
            var entities = new zzuegg.ecs.entity.Entity[entityCount];
            for (int i = 0; i < entityCount; i++) {
                long eid = in.readLong();
                entities[i] = new zzuegg.ecs.entity.Entity(eid);
                world.entityAllocator().allocateExact(entities[i].index(), entities[i].generation());
            }

            int numChunks = (entityCount == 0) ? 0 : ((entityCount - 1) / chunkCapacity) + 1;
            var chunkList = new zzuegg.ecs.storage.Chunk[numChunks];
            var chunkSizes = new int[numChunks];
            int remaining = entityCount;
            int offset = 0;
            for (int ci = 0; ci < numChunks; ci++) {
                int batchSize = Math.min(remaining, chunkCapacity);
                var chunk = archetype.createChunk();
                chunk.bulkSetEntities(entities, offset, batchSize);
                chunkList[ci] = chunk;
                chunkSizes[ci] = batchSize;
                int archChunkIdx = archetype.chunkCount() - 1;
                for (int i = 0; i < batchSize; i++) {
                    world.setEntityLocation(entities[offset + i].index(),
                        new zzuegg.ecs.archetype.EntityLocation(archetype, archChunkIdx, i));
                }
                offset += batchSize;
                remaining -= batchSize;
            }

            for (int c = 0; c < compCount; c++) {
                if (!compInfos[c].isTableStorage()) continue;
                var compId = compInfos[c].id();
                var codec = binaryCodecs[c];
                if (allDirect && codec != null) {
                    for (int ci = 0; ci < numChunks; ci++) {
                        var soaArrays = chunkList[ci].componentStorage(compId).soaFieldArrays();
                        int size = chunkSizes[ci];
                        for (int slot = 0; slot < size; slot++)
                            codec.decodeDirect(in, soaArrays, slot);
                    }
                } else {
                    var fc = (ComponentCodec<Record>) getOrCreateCodec((Class<? extends Record>) types[c]);
                    for (int ci = 0; ci < numChunks; ci++) {
                        int size = chunkSizes[ci];
                        for (int slot = 0; slot < size; slot++)
                            chunkList[ci].set(compId, slot, fc.decode(in));
                    }
                }
            }

            long currentTick = world.currentTick();
            for (int ci = 0; ci < numChunks; ci++)
                chunkList[ci].bulkMarkAdded(currentTick);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> ComponentCodec<T> getOrCreateCodec(Class<T> type) {
        var custom = (ComponentCodec<T>) customCodecs.get(type);
        return custom != null ? custom : new BinaryCodec<>(type);
    }

    private record EntityRecord(Entity entity, List<TypedComponent> components) {}
    private record TypedComponent(Class<? extends Record> type, int typeIndex, Record value) {}
}
