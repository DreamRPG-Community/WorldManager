package cn.mythicland.worldmanager.api;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public world lifecycle and teleport service exposed by WorldManager.
 */
public interface WorldManagerApi {

    /**
     * Lists all worlds discovered below the persistent snapshot directory.
     *
     * @return an immutable snapshot of managed world metadata
     */
    Collection<WorldInfo> list();

    /**
     * Reloads runtime configuration, re-scans the persistent snapshot directory, and loads newly
     * discovered worlds.
     *
     * <p>World path settings are fixed when WorldManager starts. Changing those settings still
     * requires a server restart. Reloadable options such as resource cleanup, automatic reset,
     * and the forced-unload fallback world are applied without replacing already loaded worlds.</p>
     *
     * @return a future completed after the snapshot directory has been scanned and discovered
     * worlds have finished loading
     */
    CompletableFuture<Void> reload();

    /**
     * Resolves a loaded Bukkit world to its user-facing logical name.
     *
     * <p>The returned name is suitable for WorldManager commands such as
     * {@code /worldmanager save <world>}. For ordinary root worlds it is the
     * Bukkit world name. For worlds loaded from the internal runtime directory
     * it is the logical snapshot name rather than the internal Bukkit path.</p>
     *
     * @param world the Bukkit world to resolve
     * @return the logical name, or an empty optional when the world is null or
     * not known to this manager
     */
    Optional<String> findLogicalName(World world);

    /**
     * Finds a currently loaded managed world.
     *
     * @param logicalName the single-segment logical world name
     * @return the loaded world, or an empty optional when it is not loaded or the name is invalid
     */
    Optional<World> find(String logicalName);

    /**
     * Loads a managed world asynchronously.
     *
     * @param logicalName the single-segment logical world name
     * @return a future completed with the loaded world, or exceptionally when loading fails
     */
    CompletableFuture<World> load(String logicalName);

    /**
     * Unloads a managed world asynchronously.
     *
     * @param logicalName the single-segment logical world name
     * @param force       whether online players may be moved to the configured fallback world
     * @return a future completed with true when the world is unloaded
     */
    CompletableFuture<Boolean> unload(String logicalName, boolean force);

    /**
     * Cleans an offline managed world asynchronously.
     *
     * @param logicalName the single-segment logical world name
     * @return a future completed with the number of deleted entries
     */
    CompletableFuture<Integer> clean(String logicalName);

    /**
     * Saves the current managed world map state as the persistent startup snapshot.
     *
     * <p>The current Bukkit world is synchronously saved before the persistent snapshot is copied
     * asynchronously. Automatic saving is paused only for the duration of the copy and restored
     * afterward. The snapshot contains {@code level.dat}, an existing {@code uid.dat}, and region
     * files from the overworld, Nether, and End dimensions. Future ordinary loads copy those map
     * files into an internal runtime directory; the configured initial world is restored into its
     * server-root directory. Player data and runtime files are not persisted.</p>
     *
     * @param logicalName the single-segment logical world name
     * @return a future completed with the number of map entries copied to the snapshot
     * @throws IllegalArgumentException when the logical name is invalid
     * @throws IllegalStateException    when the world manager is closed or the world is busy
     */
    CompletableFuture<Integer> save(String logicalName);

    /**
     * Loads the target when necessary and teleports a player to its world spawn.
     *
     * @param player      the player to teleport
     * @param logicalName a loaded root-world name or a managed single-segment world name
     * @return a future completed with Bukkit's teleport result
     */
    CompletableFuture<Boolean> teleport(Player player, String logicalName);
}
