package zzuegg.ecs.event;

public final class EventWriter<T extends Record> {

    private final EventStore<T> store;

    EventWriter(EventStore<T> store) {
        this.store = store;
    }

    public void send(T event) {
        store.send(event);
    }
}
