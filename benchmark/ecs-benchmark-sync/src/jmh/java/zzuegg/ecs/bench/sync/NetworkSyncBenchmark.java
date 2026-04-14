package zzuegg.ecs.bench.sync;

import org.openjdk.jmh.annotations.*;
import zzuegg.ecs.component.NetworkSync;
import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.persistence.BinaryCodec;
import zzuegg.ecs.world.World;
import zzuegg.ecs.world.WorldAccessor;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Measures throughput of syncing ECS world state over real TCP sockets
 * between a server and client running in the same JVM.
 *
 * <p>Two benchmarks:
 * <ul>
 *   <li>{@code fullSync} — encode all @NetworkSync entities, send over TCP, decode on client</li>
 *   <li>{@code deltaSync} — mutate ~1% of entities, encode only changed, send, decode on client</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NetworkSyncBenchmark {

    // ---- Component types annotated for network sync ----

    @NetworkSync
    public record Position(float x, float y, float z) {}

    @NetworkSync
    public record Velocity(float vx, float vy, float vz) {}

    @NetworkSync
    public record Health(int hp) {}

    // ---- Parameters ----

    @Param({"1000", "10000"})
    int entityCount;

    // ---- State ----

    World serverWorld;
    World clientWorld;
    WorldAccessor serverAccessor;

    ServerSocket serverSocket;
    Socket serverSideSocket;
    Socket clientSideSocket;

    DataOutputStream serverOut;
    DataInputStream clientIn;
    // Reverse channel: client sends an ack byte so server knows client is done
    DataOutputStream clientOut;
    DataInputStream serverIn;

    BinaryCodec<Position> posCodec;
    BinaryCodec<Velocity> velCodec;
    BinaryCodec<Health> healthCodec;

    // For class name -> codec mapping on decode side
    Map<String, BinaryCodec<? extends Record>> codecByName;

    List<Entity> allEntities;
    int deltaCursor;

    // Pre-computed class names to avoid repeated Class.getName() in hot path
    static final String POS_NAME = Position.class.getName();
    static final String VEL_NAME = Velocity.class.getName();
    static final String HEALTH_NAME = Health.class.getName();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // -- Codecs --
        posCodec = new BinaryCodec<>(Position.class);
        velCodec = new BinaryCodec<>(Velocity.class);
        healthCodec = new BinaryCodec<>(Health.class);

        codecByName = new HashMap<>();
        codecByName.put(POS_NAME, posCodec);
        codecByName.put(VEL_NAME, velCodec);
        codecByName.put(HEALTH_NAME, healthCodec);

        // -- Server world --
        serverWorld = World.builder().build();
        allEntities = new ArrayList<>(entityCount);
        var rng = new Random(42);
        for (int i = 0; i < entityCount; i++) {
            var e = serverWorld.spawn(
                new Position(rng.nextFloat() * 100, rng.nextFloat() * 100, rng.nextFloat() * 100),
                new Velocity(rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f),
                new Health(100 + rng.nextInt(900))
            );
            allEntities.add(e);
        }
        serverAccessor = serverWorld.accessor();

        // -- Client world: seed with same entities so deltaSync can setComponent --
        clientWorld = World.builder().build();
        for (var e : allEntities) {
            var comps = serverAccessor.networkSyncComponents(e);
            clientWorld.spawnWithId(e, comps.toArray(new Record[0]));
        }

        // -- TCP connection --
        serverSocket = new ServerSocket(0); // random port
        var port = serverSocket.getLocalPort();

        // Accept in background thread
        var acceptThread = new Thread(() -> {
            try {
                serverSideSocket = serverSocket.accept();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        acceptThread.start();

        clientSideSocket = new Socket("127.0.0.1", port);
        acceptThread.join();

        // Buffered streams: server writes, client reads
        serverOut = new DataOutputStream(new BufferedOutputStream(serverSideSocket.getOutputStream(), 65536));
        clientIn = new DataInputStream(new BufferedInputStream(clientSideSocket.getInputStream(), 65536));

        // Reverse channel for ack
        clientOut = new DataOutputStream(new BufferedOutputStream(clientSideSocket.getOutputStream(), 4096));
        serverIn = new DataInputStream(new BufferedInputStream(serverSideSocket.getInputStream(), 4096));

        deltaCursor = 0;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (serverOut != null) serverOut.close();
        if (clientIn != null) clientIn.close();
        if (clientOut != null) clientOut.close();
        if (serverIn != null) serverIn.close();
        if (serverSideSocket != null) serverSideSocket.close();
        if (clientSideSocket != null) clientSideSocket.close();
        if (serverSocket != null) serverSocket.close();
        if (serverWorld != null) serverWorld.close();
        if (clientWorld != null) clientWorld.close();
    }

    // ---------------------------------------------------------------
    // Full sync: encode all entities, send over TCP, decode on client
    // ---------------------------------------------------------------

    @Benchmark
    public void fullSync() throws IOException {
        // --- Server side: encode ---
        serverOut.writeInt(allEntities.size());
        for (var entity : allEntities) {
            var components = serverAccessor.networkSyncComponents(entity);
            serverOut.writeLong(entity.id());
            serverOut.writeInt(components.size());
            for (var comp : components) {
                serverOut.writeUTF(comp.getClass().getName());
                encodeComponent(comp, serverOut);
            }
        }
        serverOut.flush();

        // --- Client side: decode ---
        clientWorld.clear();
        int count = clientIn.readInt();
        for (int i = 0; i < count; i++) {
            long entityId = clientIn.readLong();
            int compCount = clientIn.readInt();
            var components = new Record[compCount];
            for (int c = 0; c < compCount; c++) {
                String className = clientIn.readUTF();
                components[c] = decodeComponent(className, clientIn);
            }
            clientWorld.spawnWithId(new Entity(entityId), components);
        }

        // Ack so server knows client is done (ensures full round-trip)
        clientOut.writeByte(1);
        clientOut.flush();
        serverIn.readByte();
    }

    // ---------------------------------------------------------------
    // Delta sync: mutate ~1% of entities, encode only changed, send
    // ---------------------------------------------------------------

    @Benchmark
    public void deltaSync() throws IOException {
        int deltaCount = Math.max(1, entityCount / 100);

        // --- Server side: mutate ~1% of entities ---
        var changedEntities = new ArrayList<Entity>(deltaCount);
        for (int i = 0; i < deltaCount; i++) {
            var e = allEntities.get(deltaCursor);
            deltaCursor = (deltaCursor + 1) % allEntities.size();
            // Mutate position and health
            var pos = serverWorld.getComponent(e, Position.class);
            serverWorld.setComponent(e, new Position(pos.x() + 0.1f, pos.y() + 0.1f, pos.z() + 0.1f));
            serverWorld.setComponent(e, new Health(serverWorld.getComponent(e, Health.class).hp() - 1));
            changedEntities.add(e);
        }

        // --- Server side: encode only changed entities ---
        serverOut.writeInt(changedEntities.size());
        for (var entity : changedEntities) {
            var components = serverAccessor.networkSyncComponents(entity);
            serverOut.writeLong(entity.id());
            serverOut.writeInt(components.size());
            for (var comp : components) {
                serverOut.writeUTF(comp.getClass().getName());
                encodeComponent(comp, serverOut);
            }
        }
        serverOut.flush();

        // --- Client side: decode and apply updates ---
        int count = clientIn.readInt();
        for (int i = 0; i < count; i++) {
            long entityId = clientIn.readLong();
            int compCount = clientIn.readInt();
            for (int c = 0; c < compCount; c++) {
                String className = clientIn.readUTF();
                Record comp = decodeComponent(className, clientIn);
                clientWorld.setComponent(new Entity(entityId), comp);
            }
        }

        // Ack
        clientOut.writeByte(1);
        clientOut.flush();
        serverIn.readByte();
    }

    // ---------------------------------------------------------------
    // Codec helpers
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void encodeComponent(Record comp, DataOutput out) throws IOException {
        if (comp instanceof Position p) {
            posCodec.encode(p, out);
        } else if (comp instanceof Velocity v) {
            velCodec.encode(v, out);
        } else if (comp instanceof Health h) {
            healthCodec.encode(h, out);
        }
    }

    private Record decodeComponent(String className, DataInput in) throws IOException {
        @SuppressWarnings("unchecked")
        var codec = (BinaryCodec<? extends Record>) codecByName.get(className);
        if (codec == null) throw new IOException("Unknown component class: " + className);
        return codec.decode(in);
    }
}
