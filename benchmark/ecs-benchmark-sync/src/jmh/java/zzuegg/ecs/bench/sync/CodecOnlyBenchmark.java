package zzuegg.ecs.bench.sync;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.Persistent;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.persistence.GroupedWorldSerializer;
import zzuegg.ecs.persistence.WorldSerializer;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CodecOnlyBenchmark {

    @Persistent public record Position(float x, float y, float z) {}
    @Persistent public record Velocity(float vx, float vy, float vz) {}
    @Persistent public record Health(int hp) {}

    @Param({"1000", "10000", "100000"})
    int entityCount;

    World world;
    List<Entity> entities;
    WorldAccessor accessor;

    BinaryCodec<Position> posCodec;
    BinaryCodec<Velocity> velCodec;
    BinaryCodec<Health> healthCodec;

    GroupedWorldSerializer serializer;
    WorldSerializer columnarSerializer;

    ByteArrayOutputStream baos;
    DataOutputStream dos;

    byte[] groupedSnapshot;
    byte[] columnarSnapshot;

    int cursor;

    @Setup(Level.Iteration)
    public void setup() throws IOException {
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
        world.tick();
        accessor = world.accessor();

        posCodec = new BinaryCodec<>(Position.class);
        velCodec = new BinaryCodec<>(Velocity.class);
        healthCodec = new BinaryCodec<>(Health.class);
        serializer = new GroupedWorldSerializer();
        columnarSerializer = new WorldSerializer();
        baos = new ByteArrayOutputStream(entityCount * 64);
        dos = new DataOutputStream(baos);

        groupedSnapshot = encodeGrouped();
        columnarSnapshot = encodeColumnar();
        cursor = 0;
    }

    @TearDown(Level.Iteration)
    public void tearDown() {
        if (world != null) world.close();
    }

    @Benchmark
    public byte[] fullEncode() throws IOException {
        return encodeGrouped();
    }

    @Benchmark
    public int fullDecode() throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(groupedSnapshot));
        serializer.load(world, in);
        return entityCount;
    }

    @Benchmark
    public int fullDecodeColumnar() throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(columnarSnapshot));
        columnarSerializer.loadColumnar(world, in);
        return entityCount;
    }

    @Benchmark
    public byte[] deltaEncode() throws IOException {
        int batch = Math.max(1, entityCount / 100);
        for (int i = 0; i < batch; i++) {
            var e = entities.get(cursor);
            cursor = (cursor + 1) % entities.size();
            var pos = world.getComponent(e, Position.class);
            world.setComponent(e, new Position(pos.x() + 0.1f, pos.y() + 0.1f, pos.z() + 0.1f));
        }

        baos.reset();
        dos.writeInt(batch);
        int base = (cursor - batch + entities.size()) % entities.size();
        for (int i = 0; i < batch; i++) {
            var e = entities.get((base + i) % entities.size());
            var comps = accessor.persistentComponents(e);
            dos.writeLong(e.id());
            dos.writeInt(comps.size());
            for (var comp : comps) {
                writeTaggedComponent(comp, dos);
            }
        }
        dos.flush();
        return baos.toByteArray();
    }

    private byte[] encodeColumnar() throws IOException {
        baos.reset();
        columnarSerializer.saveColumnar(world, dos);
        dos.flush();
        return baos.toByteArray();
    }

    private byte[] encodeGrouped() throws IOException {
        baos.reset();
        serializer.save(world, dos);
        dos.flush();
        return baos.toByteArray();
    }

    private void writeTaggedComponent(Record comp, DataOutput out) throws IOException {
        switch (comp) {
            case Position p -> { out.writeByte(0); posCodec.encode(p, out); }
            case Velocity v -> { out.writeByte(1); velCodec.encode(v, out); }
            case Health h   -> { out.writeByte(2); healthCodec.encode(h, out); }
            default -> throw new IllegalArgumentException("Unknown: " + comp.getClass());
        }
    }

    private Record decodeByTag(byte tag, DataInput in) throws IOException {
        return switch (tag) {
            case 0 -> posCodec.decode(in);
            case 1 -> velCodec.decode(in);
            case 2 -> healthCodec.decode(in);
            default -> throw new IOException("Unknown tag: " + tag);
        };
    }
}
