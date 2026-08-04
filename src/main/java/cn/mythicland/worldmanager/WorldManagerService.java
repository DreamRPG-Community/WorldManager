package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.path.SafePathResolver;
import cn.mythicland.worldmanager.api.WorldInfo;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Implements WorldManager's asynchronous lifecycle service and persistent snapshot operations.
 */
public final class WorldManagerService implements WorldManagerApi {

    private final JavaPlugin plugin;
    private final LibApi lib;
    private final WorldManagerSettings settings;
    private final SafePathResolver snapshotResolver;
    private final SafePathResolver runtimeResolver;
    private final WorldCleaner cleaner = new WorldCleaner();
    private final WorldSnapshotService snapshots;
    private final Map<String, ManagedWorld> worlds = new ConcurrentHashMap<>();
    private volatile boolean closed;

    WorldManagerService(
            JavaPlugin plugin,
            LibApi lib,
            WorldManagerSettings settings
    ) {
        this.plugin = plugin;
        this.lib = lib;
        this.settings = settings;
        this.snapshotResolver = new SafePathResolver(settings.worldsRoot());
        this.runtimeResolver = new SafePathResolver(settings.runtimeRoot());
        this.snapshots = new WorldSnapshotService(settings.worldsRoot(), settings.runtimeRoot());
    }

    private static Throwable rootCause(Throwable throwable) {
        if (throwable == null) return null;
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    public CompletableFuture<Void> discoverAndLoadAll() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        return lib.supplyAsync(this::discoverWorldNames)
                .thenCompose(names -> {
                    if (names.isEmpty()) return CompletableFuture.completedFuture(null);

                    CompletableFuture<?>[] loadFutures = names.stream()
                            .map(this::load)
                            .toArray(CompletableFuture<?>[]::new);
                    return CompletableFuture.allOf(loadFutures);
                });
    }

    void restoreInitialWorld() throws IOException {
        if (!settings.autoResetWorlds()) {
            return;
        }

        Path snapshotDirectory = snapshotResolver.resolveSingleSegment(settings.initialWorldName());
        if (!Files.exists(snapshotDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(snapshotDirectory)) {
            plugin.getLogger().info("Initial world snapshot '" + settings.initialWorldName()
                    + "' does not exist; leaving the root world unchanged.");
            return;
        }
        if (Bukkit.getWorld(settings.initialWorldName()) != null) {
            throw new IOException("Initial world is already loaded; WorldManager must load at startup");
        }

        WorldSnapshotService.SnapshotRestoreResult restored = snapshots.restore(
                settings.initialWorldName(),
                settings.initialWorldDirectory()
        );
        plugin.getLogger().info("Restored " + restored.copiedEntries()
                + " map entries into initial world '" + settings.initialWorldName() + "'.");
    }

    @Override
    public Collection<WorldInfo> list() {
        return worlds.values().stream()
                .sorted(Comparator.comparing(ManagedWorld::logicalName))
                .map(ManagedWorld::info)
                .toList();
    }

    @Override
    public Optional<String> findLogicalName(World world) {
        if (world == null) return Optional.empty();

        String bukkitName = world.getName();
        return worlds.values().stream()
                .filter(managedWorld -> managedWorld.bukkitName().equals(bukkitName))
                .map(ManagedWorld::logicalName)
                .findFirst();
    }

    @Override
    public Optional<World> find(String logicalName) {
        final String normalizedName;
        try {
            normalizedName = snapshotResolver.normalizeSingleSegment(logicalName);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        ManagedWorld managedWorld = worlds.get(normalizedName);
        if (managedWorld == null || managedWorld.world() == null) return Optional.empty();
        return Optional.of(managedWorld.world());
    }

    @Override
    public CompletableFuture<World> load(String logicalName) {
        final ManagedWorld managedWorld;
        try {
            managedWorld = getOrCreate(logicalName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        synchronized (managedWorld) {
            if (managedWorld.world() != null) return CompletableFuture.completedFuture(managedWorld.world());
            if (managedWorld.loadFuture() != null) return managedWorld.loadFuture();
            if (managedWorld.operation() != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "World is busy with another operation: " + managedWorld.logicalName()
                ));
            }

            CompletableFuture<World> result = new CompletableFuture<>();
            managedWorld.setLoadFuture(result);
            managedWorld.setOperation(result);
            managedWorld.setStatus(WorldStatus.PREPARING);
            managedWorld.setDetail("");

            lib.supplyOnMain(() -> Bukkit.getWorld(managedWorld.bukkitName()))
                    .thenCompose(existingWorld -> {
                        if (existingWorld != null) {
                            if (settings.autoResetWorlds() && !managedWorld.initialWorld()) {
                                plugin.getLogger().warning("Automatic reset skipped for pre-loaded world '"
                                        + managedWorld.logicalName()
                                        + "'; do not use this world as server.properties level-name.");
                            }
                            managedWorld.setWorld(existingWorld);
                            managedWorld.setStatus(WorldStatus.LOADED);
                            return CompletableFuture.completedFuture(existingWorld);
                        }

                        if (managedWorld.initialWorld()) {
                            return CompletableFuture.failedFuture(new IllegalStateException(
                                    "Initial world '" + managedWorld.logicalName()
                                            + "' is not loaded; server.properties level-name must be '"
                                            + settings.initialWorldName() + "'"
                            ));
                        }

                        return lib.runAsync(() -> prepareWorld(managedWorld))
                                .thenCompose(ignored -> {
                                    managedWorld.setStatus(WorldStatus.QUEUED);
                                    return lib.supplyOnMain(() -> createWorld(managedWorld));
                                });
                    })
                    .whenComplete((world, error) -> finishLoad(managedWorld, result, world, error));
            return result;
        }
    }

    @Override
    public CompletableFuture<Boolean> unload(String logicalName, boolean force) {
        final ManagedWorld managedWorld;
        try {
            managedWorld = getOrCreate(logicalName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        if (managedWorld.initialWorld()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The initial world cannot be unloaded: " + managedWorld.logicalName()
            ));
        }

        synchronized (managedWorld) {
            if (managedWorld.operation() != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "World is busy with another operation: " + managedWorld.logicalName()
                ));
            }

            CompletableFuture<Boolean> result = new CompletableFuture<>();
            managedWorld.setOperation(result);
            managedWorld.setStatus(WorldStatus.UNLOADING);
            lib.runOnMain(() -> unloadWorld(managedWorld, force))
                    .whenComplete((ignored, error) -> finishUnload(managedWorld, result, error));
            return result;
        }
    }

