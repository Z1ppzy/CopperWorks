package dev.copperworks.ui;

import dev.copperworks.CopperWorksPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** «Кликни по сундуку, куда возить» — ожидание выбора точки доставки курьера. */
public final class LinkSessions {
    private static final int SECONDS = 30;

    private final CopperWorksPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public record Pending(UUID golem, long expiresAt) {
    }

    public LinkSessions(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Player player, CopperGolem golem) {
        Pending session = new Pending(golem.getUniqueId(), System.currentTimeMillis() + SECONDS * 1000L);
        pending.put(player.getUniqueId(), session);
        plugin.messages().send(player, "link.start", Placeholder.unparsed("seconds", String.valueOf(SECONDS)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId(), session) && player.isOnline()) {
                plugin.messages().send(player, "link.expired");
            }
        }, SECONDS * 20L);
    }

    public @Nullable Pending peek(UUID player) {
        Pending session = pending.get(player);
        if (session != null && session.expiresAt() < System.currentTimeMillis()) {
            pending.remove(player);
            return null;
        }
        return session;
    }

    public void clear(UUID player) {
        pending.remove(player);
    }
}
