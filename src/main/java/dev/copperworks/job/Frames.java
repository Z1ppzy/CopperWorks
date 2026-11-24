package dev.copperworks.job;

import dev.copperworks.util.Containers;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Рамки с предметами, повешенные на контейнер: фильтр курьера на его медном сундуке. */
public final class Frames {
    private Frames() {
    }

    /**
     * Предметы в рамках, прикреплённых к контейнеру. У двойного сундука учитываются обе половины
     * (половины узнаём по общему инвентарю, как в SortScanner).
     *
     * @return пустое множество, если рамок с предметами нет
     */
    public static Set<Material> on(Block container) {
        Inventory inventory = Containers.inventory(container);
        Location shared = inventory == null ? null : inventory.getLocation();
        Location center = container.getLocation().add(0.5, 0.5, 0.5);
        Set<Material> items = new LinkedHashSet<>();
        for (ItemFrame frame : container.getWorld().getNearbyEntitiesByType(ItemFrame.class, center, 2.5, 1.5, 2.5)) {
            ItemStack item = frame.getItem();
            if (item.isEmpty()) {
                continue;
            }
            Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
            if (attached.equals(container) || sameInventory(attached, shared)) {
                items.add(item.getType());
            }
        }
        return items;
    }

    private static boolean sameInventory(Block block, @Nullable Location shared) {
        if (shared == null || !Containers.isCopperChest(block.getType())) {
            return false;
        }
        Inventory other = Containers.inventory(block);
        return other != null && shared.equals(other.getLocation());
    }
}
