package cn.mythicland.worldmanager;

import java.nio.file.Path;
import java.util.Objects;

record WorldManagerSettings(
        Path worldsRoot,
        Path runtimeRoot,
        Path initialWorldDirectory,
        String initialWorldName,
        String bukkitDirectory,
        boolean cleanWorldResources,
        boolean autoResetWorlds,
        String fallbackWorld
) {

    WorldManagerSettings withReloadableOptions(WorldManagerSettings refreshed) {
        Objects.requireNonNull(refreshed, "refreshed");
        if (!worldsRoot.equals(refreshed.worldsRoot())
                || !runtimeRoot.equals(refreshed.runtimeRoot())
                || !initialWorldDirectory.equals(refreshed.initialWorldDirectory())
                || !initialWorldName.equals(refreshed.initialWorldName())
                || !bukkitDirectory.equals(refreshed.bukkitDirectory())) {
            throw new IllegalStateException(
                    "World path settings changed; restart WorldManager to apply world-directory "
                            + "or initial-world-name changes"
            );
        }
        return new WorldManagerSettings(
                worldsRoot,
                runtimeRoot,
                initialWorldDirectory,
                initialWorldName,
                bukkitDirectory,
                refreshed.cleanWorldResources(),
                refreshed.autoResetWorlds(),
                refreshed.fallbackWorld()
        );
    }
}
