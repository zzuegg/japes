# Persistence and sync

All persistence and sync code in japes runs **outside** the hot tick path.
The serialization APIs allocate freely, use reflection, and stream through
`DataOutput` / `DataInput` -- none of that touches the system iteration
loop, so your tick's escape analysis profile stays flat.

!!! tip "Zero EA impact"
    `WorldSerializer`, `GroupedWorldSerializer`, `WorldAccessor`, and every
    codec are designed to be called between ticks (or on a background
    thread). They never participate in the per-tick scheduling graph.

## Marking components for save and sync

Two annotations control which components participate in persistence and
network synchronization:

```java
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.component.NetworkSync;

@Persistent
public record Position(float x, float y) {}

@Persistent
public record Health(int hp) {}

@NetworkSync
public record Velocity(float dx, float dy) {}

// Both: persisted AND synced over the network
@NetworkSync @Persistent
public record Transform(float x, float y, float rotation) {}

// Neither: excluded from saves and sync
public record AiState(String behavior) {}
```

- `@Persistent` -- included in save/load by default. The serializers use
  `type.isAnnotationPresent(Persistent.class)` as their default filter.
- `@NetworkSync` -- included in network-sync queries via `WorldAccessor`.
- A component can carry both annotations.
- Components **without** `@Persistent` are silently excluded from saves.

Both annotations are `@Retention(RUNTIME)` and `@Target(TYPE)`, so they
go on the record declaration itself.

## `world.save()` / `world.load()`

The simplest path: call `save` to write every `@Persistent` component to
a `DataOutput`, then `load` to restore the world from a `DataInput`.

```java
import java.io.*;

var world = World.builder().build();
var e1 = world.spawn(new Position(1.5f, 2.5f));
var e2 = world.spawn(new Position(3, 4), new Health(100));

// Save to a byte array
var buf = new ByteArrayOutputStream();
world.save(new DataOutputStream(buf));
byte[] bytes = buf.toByteArray();

// Load into a fresh world (or the same one -- it clears first)
var world2 = World.builder().build();
world2.load(new DataInputStream(new ByteArrayInputStream(bytes)));

// Entity IDs are preserved exactly
assert world2.isAlive(e1);
assert world2.isAlive(e2);
assert world2.getComponent(e1, Position.class).equals(new Position(1.5f, 2.5f));
assert world2.getComponent(e2, Health.class).equals(new Health(100));
```

!!! info "Entity IDs are preserved"
    `save` / `load` round-trip the exact entity ID (index + generation).
    After loading, `world2.isAlive(e1)` returns `true` and you can look up
    the same `Entity` handle you had before the save.

You can also pass a custom filter to save a subset of component types:

```java
// Only save Position, not Health
world.save(new DataOutputStream(buf), type -> type == Position.class);
```

## `world.clear()`

Both `load` and `loadGrouped` call `world.clear()` before restoring
entities. You can also call it yourself to reset the world to an empty
state:

```java
world.clear();
assert world.entityCount() == 0;
```

`clear()` wipes all entities, all archetype storage, and resets the entity
allocator. System registrations, event registrations, and resources are
**not** affected.

## `WorldAccessor` -- read-only view for plugins

`WorldAccessor` is the bridge between the ECS internals and your
out-of-tick code (serializers, sync plugins, debug tools). Obtain one from
any world:

```java
WorldAccessor accessor = world.accessor();
```

Key methods:

| Method | Description |
|--------|-------------|
| `allEntities()` | Iterate every live entity |
| `allEntities(Consumer<Entity>)` | Same, consumer form (no intermediate list) |
| `persistentEntities()` | Entities with at least one `@Persistent` component |
| `forEachPersistentEntity(Consumer<Entity>)` | Allocation-free variant |
| `forEachPersistentEntityComponent(BiConsumer<Entity, Record>)` | Entity + each persistent component |
| `persistentComponents(Entity)` | List of `@Persistent` components for one entity |
| `forEachPersistentComponent(Entity, Consumer<Record>)` | Allocation-free variant |
| `networkSyncComponents(Entity)` | List of `@NetworkSync` components for one entity |
| `forEachNetworkSyncComponent(Entity, Consumer<Record>)` | Allocation-free variant |
| `getComponent(Entity, Class<T>)` | Get a single component (returns null if missing) |
| `componentTypes(Entity)` | All component types for an entity |
| `entitiesWith(Class<? extends Record>...)` | All entities matching the given component types |

`WorldAccessor` is intentionally **not** on the system iteration hot path
and has no impact on escape analysis.

## `WorldSerializer` and `GroupedWorldSerializer`

japes ships two serializer implementations. Both write a binary format to
`DataOutput` and read from `DataInput`.

### `WorldSerializer` (entity-by-entity)

The basic serializer writes each entity one at a time. Simple and
correct, but pays archetype-resolution costs per entity on load.

```java
import zzuegg.ecs.persistence.WorldSerializer;

var serializer = new WorldSerializer();

// Save
var buf = new ByteArrayOutputStream();
serializer.save(world, new DataOutputStream(buf));

// Load
serializer.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));
```

