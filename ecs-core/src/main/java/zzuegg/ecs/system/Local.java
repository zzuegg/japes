package zzuegg.ecs.system;

public final class Local<T> {

    private T value;

    public Local(T initial) {
        this.value = initial;
    }

    public Local() {
        this(null);
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
