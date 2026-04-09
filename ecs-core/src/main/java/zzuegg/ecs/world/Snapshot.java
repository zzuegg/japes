package zzuegg.ecs.world;

import zzuegg.ecs.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

public final class Snapshot {

    public record SnapshotEntry(Entity entity, Record[] components) {}

    private final List<SnapshotEntry> entries;

    Snapshot(List<SnapshotEntry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public List<SnapshotEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    @SuppressWarnings("unchecked")
    public <A extends Record, B extends Record> void forEach(Class<A> typeA, Class<B> typeB, BiConsumer<A, B> consumer) {
        for (var entry : entries) {
            A a = null;
            B b = null;
            for (var comp : entry.components()) {
                if (typeA.isInstance(comp)) a = (A) comp;
                if (typeB.isInstance(comp)) b = (B) comp;
            }
            if (a != null && b != null) {
                consumer.accept(a, b);
            }
        }
    }
}
