package dev.copperworks.job;

import dev.copperworks.util.Containers;
import dev.copperworks.worker.BlockKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Ищет контейнеры вокруг медного сундука. Читает только загруженные чанки. */
public final class SortScanner {
    private SortScanner() {
    }

    public static List<SortTarget> scan(Block post, int radius) {
        World world = post.getWorld();
        int px = post.getX();
        int py = post.getY();
        int pz = post.getZ();
        int vertical = Math.max(3, radius / 2);
        Location postCenter = post.getLocation().add(0.5, 0.5, 0.5);
        BlockKey postKey = BlockKey.of(post);

        // 1. Рамки с предметами → фильтры для блока, к которому они прикреплены.
        Map<BlockKey, List<Material>> frameFilters = new HashMap<>();
        for (ItemFrame frame : world.getNearbyEntitiesByType(ItemFrame.class, postCenter, radius + 1, vertical + 1, radius + 1)) {
            ItemStack item = frame.getItem();
            if (item.isEmpty()) {
                continue;
            }
            Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
            frameFilters.computeIfAbsent(BlockKey.of(attached), k -> new ArrayList<>()).add(item.getType());
        }

        // 2. Контейнеры. Половинки двойного сундука склеиваем по общему инвентарю.
        Map<String, Builder> byInventory = new LinkedHashMap<>();
        for (int cx = (px - radius) >> 4; cx <= (px + radius) >> 4; cx++) {
            for (int cz = (pz - radius) >> 4; cz <= (pz + radius) >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(cx, cz).getTileEntities(
                    b -> Math.abs(b.getX() - px) <= radius && Math.abs(b.getY() - py) <= vertical && Math.abs(b.getZ() - pz) <= radius,
                    false)) {
                    if (!(state instanceof Container container)) {
                        continue;
                    }
                    Material type = state.getType();
                    if (!Containers.isStorage(type) || Containers.isCopperChest(type)) {
                        continue;
                    }
                    BlockKey key = BlockKey.of(state.getBlock());
                    if (key.equals(postKey)) {
                        continue;
                    }
                    Inventory inventory = container.getInventory();
                    Location invLocation = inventory.getLocation();
                    String id = invLocation == null
                        ? key.toString()
                        : invLocation.getX() + ";" + invLocation.getY() + ";" + invLocation.getZ();
                    Builder builder = byInventory.computeIfAbsent(id, k -> new Builder(key,
                        (invLocation != null ? invLocation.clone() : state.getLocation()).add(0.5, 0.75, 0.5)));
                    List<Material> filters = frameFilters.get(key);
                    if (filters != null) {
                        builder.filters.addAll(filters);
                    }
                }
            }
        }

        List<SortTarget> targets = new ArrayList<>(byInventory.size());
        for (Builder builder : byInventory.values()) {
            targets.add(new SortTarget(builder.anchor, builder.center, Set.copyOf(builder.filters),
                builder.center.distanceSquared(postCenter)));
        }
        targets.sort(Comparator.comparingDouble(SortTarget::distanceSquared));
        return List.copyOf(targets);
    }

    private static final class Builder {
        final BlockKey anchor;
        final Location center;
        final Set<Material> filters = new LinkedHashSet<>();

        Builder(BlockKey anchor, Location center) {
            this.anchor = anchor;
            this.center = center;
        }
    }
}
