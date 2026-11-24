package dev.copperworks.job;

import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.BlockKey;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import java.util.ArrayList;
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
 */
public final class SorterJob implements JobHandler {
    private static final int RESCAN_TICKS = 200;

    private final Supplier<Settings> settings;
    private final Visuals visuals;

    public SorterJob(Supplier<Settings> settings, Visuals visuals) {
        this.settings = settings;
        this.visuals = visuals;
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

        int radius = s.sorterRadius[worker.level(Upgrade.RANGE)];
        BlockKey postKey = BlockKey.of(post);
        if (state.sortDirty || tick - state.sortScannedTick > RESCAN_TICKS
            || state.sortRadius != radius || !postKey.equals(state.sortPost)) {
            state.sortTargets = SortScanner.scan(post, radius);
            state.sortDirty = false;
            state.sortScannedTick = tick;
            state.sortRadius = radius;
            state.sortPost = postKey;
        }

        int interval = s.scaledInterval(s.sorterBaseInterval, s.speedFactor(worker));
        if (input.isEmpty()) {
            state.status = Status.IDLE;
            return interval;
        }

        World world = post.getWorld();
        int perMove = s.sorterItemsPerMove[worker.level(Upgrade.CAPACITY)];
        ItemStack[] contents = input.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (SortTarget target : candidates(state.sortTargets, stack.getType(), world, s)) {
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
                return interval;
            }
        }
        state.status = Status.NOTHING_TO_SORT;
        return Math.max(interval, 60);
    }

    private static List<SortTarget> candidates(List<SortTarget> targets, Material type, World world, Settings s) {
        List<SortTarget> filtered = new ArrayList<>();
        for (SortTarget target : targets) {
            if (target.hasFilters() && target.accepts(type, s.sorterGroups)) {
                filtered.add(target);
            }
        }
        // Если для предмета есть фильтр — кладём только туда (даже если там полно).
        if (!filtered.isEmpty() || !s.sorterMatchExisting) {
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
