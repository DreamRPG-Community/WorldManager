package cn.mythicland.worldmanager;

import cn.mythicland.lib.bootstrap.annotation.ServiceComponent;
import cn.mythicland.worldmanager.api.WorldInfo;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes the active WorldManager lifecycle service through Lib's service annotation.
 */
@ServiceComponent(WorldManagerApi.class)
public final class WorldManagerApiProvider implements WorldManagerApi {

    private final WorldManagerLifecycle lifecycle;
    private final WorldManagerPlugin plugin;

    public WorldManagerApiProvider(WorldManagerPlugin plugin, WorldManagerLifecycle lifecycle) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public Collection<WorldInfo> list() {
        return lifecycle.service().list();
    }

    @Override
    public CompletableFuture<Void> reload() {
        return plugin.reloadWorldManager();
    }

    @Override
    public Optional<String> findLogicalName(World world) {
        return lifecycle.service().findLogicalName(world);
    }

    @Override
    public Optional<World> find(String logicalName) {
        return lifecycle.service().find(logicalName);
    }

    @Override
    public CompletableFuture<World> load(String logicalName) {
        return lifecycle.service().load(logicalName);
    }

    @Override
    public CompletableFuture<Boolean> unload(String logicalName, boolean force) {
        return lifecycle.service().unload(logicalName, force);
    }

    @Override
    public CompletableFuture<Integer> clean(String logicalName) {
        return lifecycle.service().clean(logicalName);
    }

    @Override
    public CompletableFuture<Integer> save(String logicalName) {
        return lifecycle.service().save(logicalName);
    }

    @Override
    public CompletableFuture<Boolean> teleport(Player player, String logicalName) {
        return lifecycle.service().teleport(player, logicalName);
    }
}
