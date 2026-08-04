package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.command.CommandRouter;
import cn.mythicland.lib.config.ConfigSupport;
import cn.mythicland.lib.path.SafePathResolver;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Bukkit entry point for managed world discovery, loading, saving, and cleanup.
 */
public final class WorldManagerPlugin extends JavaPlugin {

    private static final String DEFAULT_WORLD_DIRECTORY = "worlds";
    private static final String LEGACY_INTERNAL_DIRECTORY = ".worldmanager";
    private static final String INTERNAL_RUNTIME_DIRECTORY = ".runtime";
    private static final String DEFAULT_INITIAL_WORLD_NAME = "world";
    private static final String DEFAULT_FALLBACK_WORLD = "world";

    private WorldManagerService service;

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

    // Lib owns and closes the service; this plugin only borrows it.
    @SuppressWarnings("resource")
    @Override
    public void onEnable() {
        LibApi libApi = LibApi.require(this);
        FileConfiguration configuration = ConfigSupport.loadDefault(this);
        WorldManagerSettings settings = loadSettings(configuration);
        service = new WorldManagerService(this, libApi, settings);
        try {
            service.restoreInitialWorld();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not restore initial world '" + settings.initialWorldName() + "'",
                    exception
            );
        }

        getServer().getServicesManager().register(
                WorldManagerApi.class,
                service,
                this,
                ServicePriority.Normal
        );
        registerCommand(libApi);

        libApi.runLater(1L, () -> service.discoverAndLoadAll().whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().warning(
                        "Startup world loading finished with failures: " + LibApi.rootCauseMessage(error)
                );
                return;
            }
            getLogger().info("Startup world discovery and loading completed.");
        }));
    }

    @Override
    public void onDisable() {
        if (service == null) return;

        getServer().getServicesManager().unregister(WorldManagerApi.class, service);
        service.close();
        service = null;
    }

    private void registerCommand(LibApi libApi) {
        PluginCommand command = getCommand("worldmanager");
        if (command == null) {
            throw new IllegalStateException("worldmanager command is missing from plugin.yml");
        }

        CommandRouter router = libApi.createCommandRouter(this, "worldmanager");
        WorldManagerCommand.register(router, service, libApi);
        command.setExecutor(router);
        command.setTabCompleter(router);
    }

    private WorldManagerSettings loadSettings(FileConfiguration configuration) {
        String configuredDirectory = ConfigSupport.getString(
                this,
                configuration,
                "world-directory",
                DEFAULT_WORLD_DIRECTORY
        );
        Path serverRoot = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path pluginDataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
        SafePathResolver serverPathResolver = new SafePathResolver(serverRoot);
        String initialWorldName = ConfigSupport.getString(
                this,
                configuration,
                "initial-world-name",
                DEFAULT_INITIAL_WORLD_NAME
        );
        try {
            initialWorldName = serverPathResolver.normalizeSingleSegment(initialWorldName);
        } catch (IllegalArgumentException exception) {
            initialWorldName = ConfigSupport.resetToDefault(
                    this,
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
                    this,
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
                this,
                configuration,
                "fallback-world",
                DEFAULT_FALLBACK_WORLD
        );
        try {
            snapshotPathResolver.normalizeSingleSegment(fallbackWorld);
        } catch (IllegalArgumentException exception) {
            fallbackWorld = ConfigSupport.resetToDefault(
                    this,
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
                ConfigSupport.getBoolean(this, configuration, "clean-world-resources", false),
                ConfigSupport.getBoolean(this, configuration, "auto-reset-worlds", false),
                fallbackWorld
        );
    }
}
