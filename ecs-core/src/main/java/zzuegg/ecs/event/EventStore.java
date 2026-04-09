package zzuegg.ecs.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventStore<T extends Record> {

    private List<T> writeBuffer = new CopyOnWriteArrayList<>();
    private List<T> readBuffer = new ArrayList<>();

    public void send(T event) {
        writeBuffer.add(event);
    }

    public List<T> read() {
        return readBuffer;
    }

    public void swap() {
        readBuffer = new ArrayList<>(writeBuffer);
        writeBuffer = new CopyOnWriteArrayList<>();
    }

    public EventWriter<T> writer() {
        return new EventWriter<>(this);
    }

    public EventReader<T> reader() {
        return new EventReader<>(this);
    }
}