`WorldSerializer` also offers a **grouped** format via `saveGrouped` /
`loadGrouped` that groups entities by archetype in the wire format:

```java
serializer.saveGrouped(world, new DataOutputStream(buf));
serializer.loadGrouped(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));
```

### `GroupedWorldSerializer` (archetype-grouped)

A standalone serializer that writes the grouped format natively. On load,
archetype resolution (HashSet, ArchetypeId, ComponentInfo lookup) happens
**once per group** instead of once per entity. For worlds with thousands
of entities this is significantly faster.

```java
import zzuegg.ecs.persistence.GroupedWorldSerializer;

var grouped = new GroupedWorldSerializer();

// Save
var buf = new ByteArrayOutputStream();
grouped.save(world, new DataOutputStream(buf));

// Load -- clears world first, uses BulkSpawnWithIdBuilder internally
grouped.load(world2, new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));
```

!!! tip "Prefer the grouped format for performance"
    The grouped format resolves the target archetype once per group and
    uses `BulkSpawnWithIdBuilder` with `decodeDirect` to write primitives
    straight into SoA backing arrays. For bulk loads this eliminates most
    per-entity allocation.

Both serializers accept a custom component filter:

```java
grouped.save(world, out, type -> type.isAnnotationPresent(Persistent.class));
```

The default filter is `@Persistent`.

### Columnar format

`WorldSerializer` also offers a columnar (v3) format via `saveColumnar` /
`loadColumnar`, available directly on `World`:

```java
world.saveColumnar(new DataOutputStream(buf));
world2.loadColumnar(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));
```

The columnar format writes all entity IDs first, then all values for each
component column. This layout is friendlier to the SoA decode path.

## `BulkSpawnWithIdBuilder`

When you need to restore many entities that share the same component shape
(the common case for persistence), `BulkSpawnWithIdBuilder` eliminates
per-entity archetype resolution:

```java
var builder = world.bulkSpawnWithIdBuilder(Position.class, Health.class);

for (int i = 0; i < 10_000; i++) {
    builder.spawnWithId(
        new Entity(savedIds[i]),
        new Position(savedX[i], savedY[i]),
        new Health(savedHp[i])
    );
}
```

The builder caches the target archetype, chunk, and per-component storage
references. The only per-entity work is allocating the entity slot and
writing component data.

For zero-allocation loads, use the direct SoA path:

```java
var builder = world.bulkSpawnWithIdBuilder(Position.class, Health.class);
BinaryCodec<Position> posCodec = new BinaryCodec<>(Position.class);
BinaryCodec<Health> hpCodec = new BinaryCodec<>(Health.class);

for (int i = 0; i < entityCount; i++) {
    int slot = builder.allocateSlot(new Entity(in.readLong()));
    posCodec.decodeDirect(in, builder.soaArrays(0), slot);
    hpCodec.decodeDirect(in, builder.soaArrays(1), slot);
    builder.markAdded(slot);
}
```

This is exactly what `GroupedWorldSerializer.load` does internally.

## `BinaryCodec` and `ComponentCodec`

### Auto-derived `BinaryCodec`

`BinaryCodec<T>` auto-derives a binary codec from a record's field
structure via reflection. It handles all primitive types (`byte`, `short`,
`int`, `long`, `float`, `double`, `boolean`, `char`) and nested records
recursively:

```java
import zzuegg.ecs.persistence.BinaryCodec;

var codec = new BinaryCodec<>(Position.class);

// Encode
codec.encode(new Position(1.5f, 2.5f), dataOutput);

// Decode
Position pos = codec.decode(dataInput);
```

!!! warning "Reference fields are not supported"
    `BinaryCodec` only handles primitives and nested records whose leaf
    fields are all primitives. If your component has a `String`, `List`,
    or any reference-type field, you must provide a custom
    `ComponentCodec`.

### Custom `ComponentCodec` SPI

Implement `ComponentCodec<T>` for types that need custom serialization
(compressed formats, schema evolution, reference-type fields):

```java
import zzuegg.ecs.persistence.ComponentCodec;

public class NameCodec implements ComponentCodec<Name> {

    @Override
    public void encode(Name value, DataOutput out) throws IOException {
        out.writeUTF(value.text());
    }

    @Override
    public Name decode(DataInput in) throws IOException {
        return new Name(in.readUTF());
    }

    @Override
    public Class<Name> type() {
        return Name.class;
    }
}
```

Register custom codecs on the serializer before saving or loading:

```java
var serializer = new GroupedWorldSerializer();
serializer.registerCodec(new NameCodec());
serializer.save(world, out);
```

If no custom codec is registered for a type, the serializer falls back to
`BinaryCodec`.

## `decodeDirect` / `encodeDirect` -- zero-allocation SoA paths

When a component type is SoA-eligible (all-primitive leaf fields),
`BinaryCodec` can read and write directly to/from the SoA backing arrays
without creating intermediate Record objects:

