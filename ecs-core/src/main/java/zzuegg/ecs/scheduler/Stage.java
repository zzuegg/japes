package zzuegg.ecs.scheduler;

public record Stage(String name, int order) implements Comparable<Stage> {

    public static final Stage FIRST = new Stage("First", 0);
    public static final Stage PRE_UPDATE = new Stage("PreUpdate", 100);
    public static final Stage UPDATE = new Stage("Update", 200);
    public static final Stage POST_UPDATE = new Stage("PostUpdate", 300);
    public static final Stage LAST = new Stage("Last", 400);

    public static Stage after(String referenceStageName) {
        int refOrder = switch (referenceStageName) {
            case "First" -> 0;
            case "PreUpdate" -> 100;
            case "Update" -> 200;
            case "PostUpdate" -> 300;
            case "Last" -> 400;
            default -> 500;
        };
        return new Stage("after:" + referenceStageName, refOrder + 50);
    }

    @Override
    public int compareTo(Stage other) {
        return Integer.compare(this.order, other.order);
    }
}
