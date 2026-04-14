package zzuegg.ecs.persistence.h2;

import zzuegg.ecs.resource.Res;
import zzuegg.ecs.system.Exclusive;
import zzuegg.ecs.world.World;

/**
 * Auto-sync system that runs delta sync every tick.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Plugin mode</b> — via {@code addPlugin(H2PersistencePlugin.autoSync(url))}.
 *       The system lazily creates the plugin on first tick from
 *       {@link H2PersistencePlugin.DeferredH2Config}.</li>
 *   <li><b>Manual mode</b> — register an {@link H2PersistencePlugin} as a resource directly.</li>
 * </ul>
 */
public class H2AutoSyncSystem {

    private H2PersistencePlugin plugin;

    @zzuegg.ecs.system.System(stage = "Last")
    @Exclusive
    public void autoSync(World world) {
        if (plugin == null) {
            try {
                plugin = world.getResource(H2PersistencePlugin.class);
            } catch (Exception _) {
                var config = world.getResource(H2PersistencePlugin.DeferredH2Config.class);
                plugin = H2PersistencePlugin.create(world, config.jdbcUrl());
                world.setResource(plugin);
            }
        }
        plugin.syncChanged();
    }
}
