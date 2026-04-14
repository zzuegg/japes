package zzuegg.ecs.persistence;

import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * Binary save/load for a {@link World}. Serializes all entities and their
 * {@link Persistent}-annotated components using auto-derived
 * {@link BinaryCodec}s (or custom {@link ComponentCodec} overrides).
 *
 * <h3>Binary format</h3>
 * <pre>
 * MAGIC (4 bytes: "JAPS")
 * VERSION (int: 1)
 * componentTypeCount (int)
 *   for each: className (UTF), componentTypeIndex (int)
 * entityCount (int)
 *   for each entity:
 *     entityId (long)
 *     componentCount (int)
 *       for each component:
 *         componentTypeIndex (int)
 *         [component fields via codec]
 * </pre>
 */
public final class WorldSerializer {

    private static final int MAGIC = 0x4A415053; // "JAPS"
    private static final int VERSION = 1;

    private final Map<Class<? extends Record>, ComponentCodec<?>> customCodecs = new HashMap<>();

    public <T extends Record> void registerCodec(ComponentCodec<T> codec) {
        customCodecs.put(codec.type(), codec);
    }

    /** Save all entities with @Persistent components. */
    public void save(World world, DataOutput out) throws IOException {
        save(world, out, type -> type.isAnnotationPresent(Persistent.class));
    }

    public void save(World world, DataOutput out, Predicate<Class<?>> componentFilter) throws IOException {
        var accessor = world.accessor();

        // First pass: discover all entity/component data and collect types
        var typeToIndex = new LinkedHashMap<Class<? extends Record>, Integer>();
        var codecByType = new HashMap<Class<? extends Record>, ComponentCodec<?>>();
        var entityData = new ArrayList<EntityRecord>();

        for (var entity : accessor.allEntities()) {
            var types = accessor.componentTypes(entity);
            var components = new ArrayList<TypedComponent>();
            for (var type : types) {
                if (!componentFilter.test(type)) continue;
                if (!typeToIndex.containsKey(type)) {
                    int idx = typeToIndex.size();
                    typeToIndex.put(type, idx);
                    codecByType.put(type, getOrCreateCodec(type));
                }
                var value = accessor.getComponent(entity, type);
                components.add(new TypedComponent(type, typeToIndex.get(type), value));
            }
            if (!components.isEmpty()) {
                entityData.add(new EntityRecord(entity, components));
            }
        }

        out.writeInt(MAGIC);
        out.writeInt(VERSION);

        // Write component type table
        out.writeInt(typeToIndex.size());
        for (var entry : typeToIndex.entrySet()) {
            out.writeUTF(entry.getKey().getName());
            out.writeInt(entry.getValue());
        }

        // Write entities
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

    /** Load entities from a save stream. Clears the world first. */
    public void load(World world, DataInput in) throws IOException {
        world.clear();

        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Invalid save file magic: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Unsupported save version: " + version);

        // Read component type table
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
                throw new IOException("Component class not found: " + className, e);
            }
        }

        // Read entities
        int entityCount = in.readInt();
        for (int i = 0; i < entityCount; i++) {
            long entityId = in.readLong();
            int compCount = in.readInt();

            var components = new Record[compCount];
            for (int c = 0; c < compCount; c++) {
                int typeIndex = in.readInt();
                @SuppressWarnings("unchecked")
                var codec = (ComponentCodec<Record>) codecByIndex.get(typeIndex);
                if (codec == null) throw new IOException("Unknown component type index: " + typeIndex);
                components[c] = codec.decode(in);
            }

            world.spawnWithId(new Entity(entityId), components);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Record> ComponentCodec<T> getOrCreateCodec(Class<T> type) {
        var custom = (ComponentCodec<T>) customCodecs.get(type);
        if (custom != null) return custom;
        return new BinaryCodec<>(type);
    }

    private record EntityRecord(Entity entity, List<TypedComponent> components) {}
    private record TypedComponent(Class<? extends Record> type, int typeIndex, Record value) {}
}
