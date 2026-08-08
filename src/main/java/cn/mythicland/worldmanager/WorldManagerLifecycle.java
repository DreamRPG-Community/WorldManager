package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.bootstrap.LibPluginLifecycle;
import cn.mythicland.lib.bootstrap.annotation.InjectComponent;
import cn.mythicland.lib.command.CommandRouter;
import cn.mythicland.lib.config.ConfigSupport;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns WorldManager construction, service registration, command binding, and startup discovery.
 */
@InjectComponent
public final class WorldManagerLifecycle implements LibPluginLifecycle {

    private final WorldManagerPlugin plugin;
    private final LibApi lib;
    private WorldManagerService service;

    /**
     * Creates the lifecycle module from Lib-provided dependencies.
     *
     * @param plugin plugin entry point
     * @param lib shared Lib service
     */
    public WorldManagerLifecycle(WorldManagerPlugin plugin, LibApi lib) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lib = Objects.requireNonNull(lib, "lib");
    }

    /**
     * Restores the initial world, registers the API and command, and starts discovery.
     */
    @Override
    public void enable() {
        FileConfiguration configuration = ConfigSupport.loadDefault(plugin);
        WorldManagerSettings settings = WorldManagerSettingsLoader.load(plugin, configuration);
        WorldManagerSettingsLoader.ensureDirectories(settings);
        service = new WorldManagerService(plugin, lib, settings);
        try {
            service.restoreInitialWorld();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not restore initial world '" + settings.initialWorldName() + "'",
                    exception
            );
        }

        plugin.getServer().getServicesManager().register(
                WorldManagerApi.class,
                service,
                plugin,
                ServicePriority.Normal
        );
        registerCommand();
        lib.runLater(1L, () -> service.discoverAndLoadAll().whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning(
                        "Startup world loading finished with failures: " + LibApi.rootCauseMessage(error)
                );
                return;
            }
            plugin.getLogger().info("Startup world discovery and loading completed.");
        }));
    }

    /**
     * Reloads runtime configuration and re-scans persistent world snapshots.
     *
     * <p>World path settings remain immutable for the server lifetime. A path or initial-world
     * change requires a restart, while runtime options and newly added snapshot directories can
     * be loaded here.</p>
     */
    @Override
    public void reload() {
        if (service == null) throw new IllegalStateException("WorldManager service is unavailable");
        service.reload().whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning(
                        "WorldManager configuration reload finished with failures: "
                                + LibApi.rootCauseMessage(error)
                );
                return;
            }
            plugin.getLogger().info("WorldManager configuration and snapshot reload completed.");
        });
    }

    /**
     * Unregisters the API and closes managed world resources.
     */
    @Override
    public void disable() {
        if (service == null) return;
        plugin.getServer().getServicesManager().unregister(WorldManagerApi.class, service);
        service.close();
        service = null;
    }

    private void registerCommand() {
        PluginCommand command = plugin.getCommand("worldmanager");
        if (command == null) {
            throw new IllegalStateException("worldmanager command is missing from plugin.yml");
        }

        CommandRouter router = lib.createCommandRouter(plugin, "worldmanager");
        WorldManagerCommand.register(router, service, lib);
        command.setExecutor(router);
        command.setTabCompleter(router);
    }

}
