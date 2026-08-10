package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.bootstrap.annotation.CommandCompleter;
import cn.mythicland.lib.bootstrap.annotation.CommandComponent;
import cn.mythicland.lib.bootstrap.annotation.CommandHandler;
import cn.mythicland.lib.command.CommandContext;
import cn.mythicland.lib.command.VanillaCommandMessages;
import cn.mythicland.worldmanager.api.WorldInfo;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Handles WorldManager commands.
 */
@CommandComponent(value = "worldmanager", permission = "worldmanager.admin")
final class WorldManagerCommand {

    private static final String ROOT = "/worldmanager";

    private final WorldManagerPlugin plugin;
    private final WorldManagerLifecycle lifecycle;
    private final LibApi lib;

    WorldManagerCommand(WorldManagerPlugin plugin, WorldManagerLifecycle lifecycle, LibApi lib) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.lib = Objects.requireNonNull(lib, "lib");
    }

    @CommandHandler(value = "list", usage = ROOT + " list")
    void list(CommandContext context) {
        context.requireArguments(0);
        Collection<WorldInfo> worlds = service().list();
        if (worlds.isEmpty()) {
            context.sender().sendMessage(VanillaCommandMessages.red("没有发现受管理的世界。"));
            return;
        }
        for (WorldInfo world : worlds) {
            String detail = world.detail().isBlank() ? "" : " - " + world.detail();
            context.sender().sendMessage(VanillaCommandMessages.red(
                    "世界 " + world.name() + " - 状态: " + world.status() + detail
            ));
        }
    }

    @CommandHandler(value = "reload", usage = ROOT + " reload")
    void reload(CommandContext context) {
        context.requireArguments(0);
        context.sender().sendMessage(VanillaCommandMessages.red("正在重载配置并扫描世界快照。"));
        report(context, plugin.reloadWorldManager(), ignored -> "配置和世界快照重新加载完成。");
    }

    @CommandHandler(
            value = "teleport",
            aliases = {"tp"},
            usage = ROOT + " teleport <世界>"
    )
    void teleport(CommandContext context) {
        context.requireArguments(1);
        if (!(context.sender() instanceof Player player)) {
            context.sender().sendMessage(VanillaCommandMessages.red("该命令只能由玩家执行。"));
            return;
        }

        String worldName = context.argument(0);
        context.sender().sendMessage(VanillaCommandMessages.red("正在传送到世界: " + worldName));
        report(
                context,
                service().teleport(player, worldName),
                teleported -> Boolean.TRUE.equals(teleported)
                        ? "已传送到世界出生点: " + worldName
                        : "传送失败: " + worldName
        );
    }

    @CommandCompleter("teleport")
    List<String> completeTeleport(CommandContext context) {
        return completeWorlds(context);
    }

    @CommandHandler(value = "load", usage = ROOT + " load <世界>")
    void load(CommandContext context) {
        context.requireArguments(1);
        String worldName = context.argument(0);
        context.sender().sendMessage(VanillaCommandMessages.red("正在加载世界: " + worldName));
        report(context, service().load(worldName), ignored -> "世界已加载: " + worldName);
    }

    @CommandCompleter("load")
    List<String> completeLoad(CommandContext context) {
        return completeWorlds(context);
    }

    @CommandHandler(value = "unload", usage = ROOT + " unload <世界> [force]")
    void unload(CommandContext context) {
        if (context.arguments().isEmpty() || context.arguments().size() > 2) {
            throw context.invalidUsage();
        }
        boolean force = context.arguments().size() == 2;
        if (force && !context.argument(1).equalsIgnoreCase("force")) throw context.invalidUsage();

        String worldName = context.argument(0);
        report(context, service().unload(worldName, force), ignored -> "世界已卸载: " + worldName);
    }

    @CommandCompleter("unload")
    List<String> completeUnload(CommandContext context) {
        if (context.arguments().size() == 1) return completeWorlds(context);
        if (context.arguments().size() == 2
                && "force".startsWith(context.argument(1).toLowerCase(Locale.ROOT))) {
            return List.of("force");
        }
        return List.of();
    }

    @CommandHandler(value = "clean", usage = ROOT + " clean <世界>")
    void clean(CommandContext context) {
        context.requireArguments(1);
        String worldName = context.argument(0);
        report(
                context,
                service().clean(worldName),
                deleted -> "世界清理完成: " + worldName + ", 删除 " + deleted + " 项"
        );
    }

    @CommandCompleter("clean")
    List<String> completeClean(CommandContext context) {
        return completeWorlds(context);
    }

    @CommandHandler(value = "save", usage = ROOT + " save <世界>")
    void save(CommandContext context) {
        context.requireArguments(1);
        String worldName = context.argument(0);
        context.sender().sendMessage(VanillaCommandMessages.red("正在保存世界快照: " + worldName));
        report(
                context,
                service().save(worldName),
                copied -> "世界快照已保存: " + worldName + ", 复制 " + copied + " 项。"
        );
    }

    @CommandCompleter("save")
    List<String> completeSave(CommandContext context) {
        return completeWorlds(context);
    }

    private WorldManagerApi service() {
        return lifecycle.service();
    }

    private void report(
            CommandContext context,
            CompletableFuture<?> future,
            Function<Object, String> successMessage
    ) {
        future.whenComplete((result, error) -> lib.runOnMain(() -> {
            if (error != null) {
                context.sender().sendMessage(
                        VanillaCommandMessages.red("操作失败: " + LibApi.rootCauseMessage(error))
                );
                return;
            }
            context.sender().sendMessage(VanillaCommandMessages.red(successMessage.apply(result)));
        }));
    }

    private List<String> completeWorlds(CommandContext context) {
        if (context.arguments().size() != 1) return List.of();
        String prefix = context.argument(0).toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>(Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> !name.contains("/") && !name.contains("\\"))
                .toList());
        names.addAll(service().list().stream()
                .map(WorldInfo::name)
                .toList());
        return names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
