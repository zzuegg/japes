package zzuegg.ecs.resource;

public final class ResMut<T> {

    private final ResourceStore.Entry<T> entry;

    ResMut(ResourceStore.Entry<T> entry) {
        this.entry = entry;
    }

    public T get() {
        return entry.value();
    }

    public void set(T value) {
        entry.setValue(value);
    }
}
