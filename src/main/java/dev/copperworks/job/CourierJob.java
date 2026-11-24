package dev.copperworks.job;

import dev.copperworks.config.Messages;
import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.util.Log;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.Phase;
import dev.copperworks.worker.Progression;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Traits;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
    private final Progression progression;

    public CourierJob(Plugin plugin, Supplier<Settings> settings, Messages messages, Visuals visuals,
                      Progression progression) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.visuals = visuals;
        this.progression = progression;
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
                    yield Traits.interval(s, worker, 40);
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

        int maxStacks = Traits.courierStacks(s, worker);
        // Рамки на медном сундуке курьера: возить только то, что в них (с группами, как у сортировщика).
        Set<Material> filter = s.courierFrameFilter ? Frames.on(post) : Set.of();
        List<ItemStack> cargo = takeStacks(input, maxStacks,
            item -> filter.isEmpty() || SortTarget.accepts(filter, item.getType(), s.sorterGroups));
        if (cargo.isEmpty()) {
            boolean filtered = !filter.isEmpty() && !input.isEmpty();
            if (filtered && state.status != Status.NOTHING_TO_SEND) {
                Log.debug("courier", () -> "golem=" + Log.id(worker.golem()) + " в медном сундуке нет вещей под рамки "
                    + filter + ", ждёт");
            }
            state.status = filtered ? Status.NOTHING_TO_SEND : Status.WAITING_CARGO;
            return 40;
        }
        double blocksPerSecond = s.courierBlocksPerSecond * Traits.speedFactor(s, worker);
        long baseLeg = Math.max(s.courierMinLegSeconds * 1000L, (long) (distance / blocksPerSecond * 1000));
        long leg = Traits.tripLegMillis(s, worker, baseLeg);
        worker.cargo(cargo);
        worker.legMillis(leg);
        worker.phaseUntil(System.currentTimeMillis() + leg);
        worker.phase(Phase.OUTBOUND);
        state.status = Status.ON_TRIP;
        Log.debug("courier", () -> "golem=" + Log.id(worker.golem()) + " ушёл в рейс: стаков " + cargo.size() + "/" + maxStacks
            + ", предметов " + Containers.total(cargo) + ", дистанция " + Math.round(distance) + " бл., плечо "
            + leg + " мс" + (leg != baseLeg ? " (без черт " + baseLeg + " мс)" : "")
            + (filter.isEmpty() ? "" : ", фильтр рамок " + filter));

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
                    Log.debug("courier", () -> "golem=" + Log.id(golem) + " выгрузился во время загрузки чанка доставки,"
                        + " доставим, когда снова загрузится");
                    return; // голем выгрузился — продолжим, когда чанк снова загрузится
                }
                Worker fresh = new Worker(golem);
                if (fresh.phase() != Phase.OUTBOUND) {
                    Log.debug("courier", () -> "golem=" + Log.id(golem) + " уже не в фазе OUTBOUND (" + fresh.phase()
                        + "), доставку пропускаю");
                    return;
                }
                if (error != null) {
                    Log.warn("courier", "golem=" + Log.id(golem) + " не удалось загрузить чанк точки доставки "
                        + target.getWorld().getName() + " " + target.getBlockX() + " " + target.getBlockY() + " "
                        + target.getBlockZ() + ", везёт груз обратно", error);
                }
                List<ItemStack> cargo = fresh.cargo();
                Block targetBlock = target.getBlock();
                Inventory output = error == null && Containers.isStorage(targetBlock.getType())
                    ? Containers.inventory(targetBlock) : null;
                if (output == null) {
                    Log.info("courier", "golem=" + Log.id(golem) + " owner=" + Log.id(fresh.owner())
                        + " не нашёл контейнер в " + target.getBlockX() + " " + target.getBlockY() + " "
                        + target.getBlockZ() + " (блок " + targetBlock.getType() + "), везёт "
                        + Containers.total(cargo) + " предм. обратно");
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
                // Курьер стоит у своего сундука (невидимый), так что это и есть дистанция рейса.
                double distance = golem.getWorld().equals(world) ? golem.getLocation().distance(target) : 0;
                Log.debug("courier", () -> "golem=" + Log.id(golem) + " доставил " + delivered + " предм. в "
                    + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ()
                    + ", не влезло " + Containers.total(rest) + ", дистанция " + Math.round(distance) + " бл.");
                progression.delivered(fresh, delivered, distance);
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

    private static List<ItemStack> takeStacks(Inventory inventory, int maxStacks, Predicate<ItemStack> accept) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && taken.size() < maxStacks; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty() || !accept.test(item)) {
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
