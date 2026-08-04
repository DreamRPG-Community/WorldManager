package cn.mythicland.worldmanager;

import cn.mythicland.worldmanager.api.WorldInfo;
import org.bukkit.World;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

final class ManagedWorld {

    private final String logicalName;
    private final Path snapshotDirectory;
    private final Path worldDirectory;
    private final String bukkitName;
    private final boolean initialWorld;
    private volatile WorldStatus status = WorldStatus.DISCOVERED;
    private volatile String detail = "";
    private volatile World world;
    private CompletableFuture<World> loadFuture;
    private CompletableFuture<?> operation;

    ManagedWorld(
            String logicalName,
            Path snapshotDirectory,
            Path worldDirectory,
            String bukkitName,
            boolean initialWorld
    ) {
        this.logicalName = logicalName;
        this.snapshotDirectory = snapshotDirectory;
        this.worldDirectory = worldDirectory;
        this.bukkitName = bukkitName;
        this.initialWorld = initialWorld;
    }

    String logicalName() {
        return logicalName;
    }

    Path snapshotDirectory() {
        return snapshotDirectory;
    }

    Path worldDirectory() {
        return worldDirectory;
    }

    String bukkitName() {
        return bukkitName;
    }

    boolean initialWorld() {
        return initialWorld;
    }

    World world() {
        return world;
    }

    void setWorld(World world) {
        this.world = world;
    }

    void setStatus(WorldStatus status) {
        this.status = status;
    }

    void setDetail(String detail) {
        this.detail = detail == null ? "" : detail;
    }

    synchronized CompletableFuture<World> loadFuture() {
        return loadFuture;
    }

    synchronized void setLoadFuture(CompletableFuture<World> loadFuture) {
        this.loadFuture = loadFuture;
    }

    synchronized CompletableFuture<?> operation() {
        return operation;
    }

    synchronized void setOperation(CompletableFuture<?> operation) {
        this.operation = operation;
    }

    synchronized void clearOperation(CompletableFuture<?> operation) {
        if (this.operation == operation) {
            this.operation = null;
        }
        if (this.loadFuture == operation) {
            this.loadFuture = null;
        }
    }

    WorldInfo info() {
        return new WorldInfo(logicalName, status, detail);
    }
}
