package dev.copperworks.job;

import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.Phase;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Курьер: забирает стаки из медного сундука и «уходит в рейс» — становится невидимым на время пути.
 * Время рейса считается по реальным часам, поэтому работает и через выгруженные чанки, и через рестарт:
 * груз хранится в PDC голема, а чанк точки доставки подгружается асинхронно только в момент доставки.
 */
public final class CourierJob implements JobHandler {
    private static final int CHECK_TICKS = 20;

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final Messages messages;
    private final Visuals visuals;

    public CourierJob(Plugin plugin, Supplier<Settings> settings, Messages messages, Visuals visuals) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.visuals = visuals;
    }

    @Override
    public int work(Worker worker, RuntimeState state, long tick) {
        Settings s = settings.get();
        CopperGolem golem = worker.golem();
        Phase phase = worker.phase();
        setAway(golem, phase != Phase.IDLE);
        return switch (phase) {
            case IDLE -> startTrip(worker, state, s);
            case OUTBOUND -> {
                state.status = Status.ON_TRIP;
                if (System.currentTimeMillis() >= worker.phaseUntil()) {
                    deliver(worker, state);
                }
                yield CHECK_TICKS;
            }
            case RETURNING -> {
                state.status = Status.RETURNING;
                if (System.currentTimeMillis() >= worker.phaseUntil()) {
                    finishTrip(worker, state, s);
                    yield s.scaledInterval(40, s.speedFactor(worker));
                }
                yield CHECK_TICKS;
            }
        };
    }

    private int startTrip(Worker worker, RuntimeState state, Settings s) {
        Block post = Posts.resolve(worker, s);
        Inventory input = post == null ? null : Containers.inventory(post);
        if (post == null || input == null) {
            state.status = Status.NO_POST;
            return 40;
        }
        Location target = worker.target();
        if (target == null) {
            state.status = Status.NO_TARGET;
            return 40;
        }
        if (!target.getWorld().equals(post.getWorld())) {
            state.status = Status.OTHER_WORLD;
            return 100;
        }
        double distance = post.getLocation().distance(target);
        int max = s.courierMaxDistance[worker.level(Upgrade.RANGE)];
        if (max >= 0 && distance > max) {
            state.status = Status.TOO_FAR;
            return 100;
        }

        List<ItemStack> cargo = takeStacks(input, s.courierStacks[worker.level(Upgrade.CAPACITY)]);
        if (cargo.isEmpty()) {
            state.status = Status.WAITING_CARGO;
            return 40;
        }
        double blocksPerSecond = s.courierBlocksPerSecond * s.speedFactor(worker);
        long leg = Math.max(s.courierMinLegSeconds * 1000L, (long) (distance / blocksPerSecond * 1000));
        worker.cargo(cargo);
        worker.legMillis(leg);
        worker.phaseUntil(System.currentTimeMillis() + leg);
        worker.phase(Phase.OUTBOUND);
        state.status = Status.ON_TRIP;

        CopperGolem golem = worker.golem();
        visuals.depart(golem, post);
        // Исчезает после короткой анимации (ticker тоже следит за невидимостью).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (golem.isValid() && new Worker(golem).phase() != Phase.IDLE) {
                setAway(golem, true);
            }
        }, 12L);
        return CHECK_TICKS;
    }

    private void deliver(Worker worker, RuntimeState state) {
        Location target = worker.target();
        if (target == null) {
            turnBack(worker, worker.cargo(), 0);
            return;
        }
        World world = target.getWorld();
        state.busy = true;
        CopperGolem golem = worker.golem();
        world.getChunkAtAsync(target.getBlockX() >> 4, target.getBlockZ() >> 4).whenComplete((chunk, error) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                state.busy = false;
                if (!golem.isValid()) {
                    return; // голем выгрузился — продолжим, когда чанк снова загрузится
                }
                Worker fresh = new Worker(golem);
                if (fresh.phase() != Phase.OUTBOUND) {
                    return;
                }
                List<ItemStack> cargo = fresh.cargo();
                Block targetBlock = target.getBlock();
                Inventory output = error == null && Containers.isStorage(targetBlock.getType())
                    ? Containers.inventory(targetBlock) : null;
                if (output == null) {
                    turnBack(fresh, cargo, 0);
                    notifyOwner(fresh, "courier.no-target", target, 0, 0);
                    return;
                }
                List<ItemStack> rest = Containers.add(targetBlock.getType(), output, cargo);
                int delivered = Containers.total(cargo) - Containers.total(rest);
                turnBack(fresh, rest, delivered);
                visuals.sparkle(target.clone().add(0.5, 1.0, 0.5));
                if (rest.isEmpty()) {
                    notifyOwner(fresh, "courier.delivered", target, delivered, 0);
                } else {
                    notifyOwner(fresh, "courier.partial", target, delivered, Containers.total(rest));
                }
            }));
    }

    private static void turnBack(Worker worker, List<ItemStack> rest, int delivered) {
        worker.addMoved(delivered);
        worker.cargo(rest);
        worker.phase(Phase.RETURNING);
        // Обратный путь отсчитывается от момента прибытия; если голем долго был выгружен — вернётся сразу.
        worker.phaseUntil(worker.phaseUntil() + worker.legMillis());
    }

    private void finishTrip(Worker worker, RuntimeState state, Settings s) {
        CopperGolem golem = worker.golem();
        List<ItemStack> rest = worker.cargo();
        if (!rest.isEmpty()) {
            Block post = Posts.resolve(worker, s);
            Inventory input = post == null ? null : Containers.inventory(post);
            List<ItemStack> overflow = input == null ? rest : Containers.add(input, rest);
            for (ItemStack item : overflow) {
                golem.getWorld().dropItemNaturally(golem.getLocation(), item);
            }
        }
        worker.clearTrip();
        setAway(golem, false);
        visuals.puff(golem.getLocation());
        state.status = Status.WAITING_CARGO;
    }

    /** Вызывается при увольнении/смерти: вернуть груз в мир. */
    public static List<ItemStack> dropCargo(Worker worker) {
        List<ItemStack> cargo = worker.cargo();
        worker.clearTrip();
        setAway(worker.golem(), false);
        return cargo;
    }

    /** Все четыре флага ставим всегда: коллизия не сохраняется в NBT и после рестарта сбрасывается. */
    public static void setAway(CopperGolem golem, boolean away) {
        golem.setInvisible(away);
        golem.setInvulnerable(away);
        golem.setSilent(away);
        golem.setCollidable(!away);
    }

    private static List<ItemStack> takeStacks(Inventory inventory, int maxStacks) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && taken.size() < maxStacks; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            taken.add(item.clone());
            inventory.setItem(slot, null);
        }
        return taken;
    }

    private void notifyOwner(Worker worker, String key, Location at, int amount, int left) {
        if (!settings.get().courierNotify) {
            return;
        }
        UUID owner = worker.owner();
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player == null) {
            return;
        }
        messages.actionBar(player, key,
            Placeholder.unparsed("amount", String.valueOf(amount)),
            Placeholder.unparsed("left", String.valueOf(left)),
            Placeholder.unparsed("x", String.valueOf(at.getBlockX())),
            Placeholder.unparsed("y", String.valueOf(at.getBlockY())),
            Placeholder.unparsed("z", String.valueOf(at.getBlockZ())));
    }
}
