package cn.mythicland.worldmanager;

import cn.mythicland.lib.bootstrap.annotation.ConfigComponent;
import cn.mythicland.lib.config.ConfigValue;
import cn.mythicland.lib.config.ConfigView;
import cn.mythicland.lib.config.ConfigurableComponent;

import java.util.Objects;

/**
 * Binds WorldManager's global configuration before its world lifecycle starts.
 */
@ConfigComponent
final class WorldManagerConfiguration implements ConfigurableComponent {

    private final WorldManagerPlugin plugin;
    private volatile WorldManagerSettings snapshot;

    WorldManagerConfiguration(WorldManagerPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void reload(ConfigView configuration) {
        RawSettings raw = Objects.requireNonNull(configuration, "configuration")
                .bind(RawSettings.class);
        snapshot = WorldManagerSettingsLoader.load(plugin, raw);
    }

    WorldManagerSettings snapshot() {
        WorldManagerSettings value = snapshot;
        if (value == null) throw new IllegalStateException("WorldManager settings are not loaded");
        return value;
    }

    record RawSettings(
            @ConfigValue(
                    path = "world-directory",
                    defaultValue = "worlds",
                    nonBlank = true
            )
            String worldDirectory,
            @ConfigValue(
                    path = "initial-world-name",
                    defaultValue = "world",
                    nonBlank = true
            )
            String initialWorldName,
            @ConfigValue(
                    path = "clean-world-resources",
                    defaultValue = "false"
            )
            boolean cleanWorldResources,
            @ConfigValue(
                    path = "auto-reset-worlds",
                    defaultValue = "false"
            )
            boolean autoResetWorlds,
            @ConfigValue(
                    path = "fallback-world",
                    defaultValue = "world",
                    nonBlank = true
            )
            String fallbackWorld
    ) {
    }
}