```java
var codec = new BinaryCodec<>(Position.class);

// Check eligibility
assert codec.supportsDirectDecode();
assert codec.supportsDirectEncode();

// Decode: read from stream into SoA arrays at the given slot
codec.decodeDirect(dataInput, soaArrays, slot);

// Encode: write from SoA arrays at the given slot to stream
codec.encodeDirect(soaArrays, slot, dataOutput);
```

- `decodeDirect` reads primitive values from the `DataInput` and writes
  them directly into the per-field primitive arrays (e.g., `float[]` for
  `Position.x`, `float[]` for `Position.y`).
- `encodeDirect` reads from the SoA arrays and writes to the `DataOutput`.
- No Record object is allocated in either direction.

The grouped and columnar serializers use these paths automatically when
the codec reports `supportsDirectDecode()` / `supportsDirectEncode()`.

## Building a sync plugin

Here is a minimal network-sync system that uses `Res<WorldAccessor>` (or
obtains the accessor directly) to encode `@NetworkSync` components into a
byte buffer. This runs outside the tick loop -- for example, in a
`"PostUpdate"` stage or on a timer after `world.tick()`.

```java
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.persistence.ComponentCodec;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.*;
import java.util.*;

public class SyncPlugin {

    private final Map<Class<? extends Record>, ComponentCodec<?>> codecs = new HashMap<>();

    @SuppressWarnings("unchecked")
    private <T extends Record> ComponentCodec<T> codec(Class<T> type) {
        return (ComponentCodec<T>) codecs.computeIfAbsent(type,
            t -> new BinaryCodec<>((Class<? extends Record>) t));
    }

    /** Encode a full snapshot of all @NetworkSync entities. */
    @SuppressWarnings("unchecked")
    public byte[] encodeSnapshot(World world) throws IOException {
        var accessor = world.accessor();
        var buf = new ByteArrayOutputStream();
        var out = new DataOutputStream(buf);

        var entities = new ArrayList<Entity>();
        for (var entity : accessor.allEntities()) {
            if (!accessor.networkSyncComponents(entity).isEmpty()) {
                entities.add(entity);
            }
        }

        out.writeInt(entities.size());
        for (var entity : entities) {
            out.writeLong(entity.id());
            var syncComps = accessor.networkSyncComponents(entity);
            out.writeInt(syncComps.size());
            for (var comp : syncComps) {
                out.writeUTF(comp.getClass().getName());
                ((ComponentCodec<Record>) codec(comp.getClass())).encode(comp, out);
            }
        }

        out.flush();
        return buf.toByteArray();
    }

    /** Apply a snapshot to a client world. */
    @SuppressWarnings("unchecked")
    public void applySnapshot(World clientWorld, byte[] data) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(data));
        int entityCount = in.readInt();

        for (int i = 0; i < entityCount; i++) {
            long entityId = in.readLong();
            int compCount = in.readInt();
            var components = new Record[compCount];
            for (int c = 0; c < compCount; c++) {
                var type = (Class<? extends Record>) Class.forName(in.readUTF());
                components[c] = codec(type).decode(in);
            }

            var entity = new Entity(entityId);
            if (clientWorld.isAlive(entity)) {
                for (var comp : components) {
                    clientWorld.setComponent(entity, comp);
                }
            } else {
                clientWorld.spawnWithId(entity, components);
            }
        }
    }
}
```

The key pattern:

1. Obtain a `WorldAccessor` via `world.accessor()`.
2. Use `networkSyncComponents(entity)` to get only the `@NetworkSync`
   components for each entity.
3. Encode with `BinaryCodec` (or a custom `ComponentCodec`).
4. On the receiving side, use `spawnWithId` for new entities and
   `setComponent` for updates.

Because `WorldAccessor` and the codecs live outside the scheduling graph,
they have zero impact on your tick performance.

## Quick recap

- `@Persistent` marks components for save/load; `@NetworkSync` marks them
  for network sync. A component can carry both.
- `world.save(out)` / `world.load(in)` are the one-liner convenience
  methods. They use `WorldSerializer` internally.
- `world.clear()` resets all entity and archetype state.
- `WorldAccessor` provides a read-only view for out-of-tick code --
  serializers, sync plugins, debug tools.
- `GroupedWorldSerializer` groups entities by archetype for faster
  bulk loads. Prefer it over the entity-by-entity format.
- `BulkSpawnWithIdBuilder` caches archetype resolution for bulk spawns
  with preserved entity IDs.
- `BinaryCodec` auto-derives from record fields. Use `ComponentCodec`
  for custom types (e.g., `String` fields, compressed formats).
- `decodeDirect` / `encodeDirect` bypass Record allocation entirely for
  SoA-eligible types.
- Entity IDs are preserved across save/load -- the exact same `Entity`
  handles work after restore.

## What's next

- [Events](09-events.md) -- cross-system communication.
- [Commands](../basics/08-commands.md) -- deferred structural edits from inside systems.
