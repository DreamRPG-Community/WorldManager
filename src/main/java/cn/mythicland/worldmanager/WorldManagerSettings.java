package cn.mythicland.worldmanager;

import java.nio.file.Path;

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
}
