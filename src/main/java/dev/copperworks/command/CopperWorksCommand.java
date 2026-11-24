package dev.copperworks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.worker.WorkerService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/** /copperworks (/cw): help, list, reload. */
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
                    plugin.reload();
                    plugin.messages().send(ctx.getSource().getSender(), "command.reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
        commands.register(root, "Медные големы-рабочие", List.of("cw"));
    }
}
