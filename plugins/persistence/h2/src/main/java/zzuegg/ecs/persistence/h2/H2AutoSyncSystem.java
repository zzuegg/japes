package zzuegg.ecs.persistence.h2;

import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.Exclusive;

/**
 * Optional auto-sync system that runs delta sync every tick.
 *
 * <p>Add this system to the world and register the {@link H2PersistencePlugin}
 * as a resource for automatic persistence on every tick:
 * <pre>{@code
 * var world = World.builder()
 *     .addSystem(H2AutoSyncSystem.class)
 *     .build();
 *
 * var h2 = H2PersistencePlugin.create(world, "jdbc:h2:./gamedata");
 * world.addResource(h2);
 * // h2.syncChanged() will be called every tick automatically
 * }</pre>
 */
public class H2AutoSyncSystem {

    @zzuegg.ecs.system.System(stage = "Last")
    @Exclusive
    public void autoSync(Res<H2PersistencePlugin> pluginRes) {
        pluginRes.get().syncChanged();
    }
}