    @Override
    public CompletableFuture<Integer> clean(String logicalName) {
        final ManagedWorld managedWorld;
        try {
            managedWorld = getOrCreate(logicalName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        synchronized (managedWorld) {
            if (managedWorld.operation() != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "World is busy with another operation: " + managedWorld.logicalName()
                ));
            }

            CompletableFuture<Integer> result = new CompletableFuture<>();
            managedWorld.setOperation(result);
            managedWorld.setStatus(WorldStatus.PREPARING);
            lib.runOnMain(() -> ensureWorldIsOffline(managedWorld))
                    .thenCompose(ignored -> lib.supplyAsync(() -> {
                        try {
                            WorldCleaner.CleanResult cleaned = cleaner.clean(managedWorld.snapshotDirectory());
                            if (Files.exists(managedWorld.worldDirectory(), LinkOption.NOFOLLOW_LINKS)) {
                                snapshots.restore(
                                        managedWorld.logicalName(),
                                        managedWorld.worldDirectory()
                                );
                            }
                            return cleaned.deletedEntries();
                        } catch (IOException exception) {
                            throw new CompletionException(
                                    "World cleanup failed for '" + managedWorld.logicalName() + "'",
                                    exception
                            );
                        }
                    }))
                    .whenComplete((deletedEntries, error) -> finishClean(
                            managedWorld,
                            result,
                            deletedEntries,
                            error
                    ));
            return result;
        }
    }

