package dev.copperworks.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Анимации: голем стоит у медного сундука, поворачивается к цели, играет ванильные
 * анимации «взял/положил», а предмет летит по дуге (ItemDisplay с интерполяцией телепорта).
 */
public final class Visuals {
    private final Plugin plugin;
    private final BooleanSupplier enabled;
    private final Set<ItemDisplay> displays = new HashSet<>();
    private final Set<Block> openLids = new HashSet<>();

    public Visuals(Plugin plugin, BooleanSupplier enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
    }

    /** Сортировщик: взял из медного сундука, перекинул в целевой контейнер. */
    public void transfer(CopperGolem golem, Block from, Location to, ItemStack item) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        Location fromCenter = Containers.center(from);
        face(golem, fromCenter);
        golem.setGolemState(CopperGolem.State.GETTING_ITEM);
        openLid(from);
        later(7, () -> {
            closeLid(from);
            if (!golem.isValid()) {
                return;
            }
            face(golem, to);
            golem.setGolemState(CopperGolem.State.DROPPING_ITEM);
            golem.getWorld().playSound(golem, Sound.ENTITY_COPPER_GOLEM_ITEM_DROP, SoundCategory.NEUTRAL, 0.6f, 1.0f);
            fly(item, golem.getLocation().add(0, 0.9, 0), to);
        });
        later(22, () -> idle(golem));
    }

    /** Фермер: сорвал растение, отправил урожай в медный сундук. */
    public void harvest(CopperGolem golem, Block crop, Block post, ItemStack item) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        Location cropCenter = crop.getLocation().add(0.5, 0.5, 0.5);
        face(golem, cropCenter);
        golem.setGolemState(CopperGolem.State.GETTING_ITEM);
        golem.swingMainHand();
        crop.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, cropCenter, 6, 0.3, 0.3, 0.3, 0);
        later(7, () -> {
            if (!golem.isValid()) {
                return;
            }
            Location postCenter = Containers.center(post);
            face(golem, postCenter);
            golem.setGolemState(CopperGolem.State.DROPPING_ITEM);
            fly(item, cropCenter, postCenter);
        });
        later(22, () -> idle(golem));
    }

    /** Курьер: собрал груз и ушёл в рейс. */
    public void depart(CopperGolem golem, Block post) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        face(golem, Containers.center(post));
        golem.setGolemState(CopperGolem.State.GETTING_ITEM);
        openLid(post);
        later(10, () -> {
            closeLid(post);
            if (golem.isValid()) {
                puff(golem.getLocation());
                golem.setGolemState(CopperGolem.State.IDLE);
            }
        });
    }

    public void puff(Location location) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        World world = location.getWorld();
        Location at = location.clone().add(0, 0.6, 0);
        world.spawnParticle(Particle.CLOUD, at, 14, 0.3, 0.4, 0.3, 0.02);
        world.spawnParticle(Particle.WAX_OFF, at, 8, 0.4, 0.5, 0.4, 0);
        world.playSound(at, Sound.ENTITY_COPPER_GOLEM_SPIN, SoundCategory.NEUTRAL, 0.7f, 1.2f);
    }

    public void sparkle(Location location) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        location.getWorld().spawnParticle(Particle.WAX_ON, location, 10, 0.4, 0.4, 0.4, 0);
        location.getWorld().playSound(location, Sound.ENTITY_COPPER_GOLEM_ITEM_DROP, SoundCategory.BLOCKS, 0.7f, 0.9f);
    }

    /** Голем повысил уровень; newTrait — заодно получил черту характера. */
    public void levelUp(CopperGolem golem, boolean newTrait) {
        if (!enabled.getAsBoolean() || !golem.isValid()) {
            return;
        }
        World world = golem.getWorld();
        Location at = golem.getLocation().add(0, 0.9, 0);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 24, 0.3, 0.5, 0.3, 0.3);
        world.spawnParticle(Particle.WAX_ON, at, 12, 0.4, 0.5, 0.4, 0);
        world.playSound(golem, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, 0.6f, 1.3f);
        if (!newTrait) {
            return;
        }
        later(8, () -> {
            if (!golem.isValid()) {
                return;
            }
            Location top = golem.getLocation().add(0, 1.4, 0);
            golem.getWorld().spawnParticle(Particle.ENCHANT, top, 40, 0.5, 0.6, 0.5, 0.8);
            golem.getWorld().spawnParticle(Particle.END_ROD, top, 8, 0.3, 0.4, 0.3, 0.02);
            golem.getWorld().playSound(golem, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 1.0f, 1.2f);
        });
    }

    public void fly(ItemStack item, Location from, Location to) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld()) || item.isEmpty()) {
            return;
        }
        double distance = from.distance(to);
        // Длительность интерполяции у Display ограничена 59 тиками.
        int half = Math.clamp((int) Math.round(distance * 1.2) + 4, 4, 20);
        Location mid = from.clone().add(to).multiply(0.5).add(0, Math.min(2.5, 0.6 + distance * 0.12), 0);
        ItemDisplay display = world.spawn(from, ItemDisplay.class, d -> {
            d.setItemStack(item.asOne());
            d.setPersistent(false);
            d.setTeleportDuration(half);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.45f), new Quaternionf()));
        });
        displays.add(display);
        later(2, () -> {
            if (display.isValid()) {
                display.teleport(mid);
            }
        });
        later(2 + half, () -> {
            if (display.isValid()) {
                display.teleport(to);
            }
        });
        later(4 + half * 2, () -> {
            display.remove();
            displays.remove(display);
        });
    }

    public static void face(CopperGolem golem, Location target) {
        Location eye = golem.getEyeLocation();
        Vector direction = target.toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-4) {
            return;
        }
        Location look = eye.clone().setDirection(direction);
        golem.setRotation(look.getYaw(), look.getPitch());
        golem.setBodyYaw(look.getYaw());
    }

    private void idle(CopperGolem golem) {
        if (golem.isValid()) {
            golem.setGolemState(CopperGolem.State.IDLE);
        }
    }

    private void openLid(Block block) {
        BlockState state = block.getState(false);
        if (state instanceof Lidded lidded && !lidded.isOpen() && !hasViewers(state)) {
            lidded.open();
            openLids.add(block);
        }
    }

    private void closeLid(Block block) {
        closeLid(block, 0);
    }

    /** Lidded.close() сбрасывает счётчик открывших, поэтому ждём, пока игрок закроет сундук сам. */
    private void closeLid(Block block, int attempt) {
        if (!openLids.contains(block)) {
            return;
        }
        BlockState state = block.getState(false);
        if (hasViewers(state) && attempt < 30) {
            later(20, () -> closeLid(block, attempt + 1));
            return;
        }
        openLids.remove(block);
        if (state instanceof Lidded lidded) {
            lidded.close();
        }
    }

    private static boolean hasViewers(BlockState state) {
        return state instanceof Container container && !container.getInventory().getViewers().isEmpty();
    }

    private void later(long ticks, Runnable task) {
        Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /** При выключении плагина: убрать летящие предметы и закрыть сундуки. */
    public void cleanup() {
        displays.forEach(ItemDisplay::remove);
        displays.clear();
        for (Block block : Set.copyOf(openLids)) {
            if (block.getState(false) instanceof Lidded lidded) {
                lidded.close();
            }
        }
        openLids.clear();
    }
}
