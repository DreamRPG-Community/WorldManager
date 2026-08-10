package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.bootstrap.LibPluginLifecycle;
import cn.mythicland.lib.bootstrap.PluginTaskScope;
import cn.mythicland.lib.bootstrap.annotation.LifecycleComponent;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns WorldManager construction, service registration, command binding, and startup discovery.
 */
@LifecycleComponent
public final class WorldManagerLifecycle implements LibPluginLifecycle {

    private final WorldManagerPlugin plugin;
    private final LibApi lib;
    private final PluginTaskScope tasks;
    private final WorldManagerConfiguration configuration;
    private WorldManagerService service;
    private BukkitTask startupTask;
    private CompletableFuture<Void> reloadFuture = CompletableFuture.completedFuture(null);

    /**
     * Creates the lifecycle module from Lib-provided dependencies.
     *
     * @param plugin plugin entry point
     * @param lib    shared Lib service
     */
    public WorldManagerLifecycle(
            WorldManagerPlugin plugin,
            LibApi lib,
            PluginTaskScope tasks,
            WorldManagerConfiguration configuration
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lib = Objects.requireNonNull(lib, "lib");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Restores the initial world, registers the API and command, and starts discovery.
     */
    @Override
    public void enable() {
        WorldManagerSettings settings = configuration.snapshot();
        WorldManagerSettingsLoader.ensureDirectories(settings);
        service = new WorldManagerService(plugin, lib, settings, configuration);
        try {
            service.restoreInitialWorld();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not restore initial world '" + settings.initialWorldName() + "'",
                    exception
            );
        }

        startupTask = tasks.runLater(1L, () -> service.discoverAndLoadAll().whenComplete((ignored, error) -> {
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
        reloadFuture = reloadConfiguration();
    }

    CompletableFuture<Void> reloadConfiguration() {
        if (service == null) return CompletableFuture.failedFuture(
                new IllegalStateException("WorldManager service is unavailable")
        );
        CompletableFuture<Void> current = service.reload();
        current.whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().warning(
                        "WorldManager configuration reload finished with failures: "
                                + LibApi.rootCauseMessage(error)
                );
                return;
            }
            plugin.getLogger().info("WorldManager configuration and snapshot reload completed.");
        });
        return current;
    }

    CompletableFuture<Void> lastReload() {
        return reloadFuture;
    }

    /**
     * Unregisters the API and closes managed world resources.
     */
    @Override
    public void disable() {
        tasks.cancel(startupTask);
        startupTask = null;
        if (service == null) return;
        service.close();
        service = null;
    }

    /**
     * Returns the active world manager service to annotation-driven commands.
     *
     * @return active world manager service
     */
    public WorldManagerApi service() {
        return Objects.requireNonNull(service, "WorldManager service is unavailable");
    }

}
