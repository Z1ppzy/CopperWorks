package dev.copperworks.job;

import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.util.Log;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.BlockKey;
import dev.copperworks.worker.Progression;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Traits;
import dev.copperworks.worker.Worker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Сортировщик: берёт вещи из медного сундука и раскладывает по контейнерам вокруг.
 * Приоритет: рамка-фильтр (точное совпадение или группа) → контейнер без фильтра, где такой предмет уже есть.
 * Черта «Аккуратный» сначала раскладывает то, для чего есть фильтр, и выбирает точный фильтр раньше группового.
 */
public final class SorterJob implements JobHandler {
    private static final int RESCAN_TICKS = 200;

    private final Supplier<Settings> settings;
    private final Visuals visuals;
    private final Progression progression;

    public SorterJob(Supplier<Settings> settings, Visuals visuals, Progression progression) {
        this.settings = settings;
        this.visuals = visuals;
        this.progression = progression;
    }

    @Override
    public int work(Worker worker, RuntimeState state, long tick) {
        Settings s = settings.get();
        Block post = Posts.resolve(worker, s);
        if (post == null) {
            state.status = Status.NO_POST;
            return 40;
        }
        Inventory input = Containers.inventory(post);
        if (input == null) {
            state.status = Status.NO_POST;
            return 40;
        }

        int radius = Traits.sorterRadius(s, worker);
        BlockKey postKey = BlockKey.of(post);
        if (state.sortDirty || tick - state.sortScannedTick > Traits.sorterRescanTicks(s, worker, RESCAN_TICKS)
            || state.sortRadius != radius || !postKey.equals(state.sortPost)) {
            boolean dirty = state.sortDirty;
            state.sortTargets = SortScanner.scan(post, radius);
            state.sortDirty = false;
            state.sortScannedTick = tick;
            state.sortRadius = radius;
            state.sortPost = postKey;
            Log.debug("sorter", () -> "golem=" + Log.id(worker.golem()) + " пересканировал сундуки: post=" + postKey
                + " radius=" + radius + " dirty=" + dirty + " найдено=" + state.sortTargets.size());
        }

        int interval = Traits.interval(s, worker, s.sorterBaseInterval);
        if (input.isEmpty()) {
            state.status = Status.IDLE;
            return interval;
        }

        World world = post.getWorld();
        int perMove = Traits.sorterItemsPerMove(s, worker);
        boolean tidy = Traits.sorterPrefersFilters(s, worker);
        if ((tidy && moveOne(worker, state, post, input, world, perMove, s, true, true))
            || moveOne(worker, state, post, input, world, perMove, s, tidy, false)) {
            return interval;
        }
        state.status = Status.NOTHING_TO_SORT;
        return Math.max(interval, 60);
    }

    /**
     * Переносит одну пачку предметов из медного сундука.
     *
     * @param exactFirst  среди сундуков с фильтрами сначала точное совпадение, потом группа
     * @param filtersOnly только в сундуки с подходящей рамкой-фильтром
     * @return true, если что-то перенесено
     */
    private boolean moveOne(Worker worker, RuntimeState state, Block post, Inventory input, World world,
                            int perMove, Settings s, boolean exactFirst, boolean filtersOnly) {
        ItemStack[] contents = input.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (SortTarget target : candidates(state.sortTargets, stack.getType(), world, s, exactFirst, filtersOnly)) {
                Block targetBlock = target.anchor().block(world);
                Inventory output = target.inventory(world);
                if (output == null || !Containers.isStorage(targetBlock.getType())) {
                    state.sortDirty = true;
                    continue;
                }
                ItemStack moving = stack.clone();
                moving.setAmount(Math.min(perMove, stack.getAmount()));
                int moved = moving.getAmount()
                    - Containers.total(Containers.add(targetBlock.getType(), output, List.of(moving)));
                if (moved <= 0) {
                    continue;
                }
                ItemStack remaining = stack.clone();
                remaining.setAmount(stack.getAmount() - moved);
                input.setItem(slot, remaining.getAmount() > 0 ? remaining : null);
                worker.addMoved(moved);
                state.status = Status.WORKING;
                visuals.transfer(worker.golem(), post, target.center(), moving);
                Log.debug("sorter", () -> "golem=" + Log.id(worker.golem()) + " перенёс " + moved + "×"
                    + stack.getType().name() + " → " + target.anchor()
                    + (target.hasFilters() ? " (фильтр)" : " (где уже лежит)")
                    + (filtersOnly ? " [аккуратный: сначала фильтры]" : ""));
                progression.sorted(worker, moved);
                return true;
            }
        }
        return false;
    }

    private static List<SortTarget> candidates(List<SortTarget> targets, Material type, World world, Settings s,
                                               boolean exactFirst, boolean filtersOnly) {
        List<SortTarget> filtered = new ArrayList<>();
        for (SortTarget target : targets) {
            if (target.hasFilters() && target.accepts(type, s.sorterGroups)) {
                filtered.add(target);
            }
        }
        if (exactFirst && filtered.size() > 1) {
            // Сортировка устойчивая: внутри групп сохраняется порядок «ближние первыми».
            filtered.sort(Comparator.comparingInt(target -> target.filters().contains(type) ? 0 : 1));
        }
        // Если для предмета есть фильтр — кладём только туда (даже если там полно).
        if (!filtered.isEmpty() || !s.sorterMatchExisting || filtersOnly) {
            return filtered;
        }
        List<SortTarget> existing = new ArrayList<>();
        for (SortTarget target : targets) {
            if (target.hasFilters()) {
                continue;
            }
            Inventory inventory = target.inventory(world);
            if (inventory != null && inventory.contains(type)) {
                existing.add(target);
            }
        }
        return existing;
    }
}
