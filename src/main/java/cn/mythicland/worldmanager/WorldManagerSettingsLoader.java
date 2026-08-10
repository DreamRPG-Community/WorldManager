package cn.mythicland.worldmanager;

import cn.mythicland.lib.path.SafePathResolver;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Loads and validates WorldManager settings from a Bukkit configuration.
 */
final class WorldManagerSettingsLoader {

    private static final String DEFAULT_WORLD_DIRECTORY = "worlds";
    private static final String LEGACY_INTERNAL_DIRECTORY = ".worldmanager";
    private static final String INTERNAL_RUNTIME_DIRECTORY = ".runtime";
    private static final String DEFAULT_INITIAL_WORLD_NAME = "world";
    private static final String DEFAULT_FALLBACK_WORLD = "world";

    private WorldManagerSettingsLoader() {
    }

    static WorldManagerSettings load(WorldManagerPlugin plugin, WorldManagerConfiguration.RawSettings raw) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(raw, "raw");
        String configuredDirectory = raw.worldDirectory();
        Path serverRoot = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path pluginDataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        SafePathResolver serverPathResolver = new SafePathResolver(serverRoot);
        String initialWorldName = raw.initialWorldName();
        try {
            initialWorldName = serverPathResolver.normalizeSingleSegment(initialWorldName);
        } catch (IllegalArgumentException exception) {
            warnInvalid(plugin, "initial-world-name", DEFAULT_INITIAL_WORLD_NAME, exception.getMessage());
            initialWorldName = DEFAULT_INITIAL_WORLD_NAME;
        }
        Path initialWorldDirectory = serverPathResolver.resolveSingleSegment(initialWorldName);
        Path worldsRoot;
        try {
            worldsRoot = resolveWorldDirectory(configuredDirectory, pluginDataDirectory);
        } catch (IllegalArgumentException exception) {
            warnInvalid(plugin, "world-directory", DEFAULT_WORLD_DIRECTORY, exception.getMessage());
            configuredDirectory = DEFAULT_WORLD_DIRECTORY;
            worldsRoot = resolveWorldDirectory(configuredDirectory, pluginDataDirectory);
        }

        Path runtimeRoot = resolveRuntimeDirectory(pluginDataDirectory);
        String bukkitDirectory = serverRoot.relativize(runtimeRoot)
                .toString()
                .replace('\\', '/');
        SafePathResolver snapshotPathResolver = new SafePathResolver(worldsRoot);
        String fallbackWorld = raw.fallbackWorld();
        try {
            snapshotPathResolver.normalizeSingleSegment(fallbackWorld);
        } catch (IllegalArgumentException exception) {
            warnInvalid(plugin, "fallback-world", DEFAULT_FALLBACK_WORLD, exception.getMessage());
            fallbackWorld = DEFAULT_FALLBACK_WORLD;
        }
        return new WorldManagerSettings(
                worldsRoot,
                runtimeRoot,
                initialWorldDirectory,
                initialWorldName,
                bukkitDirectory,
                raw.cleanWorldResources(),
                raw.autoResetWorlds(),
                fallbackWorld
        );
    }

    private static void warnInvalid(WorldManagerPlugin plugin, String path, String defaultValue, String reason) {
        plugin.getLogger().warning(
                "Invalid configuration '" + path + "': " + reason
                        + "; using the declared default '" + defaultValue + "' for this snapshot."
        );
    }

    static void ensureDirectories(WorldManagerSettings settings) {
        SafePathResolver snapshotPathResolver = new SafePathResolver(settings.worldsRoot());
        try {
            snapshotPathResolver.ensureRootDirectory();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare world-directory: " + settings.worldsRoot(),
                    exception
            );
        }

        SafePathResolver runtimePathResolver = new SafePathResolver(settings.runtimeRoot());
        try {
            runtimePathResolver.ensureRootDirectory();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare internal runtime directory: " + settings.runtimeRoot(),
                    exception
            );
        }
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
        if (!path.startsWith(pluginDataDirectory) || path.equals(pluginDataDirectory)) {
            throw new IllegalArgumentException("Internal WorldManager path is invalid");
        }
        return path;
    }
}
