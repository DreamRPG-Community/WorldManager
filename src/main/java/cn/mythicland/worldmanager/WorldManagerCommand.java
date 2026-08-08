package cn.mythicland.worldmanager;

import cn.mythicland.lib.api.LibApi;
import cn.mythicland.lib.command.CommandRouter;
import cn.mythicland.lib.command.CommandUsageException;
import cn.mythicland.lib.command.Subcommand;
import cn.mythicland.lib.command.VanillaCommandMessages;
import cn.mythicland.worldmanager.api.WorldInfo;
import cn.mythicland.worldmanager.api.WorldManagerApi;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class WorldManagerCommand {

    private static final String ADMIN_PERMISSION = "worldmanager.admin";

    private WorldManagerCommand() {
    }

    static void register(
            CommandRouter router,
            WorldManagerApi service,
            LibApi lib
    ) {
        router.register(new ListCommand(service));
        router.register(new ReloadCommand(service, lib));
        router.register(new TeleportCommand(service, lib));
        router.register(new LoadCommand(service, lib));
        router.register(new UnloadCommand(service, lib));
        router.register(new CleanCommand(service, lib));
        router.register(new SaveCommand(service, lib));
    }

    private static void report(
            LibApi lib,
            CommandSender sender,
            CompletableFuture<?> future,
            Function<Object, String> successMessage
    ) {
        future.whenComplete((result, error) -> lib.runOnMain(() -> {
            if (error != null) {
                sender.sendMessage(
                        VanillaCommandMessages.red("操作失败: " + LibApi.rootCauseMessage(error))
                );
                return;
            }
            sender.sendMessage(VanillaCommandMessages.red(successMessage.apply(result)));
        }));
    }

    private static List<String> completeWorlds(WorldManagerApi service, List<String> arguments) {
        if (arguments.size() != 1) return List.of();
        String prefix = arguments.getFirst().toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>(Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(name -> !name.contains("/") && !name.contains("\\"))
                .toList());
        names.addAll(service.list().stream()
                .map(WorldInfo::name)
                .toList());
        return names.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private abstract static class AdminCommand implements Subcommand {

        protected final WorldManagerApi service;
        protected final LibApi lib;

        private AdminCommand(WorldManagerApi service, LibApi lib) {
            this.service = service;
            this.lib = lib;
        }

        @Override
        public String permission() {
            return ADMIN_PERMISSION;
        }
    }

    private record ListCommand(WorldManagerApi service) implements Subcommand {

        @Override
        public String name() {
            return "list";
        }

        @Override
        public String usage() {
            return "/worldmanager list";
        }

        @Override
        public String permission() {
            return ADMIN_PERMISSION;
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (!arguments.isEmpty()) throw new CommandUsageException(usage());

            Collection<WorldInfo> worlds = service.list();
            if (worlds.isEmpty()) {
                sender.sendMessage(VanillaCommandMessages.red("没有发现受管理的世界。"));
                return;
            }
            for (WorldInfo world : worlds) {
                String detail = world.detail().isBlank() ? "" : " - " + world.detail();
                sender.sendMessage(VanillaCommandMessages.red(
                        "世界 " + world.name() + " - 状态: " + world.status() + detail
                ));
            }
        }
    }

    private static final class ReloadCommand extends AdminCommand {

        private ReloadCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "reload";
        }

        @Override
        public String usage() {
            return "/worldmanager reload";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (!arguments.isEmpty()) throw new CommandUsageException(usage());
            sender.sendMessage(VanillaCommandMessages.red("正在重载配置并扫描世界快照。"));
            report(
                    lib,
                    sender,
                    service.reload(),
                    ignored -> "配置和世界快照重新加载完成。"
            );
        }
    }

    private static final class TeleportCommand extends AdminCommand {

        private TeleportCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "teleport";
        }

        @Override
        public Collection<String> aliases() {
            return List.of("tp");
        }

        @Override
        public String usage() {
            return "/worldmanager teleport <世界>";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (arguments.size() != 1) throw new CommandUsageException(usage());
            if (!(sender instanceof Player player)) {
                sender.sendMessage(VanillaCommandMessages.red("该命令只能由玩家执行。"));
                return;
            }

            String worldName = arguments.getFirst();
            sender.sendMessage(VanillaCommandMessages.red("正在传送到世界: " + worldName));
            report(
                    lib,
                    sender,
                    service.teleport(player, worldName),
                    teleported -> Boolean.TRUE.equals(teleported)
                            ? "已传送到世界出生点: " + worldName
                            : "传送失败: " + worldName
            );
        }

        @Override
        public List<String> tabComplete(CommandSender sender, List<String> arguments) {
            return completeWorlds(service, arguments);
        }
    }

    private static final class LoadCommand extends AdminCommand {

        private LoadCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "load";
        }

        @Override
        public String usage() {
            return "/worldmanager load <世界>";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (arguments.size() != 1) throw new CommandUsageException(usage());
            String worldName = arguments.getFirst();
            sender.sendMessage(VanillaCommandMessages.red("正在加载世界: " + worldName));
            report(lib, sender, service.load(worldName), ignored -> "世界已加载: " + worldName);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, List<String> arguments) {
            return completeWorlds(service, arguments);
        }
    }

    private static final class UnloadCommand extends AdminCommand {

        private UnloadCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "unload";
        }

        @Override
        public String usage() {
            return "/worldmanager unload <世界> [force]";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (arguments.isEmpty() || arguments.size() > 2) throw new CommandUsageException(usage());
            boolean force = arguments.size() == 2;
            if (force && !arguments.get(1).equalsIgnoreCase("force")) {
                throw new CommandUsageException(usage());
            }

            String worldName = arguments.getFirst();
            report(
                    lib,
                    sender,
                    service.unload(worldName, force),
                    ignored -> "世界已卸载: " + worldName
            );
        }

        @Override
        public List<String> tabComplete(CommandSender sender, List<String> arguments) {
            if (arguments.size() == 1) return completeWorlds(service, arguments);
            if (arguments.size() == 2 && "force".startsWith(arguments.get(1).toLowerCase(Locale.ROOT))) {
                return List.of("force");
            }
            return List.of();
        }
    }

    private static final class CleanCommand extends AdminCommand {

        private CleanCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "clean";
        }

        @Override
        public String usage() {
            return "/worldmanager clean <世界>";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (arguments.size() != 1) throw new CommandUsageException(usage());
            String worldName = arguments.getFirst();
            report(
                    lib,
                    sender,
                    service.clean(worldName),
                    deleted -> "世界清理完成: " + worldName + ", 删除 " + deleted + " 项"
            );
        }

        @Override
        public List<String> tabComplete(CommandSender sender, List<String> arguments) {
            return completeWorlds(service, arguments);
        }
    }

    private static final class SaveCommand extends AdminCommand {

        private SaveCommand(WorldManagerApi service, LibApi lib) {
            super(service, lib);
        }

        @Override
        public String name() {
            return "save";
        }

        @Override
        public String usage() {
            return "/worldmanager save <世界>";
        }

        @Override
        public void execute(CommandSender sender, List<String> arguments) {
            if (arguments.size() != 1) throw new CommandUsageException(usage());
            String worldName = arguments.getFirst();
            sender.sendMessage(VanillaCommandMessages.red("正在保存世界快照: " + worldName));
            report(
                    lib,
                    sender,
                    service.save(worldName),
                    copied -> "世界快照已保存: " + worldName + ", 复制 " + copied + " 项。"
            );
        }

        @Override
        public List<String> tabComplete(CommandSender sender, List<String> arguments) {
            return completeWorlds(service, arguments);
        }
    }
}
