package cn.mythicland.worldmanager;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldManagerSettingsTest {

    @Test
    void reloadableOptionsAreReplacedWhilePathsRemainStable() {
        WorldManagerSettings current = settings(false, false, "world");
        WorldManagerSettings refreshed = settings(true, true, "hub");

        WorldManagerSettings result = current.withReloadableOptions(refreshed);

        assertTrue(result.cleanWorldResources());
        assertTrue(result.autoResetWorlds());
        assertEquals("hub", result.fallbackWorld());
        assertEquals(current.worldsRoot(), result.worldsRoot());
        assertEquals(current.initialWorldName(), result.initialWorldName());
    }

    @Test
    void pathChangesRequireRestart() {
        WorldManagerSettings current = settings(false, false, "world");
        WorldManagerSettings refreshed = new WorldManagerSettings(
                Path.of("plugin-data", "other-worlds"),
                current.runtimeRoot(),
                current.initialWorldDirectory(),
                current.initialWorldName(),
                current.bukkitDirectory(),
                true,
                true,
                "world"
        );

        assertThrows(
                IllegalStateException.class,
                () -> current.withReloadableOptions(refreshed)
        );
    }

    private static WorldManagerSettings settings(
            boolean cleanWorldResources,
            boolean autoResetWorlds,
            String fallbackWorld
    ) {
        return new WorldManagerSettings(
                Path.of("plugin-data", "worlds"),
                Path.of("plugin-data", ".runtime"),
                Path.of("server", "world"),
                "world",
                "plugins/WorldManager/.runtime",
                cleanWorldResources,
                autoResetWorlds,
                fallbackWorld
        );
    }
}
