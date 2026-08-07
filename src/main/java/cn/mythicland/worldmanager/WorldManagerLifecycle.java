package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.bootstrap.LibPluginLifecycle;
import cn.mythicland.lib.bootstrap.annotation.InjectComponent;
import cn.mythicland.lib.command.CommandRouter;
import cn.mythicland.lib.config.ConfigSupport;
import cn.mythicland.lib.path.SafePathResolver;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns WorldManager construction, service registration, command binding, and startup discovery.
 */
@InjectComponent
public final class WorldManagerLifecycle implements LibPluginLifecycle {

    private static final String DEFAULT_WORLD_DIRECTORY = "worlds";
    private static final String LEGACY_INTERNAL_DIRECTORY = ".worldmanager";
    private static final String INTERNAL_RUNTIME_DIRECTORY = ".runtime";
    private static final String DEFAULT_INITIAL_WORLD_NAME = "world";
    private static final String DEFAULT_FALLBACK_WORLD = "world";

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
        WorldManagerSettings settings = loadSettings(configuration);
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
     * WorldManager keeps immutable world settings for the server lifetime.
     */
    @Override
    public void reload() {
        throw new UnsupportedOperationException("WorldManager does not support runtime reload");
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

    @Nonnull
    private static Path resolveWorldDirectory(String configuredDirectory, Path pluginDataDirectory) {
        Path relativeDirectory;
        try {
            relativeDirectory = Path.of(configuredDirectory);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid world-directory: " + configuredDirectory, exception);
        }

        if (relativeDirectory.isAbsolute() || relativeDirectory.getNameCount() == 0) {
            throw new IllegalArgumentException("world-directory must be relative to the plugin data directory");
        }
        for (Path segment : relativeDirectory) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("world-directory cannot contain '..'");
            }
            if (segment.toString().equals(LEGACY_INTERNAL_DIRECTORY)
                    || segment.toString().equals(INTERNAL_RUNTIME_DIRECTORY)) {
                throw new IllegalArgumentException(
                        "world-directory cannot use the reserved internal directory"
                );
            }
        }

        Path worldsRoot = pluginDataDirectory.resolve(relativeDirectory).normalize();
        if (!worldsRoot.startsWith(pluginDataDirectory) || worldsRoot.equals(pluginDataDirectory)) {
            throw new IllegalArgumentException("world-directory must stay below the plugin data directory");
        }
        return worldsRoot;
    }

    @Nonnull
    private static Path resolveRuntimeDirectory(Path pluginDataDirectory) {
        Path path = pluginDataDirectory.resolve(INTERNAL_RUNTIME_DIRECTORY).normalize();
        if (!path.startsWith(pluginDataDirectory)
                || path.equals(pluginDataDirectory)
                || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Internal WorldManager path is invalid");
        }
        return path;
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

    private WorldManagerSettings loadSettings(FileConfiguration configuration) {
        String configuredDirectory = ConfigSupport.getString(
                plugin,
                configuration,
                "world-directory",
                DEFAULT_WORLD_DIRECTORY
        );
        Path serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path pluginDataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        SafePathResolver serverPathResolver = new SafePathResolver(serverRoot);
        String initialWorldName = ConfigSupport.getString(
                plugin,
                configuration,
                "initial-world-name",
                DEFAULT_INITIAL_WORLD_NAME
        );
        try {
            initialWorldName = serverPathResolver.normalizeSingleSegment(initialWorldName);
        } catch (IllegalArgumentException exception) {
            initialWorldName = ConfigSupport.resetToDefault(
                    plugin,
                    configuration,
                    "initial-world-name",
                    DEFAULT_INITIAL_WORLD_NAME,
                    exception.getMessage()
            );
        }
        Path initialWorldDirectory = serverPathResolver.resolveSingleSegment(initialWorldName);
        Path worldsRoot;
        try {
            worldsRoot = resolveWorldDirectory(configuredDirectory, pluginDataDirectory);
        } catch (IllegalArgumentException exception) {
            configuredDirectory = ConfigSupport.resetToDefault(
                    plugin,
                    configuration,
                    "world-directory",
                    DEFAULT_WORLD_DIRECTORY,
                    exception.getMessage()
            );
            worldsRoot = resolveWorldDirectory(configuredDirectory, pluginDataDirectory);
        }

        Path runtimeRoot = resolveRuntimeDirectory(pluginDataDirectory);
        String bukkitDirectory = serverRoot.relativize(runtimeRoot)
                .toString()
                .replace('\\', '/');
        SafePathResolver snapshotPathResolver = new SafePathResolver(worldsRoot);
        try {
            snapshotPathResolver.ensureRootDirectory();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare world-directory: " + worldsRoot, exception);
        }
        SafePathResolver runtimePathResolver = new SafePathResolver(runtimeRoot);
        try {
            runtimePathResolver.ensureRootDirectory();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare internal runtime directory: " + runtimeRoot,
                    exception
            );
        }
        String fallbackWorld = ConfigSupport.getString(
                plugin,
                configuration,
                "fallback-world",
                DEFAULT_FALLBACK_WORLD
        );
        try {
            snapshotPathResolver.normalizeSingleSegment(fallbackWorld);
        } catch (IllegalArgumentException exception) {
            fallbackWorld = ConfigSupport.resetToDefault(
                    plugin,
                    configuration,
                    "fallback-world",
                    DEFAULT_FALLBACK_WORLD,
                    exception.getMessage()
            );
        }
        return new WorldManagerSettings(
                worldsRoot,
                runtimeRoot,
                initialWorldDirectory,
                initialWorldName,
                bukkitDirectory,
                ConfigSupport.getBoolean(plugin, configuration, "clean-world-resources", false),
                ConfigSupport.getBoolean(plugin, configuration, "auto-reset-worlds", false),
                fallbackWorld
        );
    }
}
