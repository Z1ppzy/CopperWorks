package dev.copperworks.job;

import dev.copperworks.config.Settings;
import dev.copperworks.util.Containers;
import dev.copperworks.worker.BlockKey;
import dev.copperworks.worker.Worker;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.jspecify.annotations.Nullable;

/** Рабочее место голема — ближайший медный сундук. Голема можно перевести на поводке к другому сундуку. */
public final class Posts {
    private Posts() {
    }

    public static @Nullable Block resolve(Worker worker, Settings settings) {
        CopperGolem golem = worker.golem();
        Location at = golem.getLocation();
        int radius = settings.postSearchRadius;
        BlockKey stored = worker.post();
        if (stored != null && stored.isLoaded(golem.getWorld())) {
            Block block = stored.block(golem.getWorld());
            double max = radius + 1.5;
            if (Containers.isCopperChest(block.getType())
                && block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(at) <= max * max) {
                return block;
            }
        }
        Block found = Containers.findCopperChest(at, radius);
        if (found != null) {
            worker.post(BlockKey.of(found));
        }
        return found;
    }
}