    @Override
    public CompletableFuture<Integer> save(String logicalName) {
        final ManagedWorld managedWorld;
        try {
            managedWorld = getOrCreate(logicalName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        synchronized (managedWorld) {
            if (managedWorld.operation() != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "World is busy with another operation: " + managedWorld.logicalName()
                ));
            }

            CompletableFuture<Integer> result = new CompletableFuture<>();
            managedWorld.setOperation(result);
            managedWorld.setStatus(WorldStatus.SAVING);
            managedWorld.setDetail("保存世界快照");

            lib.supplyOnMain(() -> prepareSnapshotSave(managedWorld))
                    .thenCompose(context -> lib.supplyAsync(() -> saveSnapshot(managedWorld, context)))
                    .thenCompose(outcome -> lib.supplyOnMain(() -> {
                        restoreAutoSave(outcome.context());
                        if (outcome.error() != null) {
                            throw new CompletionException(
                                    "World snapshot save failed for '" + managedWorld.logicalName() + "'",
                                    outcome.error()
                            );
                        }
                        return outcome.copiedEntries();
                    }))
                    .whenComplete((copiedEntries, error) -> finishSave(
                            managedWorld,
                            result,
                            copiedEntries,
                            error
                    ));
            return result;
        }
    }

    @Override
    public CompletableFuture<Boolean> teleport(Player player, String logicalName) {
        if (player == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player cannot be null"));
        }

        final String normalizedName;
        try {
            normalizedName = snapshotResolver.normalizeSingleSegment(logicalName);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("WorldManager is closed"));

        return resolveOrLoad(normalizedName).thenCompose(world -> lib.supplyOnMain(() -> {
            if (!player.isOnline()) {
                throw new IllegalStateException("Player is no longer online");
            }
            return player.teleport(world.getSpawnLocation());
        }));
    }

    public void close() {
        closed = true;
        IllegalStateException exception = new IllegalStateException("WorldManager disabled");
        for (ManagedWorld managedWorld : worlds.values()) {
            CompletableFuture<?> operation = managedWorld.operation();
            if (operation != null) operation.completeExceptionally(exception);
        }
    }

    private List<String> discoverWorldNames() {
        List<String> names = new ArrayList<>();
        register(settings.initialWorldName());
        names.add(settings.initialWorldName());
        try (var children = Files.list(settings.worldsRoot())) {
            children.filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        try {
                            register(name);
                            names.add(name);
                        } catch (IllegalArgumentException exception) {
                            plugin.getLogger().warning("Skipping invalid world snapshot '" + name + "': "
                                    + exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            throw new CompletionException(
                    "Could not scan persistent world snapshot directory: " + settings.worldsRoot(),
                    exception
            );
        }
        return names.stream().distinct().sorted().toList();
    }

    private void prepareWorld(ManagedWorld managedWorld) {
        try {
            snapshotResolver.requireRealDirectory(managedWorld.snapshotDirectory());
            Path levelDat = managedWorld.snapshotDirectory().resolve("level.dat");
            if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World snapshot is missing a real level.dat: "
                        + managedWorld.snapshotDirectory());
            }
            boolean worldReady = Files.isDirectory(
                    managedWorld.worldDirectory(),
                    LinkOption.NOFOLLOW_LINKS
            ) && Files.isRegularFile(
                    managedWorld.worldDirectory().resolve("level.dat"),
                    LinkOption.NOFOLLOW_LINKS
            );
            if (settings.autoResetWorlds() || !worldReady) {
                WorldSnapshotService.SnapshotRestoreResult restored = snapshots.restore(
                        managedWorld.logicalName(),
                        managedWorld.worldDirectory()
                );
                plugin.getLogger().info("Prepared " + restored.copiedEntries()
                        + " map entries from persistent snapshot for world '"
                        + managedWorld.logicalName() + "'.");
            } else {
                runtimeResolver.requireRealDirectory(managedWorld.worldDirectory());
            }
        } catch (IOException exception) {
            throw new CompletionException(
                    "World preparation failed for '" + managedWorld.logicalName() + "'",
                    exception
            );
        }
    }

