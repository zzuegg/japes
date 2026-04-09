package zzuegg.ecs.event;

import java.util.Collections;
import java.util.List;

public final class EventReader<T extends Record> {

    private final EventStore<T> store;

    EventReader(EventStore<T> store) {
        this.store = store;
    }

    public List<T> read() {
        return Collections.unmodifiableList(store.read());
    }
}
