package zzuegg.ecs.entity;

public record Entity(long id) {

    public static final Entity NULL = new Entity(pack(-1, 0));

    public static Entity of(int index, int generation) {
        return new Entity(pack(index, generation));
    }

    public int index() {
        return (int) (id >>> 32);
    }

    public int generation() {
        return (int) id;
    }

    @Override
    public String toString() {
        return "Entity(" + index() + "v" + generation() + ")";
    }

    private static long pack(int index, int generation) {
        return ((long) index << 32) | Integer.toUnsignedLong(generation);
    }
}
