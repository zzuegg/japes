package zzuegg.ecs.component;

public record ComponentId(int id) implements Comparable<ComponentId> {
    @Override
    public int compareTo(ComponentId other) {
        return Integer.compare(this.id, other.id);
    }
}