    private World createWorld(ManagedWorld managedWorld) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("World creation must run on the primary thread");
        }

        managedWorld.setStatus(WorldStatus.LOADING);
        World existingWorld = Bukkit.getWorld(managedWorld.bukkitName());
        if (existingWorld != null) {
            managedWorld.setWorld(existingWorld);
            managedWorld.setStatus(WorldStatus.LOADED);
            return existingWorld;
        }

        World world = Bukkit.createWorld(WorldCreator.name(managedWorld.bukkitName()));
        if (world == null) {
            throw new IllegalStateException("Bukkit refused to create world '" + managedWorld.bukkitName() + "'");
        }
        managedWorld.setWorld(world);
        managedWorld.setStatus(WorldStatus.LOADED);
        return world;
    }

    private void unloadWorld(ManagedWorld managedWorld, boolean force) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("World unloading must run on the primary thread");
        }

        World world = managedWorld.world();
        if (world == null) world = Bukkit.getWorld(managedWorld.bukkitName());
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + managedWorld.logicalName());
        }

        List<Player> players = List.copyOf(world.getPlayers());
        if (!force && !players.isEmpty()) {
            throw new IllegalStateException("World has online players; use [force] to unload it");
        }
        if (force && !players.isEmpty()) {
            World fallbackWorld = resolveFallbackWorld(world);
            for (Player player : players) {
                if (!player.teleport(fallbackWorld.getSpawnLocation())) {
                    throw new IllegalStateException("Could not teleport player " + player.getName()
                            + " to fallback world");
                }
            }
        }

        if (!Bukkit.unloadWorld(world, true)) {
            throw new IllegalStateException("Bukkit refused to unload world: " + managedWorld.logicalName());
        }
        managedWorld.setWorld(null);
        managedWorld.setStatus(WorldStatus.DISCOVERED);
        managedWorld.setDetail("");
    }

    private World resolveFallbackWorld(World unloadingWorld) {
        World fallbackWorld = Bukkit.getWorld(settings.fallbackWorld());
        if (fallbackWorld == null) {
            try {
                String fallbackLogicalName = snapshotResolver.normalizeSingleSegment(settings.fallbackWorld());
                fallbackWorld = Bukkit.getWorld(toBukkitName(fallbackLogicalName));
            } catch (IllegalArgumentException ignored) {
                // The configured value may be a normal root world name.
            }
        }
        if (fallbackWorld == null) {
            throw new IllegalStateException("Fallback world is not loaded: " + settings.fallbackWorld());
        }
        if (fallbackWorld.equals(unloadingWorld)) {
            throw new IllegalStateException("Fallback world cannot be the world being unloaded");
        }
        return fallbackWorld;
    }

    private CompletableFuture<World> resolveOrLoad(String logicalName) {
        return lib.supplyOnMain(() -> findLoadedWorld(logicalName))
                .thenCompose(world -> {
                    if (world == null) return load(logicalName);

                    ManagedWorld managedWorld = worlds.get(logicalName);
                    if (managedWorld != null && managedWorld.operation() != null) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "World is busy with another operation: " + logicalName
                        ));
                    }
                    return CompletableFuture.completedFuture(world);
                });
    }

    private World findLoadedWorld(String logicalName) {
        World world = Bukkit.getWorld(logicalName);
        if (world != null) return world;
        return Bukkit.getWorld(toBukkitName(logicalName));
    }

    private void ensureWorldIsOffline(ManagedWorld managedWorld) {
        if (managedWorld.world() != null || Bukkit.getWorld(managedWorld.bukkitName()) != null) {
            throw new IllegalStateException("World must be unloaded before it can be cleaned: "
                    + managedWorld.logicalName());
        }
    }

    private SnapshotSaveContext prepareSnapshotSave(ManagedWorld managedWorld) {
        World world = Bukkit.getWorld(managedWorld.bukkitName());
        if (world == null) {
            throw new IllegalStateException("World is not loaded: " + managedWorld.logicalName());
        }

        boolean autoSave = world.isAutoSave();
        world.save();
        world.setAutoSave(false);
        return new SnapshotSaveContext(world, autoSave);
    }

    private SnapshotSaveOutcome saveSnapshot(ManagedWorld managedWorld, SnapshotSaveContext context) {
        Integer copiedEntries = null;
        Throwable error = null;
        try {
            WorldSnapshotService.SnapshotSaveResult saved = snapshots.save(
                    managedWorld.logicalName(),
                    managedWorld.worldDirectory()
            );
            copiedEntries = saved.copiedEntries();

            if (settings.cleanWorldResources()) {
                WorldCleaner.CleanResult cleaned = cleaner.clean(managedWorld.snapshotDirectory());
                plugin.getLogger().info("Cleaned " + cleaned.deletedEntries()
                        + " persistent snapshot entries after saving world snapshot '"
                        + managedWorld.logicalName() + "'.");
            }
        } catch (IOException exception) {
            error = exception;
        }
        return new SnapshotSaveOutcome(context, copiedEntries, error);
    }

    private void restoreAutoSave(SnapshotSaveContext context) {
        if (context.world() != null) context.world().setAutoSave(context.autoSave());
    }

    private void finishLoad(
            ManagedWorld managedWorld,
            CompletableFuture<World> result,
            World world,
            Throwable error
    ) {
        Throwable cause = rootCause(error);
        if (cause != null) {
            managedWorld.setWorld(null);
            managedWorld.setStatus(WorldStatus.FAILED);
            managedWorld.setDetail(LibApi.rootCauseMessage(cause));
            plugin.getLogger().log(Level.WARNING, "Failed to load world '" + managedWorld.logicalName() + "'.", cause);
        } else {
            managedWorld.setWorld(world);
            managedWorld.setStatus(WorldStatus.LOADED);
            managedWorld.setDetail("");
        }
        managedWorld.clearOperation(result);
        if (cause != null) result.completeExceptionally(cause);
        else result.complete(world);
    }

    private void finishUnload(
            ManagedWorld managedWorld,
            CompletableFuture<Boolean> result,
            Throwable error
    ) {
        Throwable cause = rootCause(error);
        if (cause != null) {
            managedWorld.setStatus(managedWorld.world() == null ? WorldStatus.FAILED : WorldStatus.LOADED);
            managedWorld.setDetail(LibApi.rootCauseMessage(cause));
            result.completeExceptionally(cause);
        } else {
            managedWorld.clearOperation(result);
            result.complete(true);
            return;
        }
        managedWorld.clearOperation(result);
    }

    private void finishClean(
            ManagedWorld managedWorld,
            CompletableFuture<Integer> result,
            Integer deletedEntries,
            Throwable error
    ) {
        Throwable cause = rootCause(error);
        if (cause != null) {
            managedWorld.setStatus(WorldStatus.FAILED);
            managedWorld.setDetail(LibApi.rootCauseMessage(cause));
            result.completeExceptionally(cause);
        } else {
            managedWorld.setStatus(WorldStatus.DISCOVERED);
            managedWorld.setDetail("");
            result.complete(deletedEntries);
        }
        managedWorld.clearOperation(result);
    }

    private void finishSave(
            ManagedWorld managedWorld,
            CompletableFuture<Integer> result,
            Integer copiedEntries,
            Throwable error
    ) {
        Throwable cause = rootCause(error);
        World loadedWorld = Bukkit.getWorld(managedWorld.bukkitName());
        if (loadedWorld != null) managedWorld.setWorld(loadedWorld);
        if (cause != null) {
            managedWorld.setStatus(loadedWorld == null ? WorldStatus.DISCOVERED : WorldStatus.LOADED);
            managedWorld.setDetail(LibApi.rootCauseMessage(cause));
            result.completeExceptionally(cause);
        } else {
            managedWorld.setStatus(loadedWorld == null ? WorldStatus.DISCOVERED : WorldStatus.LOADED);
            managedWorld.setDetail("");
            result.complete(copiedEntries);
        }
        managedWorld.clearOperation(result);
    }

    private ManagedWorld getOrCreate(String logicalName) {
        String normalizedName = snapshotResolver.normalizeSingleSegment(logicalName);
        return worlds.computeIfAbsent(
                normalizedName,
                this::createManagedWorld
        );
    }

    private void register(String logicalName) {
        String normalizedName = snapshotResolver.normalizeSingleSegment(logicalName);
        worlds.computeIfAbsent(
                normalizedName,
                this::createManagedWorld
        );
    }

    private ManagedWorld createManagedWorld(String logicalName) {
        boolean initialWorld = logicalName.equals(settings.initialWorldName());
        return new ManagedWorld(
                logicalName,
                snapshotResolver.resolveSingleSegment(logicalName),
                initialWorld
                        ? settings.initialWorldDirectory()
                        : runtimeResolver.resolveSingleSegment(logicalName),
                initialWorld ? settings.initialWorldName() : toBukkitName(logicalName),
                initialWorld
        );
    }

    private String toBukkitName(String logicalName) {
        return settings.bukkitDirectory() + "/" + logicalName;
    }

    private record SnapshotSaveContext(World world, boolean autoSave) {
    }

    private record SnapshotSaveOutcome(
            SnapshotSaveContext context,
            Integer copiedEntries,
            Throwable error
    ) {
    }
}
