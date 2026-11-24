package dev.copperworks.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

public final class Containers {
    private Containers() {
    }

    /** Любой медный сундук: обычный, окисленный, вощёный. */
    public static boolean isCopperChest(Material type) {
        return type.name().endsWith("COPPER_CHEST");
    }

    /** Куда сортировщик и курьер могут класть вещи. Медные сундуки — это входы, их не трогаем. */
    public static boolean isStorage(Material type) {
        return type == Material.CHEST
            || type == Material.TRAPPED_CHEST
            || type == Material.BARREL
            || Tag.SHULKER_BOXES.isTagged(type)
            || isCopperChest(type);
    }

    /** Живой инвентарь контейнера (для двойного сундука — общий). */
    public static @Nullable Inventory inventory(Block block) {
        BlockState state = block.getState(false);
        if (state instanceof Container container) {
            return container.getInventory();
        }
        return null;
    }

    public static @Nullable Block findCopperChest(Location around, int radius) {
        Block origin = around.getBlock();
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = origin.getRelative(x, y, z);
                    if (!isCopperChest(block.getType())) {
                        continue;
                    }
                    double distance = block.getLocation().add(0.5, 0.5, 0.5).distanceSquared(around);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    /** Кладёт копии предметов в инвентарь, возвращает то, что не влезло. Исходные стаки не меняются. */
    public static List<ItemStack> add(Inventory inventory, Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return new ArrayList<>();
        }
        ItemStack[] copies = items.stream().map(ItemStack::clone).toArray(ItemStack[]::new);
        Map<Integer, ItemStack> rest = inventory.addItem(copies);
        return new ArrayList<>(rest.values());
    }

    /**
     * То же, но с правилами контейнера: Inventory#addItem не проверяет слоты, поэтому
     * шалкер в шалкер (как в ванили) не кладём — такие предметы возвращаются как «не влезло».
     */
    public static List<ItemStack> add(Material containerType, Inventory inventory, Collection<ItemStack> items) {
        if (!Tag.SHULKER_BOXES.isTagged(containerType)) {
            return add(inventory, items);
        }
        List<ItemStack> allowed = new ArrayList<>();
        List<ItemStack> rejected = new ArrayList<>();
        for (ItemStack item : items) {
            (Tag.SHULKER_BOXES.isTagged(item.getType()) ? rejected : allowed).add(item.clone());
        }
        List<ItemStack> rest = add(inventory, allowed);
        rest.addAll(rejected);
        return rest;
    }

    public static int total(Collection<ItemStack> items) {
        int sum = 0;
        for (ItemStack item : items) {
            sum += item.getAmount();
        }
        return sum;
    }

    /** Центр контейнера для анимаций (у двойного сундука — середина). */
    public static Location center(Block block) {
        Inventory inventory = inventory(block);
        Location location = inventory != null ? inventory.getLocation() : null;
        if (location == null) {
            location = block.getLocation();
        }
        return location.clone().add(0.5, 0.75, 0.5);
    }
}
