package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.bootstrap.PluginBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Minimal Bukkit entry point for the Lib-managed world manager.
 */
public final class WorldManagerPlugin extends JavaPlugin {

    private static final String COMPONENT_PACKAGE = "cn.mythicland.worldmanager";

    private PluginBootstrap bootstrap;

    /**
     * Starts the Lib-managed WorldManager component graph.
     */
    @Override
    public void onEnable() {
        try {
            LibApi lib = LibApi.require(this);
            bootstrap = lib.createPluginBootstrap(this, COMPONENT_PACKAGE);
            bootstrap.enable();
        } catch (RuntimeException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "WorldManager failed to enable: " + LibApi.rootCauseMessage(exception),
                    exception
            );
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * Closes the Lib-managed WorldManager component graph.
     */
    @Override
    public void onDisable() {
        if (bootstrap != null) bootstrap.disable();
        bootstrap = null;
    }
}
