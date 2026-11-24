package dev.copperworks.job;

import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.util.Visuals;
import dev.copperworks.worker.BlockKey;
import dev.copperworks.worker.RuntimeState;
import dev.copperworks.worker.Status;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Фермер: собирает созревшее вокруг себя, пересаживает и складывает урожай в медный сундук. */
public final class FarmerJob implements JobHandler {
    private static final int RESCAN_TICKS = 200;

    private final Supplier<Settings> settings;
    private final Visuals visuals;

    public FarmerJob(Supplier<Settings> settings, Visuals visuals) {
        this.settings = settings;
        this.visuals = visuals;
    }

    @Override
    public int work(Worker worker, RuntimeState state, long tick) {
        Settings s = settings.get();
        CopperGolem golem = worker.golem();
        Block post = Posts.resolve(worker, s);
        Inventory storage = post == null ? null : Containers.inventory(post);
        if (post == null || storage == null) {
            state.status = Status.NO_POST;
            return 40;
        }
        if (storage.firstEmpty() == -1) {
            state.status = Status.STORAGE_FULL;
            return 60;
        }

        int radius = s.farmerRadius[worker.level(Upgrade.RANGE)];
        Block center = golem.getLocation().getBlock();
        BlockKey centerKey = BlockKey.of(center);
        if (state.cropsDirty || tick - state.cropsScannedTick > RESCAN_TICKS
            || state.cropsRadius != radius || !centerKey.equals(state.cropsCenter)) {
            state.crops = Crops.scan(center, radius, s.farmerVerticalRange);
            state.cropsDirty = false;
            state.cropsScannedTick = tick;
            state.cropsRadius = radius;
            state.cropsCenter = centerKey;
        }

        int interval = s.scaledInterval(s.farmerBaseInterval, s.speedFactor(worker));
        int limit = s.farmerCropsPerAction[worker.level(Upgrade.CAPACITY)];
        World world = golem.getWorld();
        int harvested = 0;
        Block shownBlock = null;
        ItemStack shownItem = null;
        for (BlockKey key : state.crops) {
            if (harvested >= limit) {
                break;
            }
            if (!key.isLoaded(world)) {
                continue;
            }
            Block block = key.block(world);
            List<ItemStack> drops = Crops.harvest(golem, block);
            if (drops == null) {
                continue;
            }
            harvested++;
            worker.addMoved(Containers.total(drops));
            for (ItemStack rest : Containers.add(storage, drops)) {
                world.dropItemNaturally(post.getLocation().add(0.5, 1.0, 0.5), rest);
            }
            if (shownItem == null && !drops.isEmpty()) {
                shownItem = drops.getFirst();
                shownBlock = block;
            }
        }

        if (harvested == 0) {
            state.status = Status.NOTHING_RIPE;
            return Math.max(interval, 40);
        }
        state.status = Status.WORKING;
        if (shownItem != null) {
            visuals.harvest(golem, shownBlock, post, shownItem);
        }
        return interval;
    }
}
