package dev.copperworks.worker;

import dev.copperworks.CopperWorksPlugin;
import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.job.CourierJob;
import dev.copperworks.job.Posts;
import dev.copperworks.util.Containers;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Найм, увольнение, улучшения — всё, что меняет рабочего по действию игрока. */
public final class WorkerService {
    public static final String PERM_USE = "copperworks.use";
    public static final String PERM_UNLIMITED = "copperworks.unlimited";
    public static final String PERM_ADMIN = "copperworks.admin";

    private final CopperWorksPlugin plugin;

    public WorkerService(CopperWorksPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Messages messages() {
        return plugin.messages();
    }

    public boolean canManage(Player player, Worker worker) {
        return player.getUniqueId().equals(worker.owner()) || player.hasPermission(PERM_ADMIN);
    }

    public boolean hire(Player player, CopperGolem golem, Job job) {
        Settings s = settings();
        if (!player.hasPermission(PERM_USE)) {
            messages().send(player, "no-permission");
            return false;
        }
        if (Worker.isWorker(golem)) {
            messages().send(player, "hire.already");
            return false;
        }
        if (!s.enabled(job)) {
            messages().send(player, "hire.job-disabled");
            return false;
        }
        int max = s.maxWorkersPerPlayer;
        if (max >= 0 && !player.hasPermission(PERM_UNLIMITED) && plugin.ownerIndex().count(player.getUniqueId()) >= max) {
            messages().send(player, "hire.limit", Placeholder.unparsed("max", String.valueOf(max)));
            return false;
        }
        Block post = Containers.findCopperChest(golem.getLocation(), s.postSearchRadius);
        if (post == null) {
            messages().send(player, "hire.no-post", Placeholder.unparsed("radius", String.valueOf(s.postSearchRadius)));
            return false;
        }

        Worker worker = new Worker(golem);
        worker.owner(player.getUniqueId());
        worker.job(job);
        worker.post(BlockKey.of(post));

        settle(golem);
        plugin.ownerIndex().add(player.getUniqueId(), golem.getUniqueId());
        plugin.registry().track(golem);
        plugin.registry().state(golem).reset();
        plugin.visuals().sparkle(golem.getLocation().add(0, 1, 0));
        messages().send(player, "hire.success", Placeholder.component("job", jobName(job)));
        return true;
    }

    /**
     * Переводит голема под управление плагина: выключает ванильный ИИ (окисление при этом идёт дальше).
     * Если голем прямо сейчас открыл сундук или несёт предмет, ждём, пока он закончит, иначе
     * крышка сундука может остаться открытой. Вызывается при найме и из главного цикла.
     *
     * @return true, если голем уже под управлением плагина
     */
    public boolean settle(CopperGolem golem) {
        if (!golem.isAware()) {
            return true;
        }
        if (golem.getGolemState() != CopperGolem.State.IDLE) {
            return false;
        }
        ItemStack held = golem.getEquipment().getItemInMainHand();
        if (!held.isEmpty()) {
            golem.getEquipment().setItemInMainHand(null);
            Worker worker = Worker.of(golem);
            Block post = worker == null ? null : Posts.resolve(worker, settings());
            Inventory inventory = post == null ? null : Containers.inventory(post);
            List<ItemStack> rest = inventory == null ? List.of(held) : Containers.add(inventory, List.of(held));
            rest.forEach(item -> golem.getWorld().dropItemNaturally(golem.getLocation(), item));
        }
        golem.setAware(false);
        return true;
    }

    public void changeJob(Player player, CopperGolem golem, Job job) {
        Worker worker = Worker.of(golem);
        if (worker == null) {
            return;
        }
        if (worker.phase() != Phase.IDLE) {
            messages().send(player, "fire.on-trip");
            return;
        }
        if (!settings().enabled(job)) {
            messages().send(player, "hire.job-disabled");
            return;
        }
        worker.job(job);
        worker.clearTrip();
        plugin.registry().state(golem).reset();
        messages().send(player, "job-changed", Placeholder.component("job", jobName(job)));
    }

    public void fire(Player player, CopperGolem golem) {
        Worker worker = Worker.of(golem);
        if (worker == null) {
            return;
        }
        if (worker.phase() != Phase.IDLE) {
            messages().send(player, "fire.on-trip");
            return;
        }
        release(worker);
        messages().send(player, "fire.success");
    }

    /** Возвращает голема в ваниль. */
    public void release(Worker worker) {
        CopperGolem golem = worker.golem();
        UUID owner = worker.owner();
        for (ItemStack item : CourierJob.dropCargo(worker)) {
            golem.getWorld().dropItemNaturally(golem.getLocation(), item);
        }
        worker.clearAll();
        golem.setGolemState(CopperGolem.State.IDLE);
        golem.setAware(true);
        plugin.registry().untrack(golem);
        if (owner != null) {
            plugin.ownerIndex().remove(owner, golem.getUniqueId());
        }
    }

    public void upgrade(Player player, CopperGolem golem, Upgrade upgrade) {
        Worker worker = Worker.of(golem);
        if (worker == null) {
            return;
        }
        int level = worker.level(upgrade);
        if (level >= Upgrade.MAX_LEVEL) {
            messages().send(player, "upgrade.max");
            return;
        }
        Settings s = settings();
        int cost = s.upgradeCost(level);
        Material item = s.upgradeItem;
        boolean free = player.getGameMode() == GameMode.CREATIVE;
        if (!free && !player.getInventory().containsAtLeast(new ItemStack(item), cost)) {
            messages().send(player, "upgrade.not-enough",
                Placeholder.unparsed("cost", String.valueOf(cost)),
                Placeholder.component("item", Component.translatable(item)));
            return;
        }
        if (!free) {
            int remaining = cost;
            while (remaining > 0) {
                int take = Math.min(remaining, item.getMaxStackSize());
                player.getInventory().removeItem(new ItemStack(item, take));
                remaining -= take;
            }
        }
        worker.level(upgrade, level + 1);
        plugin.registry().state(golem).invalidateCaches();
        player.playSound(player, Sound.BLOCK_COPPER_BULB_TURN_ON, SoundCategory.PLAYERS, 0.8f, 1.2f);
        plugin.visuals().sparkle(golem.getLocation().add(0, 1, 0));
        messages().send(player, "upgrade.success",
            Placeholder.component("upgrade", messages().get("upgrade-name." + upgrade.id())),
            Placeholder.unparsed("level", String.valueOf(level + 1)));
    }

    public void setTarget(Player player, CopperGolem golem, Block block) {
        Worker worker = Worker.of(golem);
        if (worker == null) {
            messages().send(player, "golem-gone");
            return;
        }
        if (!Containers.isStorage(block.getType()) || Containers.inventory(block) == null) {
            messages().send(player, "link.not-container");
            return;
        }
        if (!block.getWorld().equals(golem.getWorld())) {
            messages().send(player, "link.other-world");
            return;
        }
        Block post = Posts.resolve(worker, settings());
        if (post != null && post.equals(block)) {
            messages().send(player, "link.same-post");
            return;
        }
        Location from = post != null ? post.getLocation() : golem.getLocation();
        int distance = (int) Math.round(from.distance(block.getLocation()));
        int max = settings().courierMaxDistance[worker.level(Upgrade.RANGE)];
        if (max >= 0 && distance > max) {
            messages().send(player, "link.too-far",
                Placeholder.unparsed("distance", String.valueOf(distance)),
                Placeholder.unparsed("max", String.valueOf(max)));
            return;
        }
        worker.target(block);
        plugin.registry().state(golem).nextActionTick = 0;
        plugin.visuals().sparkle(block.getLocation().add(0.5, 1.0, 0.5));
        messages().send(player, "link.success",
            Placeholder.unparsed("x", String.valueOf(block.getX())),
            Placeholder.unparsed("y", String.valueOf(block.getY())),
            Placeholder.unparsed("z", String.valueOf(block.getZ())),
            Placeholder.unparsed("distance", String.valueOf(distance)));
    }

    public Component jobName(Job job) {
        return messages().get("job." + job.id() + ".name");
    }
}
