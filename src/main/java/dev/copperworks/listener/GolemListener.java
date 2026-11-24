package dev.copperworks.listener;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.job.CourierJob;
import dev.copperworks.ui.LinkSessions;
import dev.copperworks.worker.Job;
import dev.copperworks.worker.Phase;
import dev.copperworks.worker.Worker;
import dev.copperworks.worker.WorkerService;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class GolemListener implements Listener {
    private final CopperWorksPlugin plugin;

    public GolemListener(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    /** Shift + ПКМ пустой рукой по медному голему — открыть меню. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof CopperGolem golem)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }
        // Гасим и событие второй руки, чтобы, например, соты из левой руки не вощили голема мимо меню.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!player.hasPermission(WorkerService.PERM_USE)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        plugin.dialogs().open(player, golem);
    }

    /** Выбор точки доставки курьера. HIGHEST — чтобы сначала отработали плагины приватов. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLinkClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        LinkSessions.Pending pending = plugin.links().peek(player.getUniqueId());
        if (block == null || pending == null) {
            return;
        }
        plugin.links().clear(player.getUniqueId());
        boolean denied = event.useInteractedBlock() == Event.Result.DENY;
        event.setCancelled(true);
        if (denied) {
            plugin.messages().send(player, "link.no-access");
            return;
        }
        Entity entity = Bukkit.getEntity(pending.golem());
        if (!(entity instanceof CopperGolem golem) || !Worker.isWorker(golem)) {
            plugin.messages().send(player, "golem-gone");
            return;
        }
        plugin.service().setTarget(player, golem, block);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem && Worker.isWorker(golem)) {
            plugin.registry().track(golem);
            UUID owner = new Worker(golem).owner();
            if (owner != null) {
                plugin.ownerIndex().add(owner, golem.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            plugin.registry().untrack(golem);
        }
    }

    /** HIGHEST, а не MONITOR: меняем дропы. ignoreCancelled — если смерть отменили, груз не трогаем. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Worker worker = Worker.of(event.getEntity());
        if (worker == null) {
            return;
        }
        event.getDrops().addAll(CourierJob.dropCargo(worker));
        UUID owner = worker.owner();
        Job job = worker.job();
        plugin.registry().untrack(worker.golem());
        if (owner == null) {
            return;
        }
        plugin.ownerIndex().remove(owner, worker.golem().getUniqueId());
        Player player = Bukkit.getPlayer(owner);
        if (player != null && job != null) {
            Location at = worker.golem().getLocation();
            plugin.messages().send(player, "death",
                Placeholder.component("job", plugin.service().jobName(job)),
                Placeholder.unparsed("x", String.valueOf(at.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(at.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(at.getBlockZ())));
        }
    }

    /** Защита рабочих: бить может только владелец (и админ). /kill и пустота работают как обычно. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.settings().protectWorkers) {
            return;
        }
        Worker worker = Worker.of(event.getEntity());
        if (worker == null) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.KILL
            || cause == EntityDamageEvent.DamageCause.VOID
            || cause == EntityDamageEvent.DamageCause.WORLD_BORDER) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            if (damager instanceof Player player
                && (player.getUniqueId().equals(worker.owner()) || player.hasPermission(WorkerService.PERM_ADMIN))) {
                return;
            }
        }
        event.setCancelled(true);
    }

    /**
     * Полностью окисленный ванильный голем может стать статуей. Нанятых не отдаём (prevent-statue),
     * а курьера в рейсе не отдаём никогда — иначе пропадёт груз.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStatue(EntityChangeBlockEvent event) {
        Worker worker = Worker.of(event.getEntity());
        if (worker == null || !isStatue(event)) {
            return;
        }
        if (plugin.settings().preventStatue || worker.phase() != Phase.IDLE) {
            event.setCancelled(true);
        }
    }

    /** Голем всё-таки стал статуей (prevent-statue: false) — освобождаем слот владельца. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatueDone(EntityChangeBlockEvent event) {
        Worker worker = Worker.of(event.getEntity());
        if (worker == null || !isStatue(event)) {
            return;
        }
        UUID owner = worker.owner();
        plugin.registry().untrack(worker.golem());
        if (owner != null) {
            plugin.ownerIndex().remove(owner, worker.golem().getUniqueId());
        }
    }

    private static boolean isStatue(EntityChangeBlockEvent event) {
        return event.getTo().name().endsWith("COPPER_GOLEM_STATUE");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.links().clear(event.getPlayer().getUniqueId());
    }
}
