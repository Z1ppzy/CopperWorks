package dev.copperworks.job;

import dev.copperworks.util.Containers;
import dev.copperworks.worker.BlockKey;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/** Контейнер рядом с сортировщиком и его фильтры (предметы в рамках на нём). */
public record SortTarget(BlockKey anchor, Location center, Set<Material> filters, double distanceSquared) {

    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    public boolean accepts(Material type, List<Tag<Material>> groups) {
        if (filters.contains(type)) {
            return true;
        }
        for (Material filter : filters) {
            for (Tag<Material> group : groups) {
                if (group.isTagged(filter) && group.isTagged(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    public @Nullable Inventory inventory(World world) {
        if (!anchor.isLoaded(world)) {
            return null;
        }
        return Containers.inventory(anchor.block(world));
    }
}
