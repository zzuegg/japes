package zzuegg.ecs.resource;

public final class Res<T> {

    private final ResourceStore.Entry<T> entry;

    Res(ResourceStore.Entry<T> entry) {
        this.entry = entry;
    }

    public T get() {
        return entry.value();
    }
}
