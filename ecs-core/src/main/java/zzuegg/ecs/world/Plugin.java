package zzuegg.ecs.world;

/**
 * A bundle of systems, resources, and configuration that can be
 * installed into a {@link WorldBuilder} as a single unit.
 *
 * <pre>{@code
 * var world = World.builder()
 *     .addPlugin(myPlugin)
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface Plugin {
    void install(WorldBuilder builder);
}
