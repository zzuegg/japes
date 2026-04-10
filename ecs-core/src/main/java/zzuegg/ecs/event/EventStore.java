package zzuegg.ecs.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EventStore<T extends Record> {

    // Both fields guarded by 'this'. Previous implementation used a
    // CopyOnWriteArrayList for writeBuffer and reassigned the field in swap(),
    // which left a send() that started reading the old reference racing with
    // a swap() that replaced it — concurrently-sent events could land in a
    // detached buffer and vanish before the next swap promoted them.
    private List<T> writeBuffer = new ArrayList<>();
    private List<T> readBuffer = new ArrayList<>();

    public synchronized void send(T event) {
        writeBuffer.add(event);
    }

    /**
     * Returns a snapshot of the current read buffer. Callers must not mutate
     * the list; use the EventReader wrapper for safe iteration.
     */
    public synchronized List<T> read() {
        return Collections.unmodifiableList(new ArrayList<>(readBuffer));
    }

    public synchronized void swap() {
        // Every event that was visible to send() before this swap lands in
        // the new readBuffer. New sends after this point go into the fresh
        // writeBuffer. Both transitions happen under the same lock so no
        // event can slip through.
        readBuffer = writeBuffer;
        writeBuffer = new ArrayList<>();
    }

    public EventWriter<T> writer() {
        return new EventWriter<>(this);
    }

    public EventReader<T> reader() {
        return new EventReader<>(this);
    }
}
