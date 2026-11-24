package dev.copperworks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.util.Log;
import dev.copperworks.worker.WorkerService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/** /copperworks (/cw): help, list, reload, xp. */
public final class CopperWorksCommand {
    private CopperWorksCommand() {
    }

    public static void register(CopperWorksPlugin plugin, Commands commands) {
        LiteralCommandNode<CommandSourceStack> root = Commands.literal("copperworks")
            .executes(ctx -> {
                ctx.getSource().getSender().sendMessage(plugin.messages().get("command.help"));
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.literal("list")
                .requires(src -> src.getSender() instanceof Player)
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource().getSender();
                    int max = plugin.settings().maxWorkersPerPlayer;
                    boolean unlimited = max < 0 || player.hasPermission(WorkerService.PERM_UNLIMITED);
                    plugin.messages().send(player, "command.list",
                        Placeholder.unparsed("count", String.valueOf(plugin.ownerIndex().count(player.getUniqueId()))),
                        unlimited
                            ? Placeholder.component("max", plugin.messages().get("command.list-unlimited"))
                            : Placeholder.unparsed("max", String.valueOf(max)));
                    return Command.SINGLE_SUCCESS;
                }))
            .then(Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission(WorkerService.PERM_ADMIN))
                .executes(ctx -> {
                    Log.info("command", ctx.getSource().getSender().getName() + " выполнил /cw reload");
                    plugin.reload();
                    plugin.messages().send(ctx.getSource().getSender(), "command.reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
            // /cw xp <опыт> — выдать опыт голему под взглядом (для настройки и проверки уровней и черт).
            .then(Commands.literal("xp")
                .requires(src -> src.getSender() instanceof Player
                    && src.getSender().hasPermission(WorkerService.PERM_ADMIN))
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(1, 1_000_000))
                    .executes(ctx -> {
                        Player player = (Player) ctx.getSource().getSender();
                        plugin.service().giveXp(player, DoubleArgumentType.getDouble(ctx, "amount"));
                        return Command.SINGLE_SUCCESS;
                    })))
            .build();
        commands.register(root, "Медные големы-рабочие", List.of("cw"));
    }
}
