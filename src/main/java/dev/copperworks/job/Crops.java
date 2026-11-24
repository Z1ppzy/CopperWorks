package dev.copperworks.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import dev.copperworks.worker.BlockKey;

/** Что умеет собирать фермер и как пересаживает. */
public final class Crops {
    /** Растение → чем пересаживать (одна штука вычитается из дропа). */
    private static final Map<Material, Material> REPLANT = new EnumMap<>(Material.class);
    private static final Set<Material> STEM_FRUITS = Set.of(Material.PUMPKIN, Material.MELON);
    private static final Set<Material> STEMS = Set.of(Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM);
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    static {
        REPLANT.put(Material.WHEAT, Material.WHEAT_SEEDS);
        REPLANT.put(Material.CARROTS, Material.CARROT);
        REPLANT.put(Material.POTATOES, Material.POTATO);
        REPLANT.put(Material.BEETROOTS, Material.BEETROOT_SEEDS);
        REPLANT.put(Material.NETHER_WART, Material.NETHER_WART);
        REPLANT.put(Material.COCOA, Material.COCOA_BEANS);
    }

    private Crops() {
    }

    public static boolean isInteresting(Material type) {
        return REPLANT.containsKey(type) || STEM_FRUITS.contains(type) || type == Material.SWEET_BERRY_BUSH;
    }

    /** Список растений вокруг (только загруженные чанки), ближние — первыми. */
    public static List<BlockKey> scan(Block center, int radius, int vertical) {
        World world = center.getWorld();
        List<BlockKey> found = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = center.getX() + x;
                int bz = center.getZ() + z;
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                for (int y = -vertical; y <= vertical; y++) {
                    Block block = world.getBlockAt(bx, center.getY() + y, bz);
                    if (isInteresting(block.getType())) {
                        found.add(BlockKey.of(block));
                    }
                }
            }
        }
        BlockKey origin = BlockKey.of(center);
        found.sort(Comparator.comparingDouble(origin::distanceSquared));
        return found;
    }

    /**
     * Собирает растение, если оно созрело. Вызывает EntityChangeBlockEvent, чтобы приваты могли запретить.
     *
     * @return дроп или null, если собирать нечего
     */
    public static @Nullable List<ItemStack> harvest(Entity harvester, Block block) {
        Material type = block.getType();
        BlockData data = block.getBlockData();

        if (REPLANT.containsKey(type)) {
            if (!(data instanceof Ageable ageable) || ageable.getAge() < ageable.getMaximumAge()) {
                return null;
            }
            Ageable replanted = (Ageable) ageable.clone();
            replanted.setAge(0);
            if (!allowed(harvester, block, replanted)) {
                return null;
            }
            List<ItemStack> drops = new ArrayList<>(block.getDrops());
            removeOne(drops, REPLANT.get(type));
            block.setBlockData(replanted);
            return drops;
        }

        if (type == Material.SWEET_BERRY_BUSH) {
            if (!(data instanceof Ageable ageable) || ageable.getAge() < 2) {
                return null;
            }
            int berries = ageable.getAge() >= ageable.getMaximumAge()
                ? 2 + ThreadLocalRandom.current().nextInt(2)
                : 1 + ThreadLocalRandom.current().nextInt(2);
            Ageable picked = (Ageable) ageable.clone();
            picked.setAge(1);
            if (!allowed(harvester, block, picked)) {
                return null;
            }
            block.setBlockData(picked);
            return new ArrayList<>(List.of(new ItemStack(Material.SWEET_BERRIES, berries)));
        }

        if (STEM_FRUITS.contains(type)) {
            // Только тыквы/арбузы, выросшие от стебля: декоративные не трогаем.
            if (!grownFromStem(block)) {
                return null;
            }
            BlockData air = Material.AIR.createBlockData();
            if (!allowed(harvester, block, air)) {
                return null;
            }
            List<ItemStack> drops = new ArrayList<>(block.getDrops());
            block.setBlockData(air);
            return drops;
        }
        return null;
    }

    /**
     * Бонусный предмет черты «Жадный»: одна штука основного урожая (пшеница, а не семена).
     *
     * @param crop тип блока до сбора
     * @return копия в одну штуку или null, если дропа нет
     */
    public static @Nullable ItemStack bonusDrop(Material crop, List<ItemStack> drops) {
        if (drops.isEmpty()) {
            return null;
        }
        Material seed = REPLANT.get(crop);
        for (ItemStack drop : drops) {
            if (drop.getType() != seed) {
                return drop.asOne();
            }
        }
        return drops.getFirst().asOne();
    }

    private static boolean grownFromStem(Block fruit) {
        for (BlockFace side : SIDES) {
            Block neighbour = fruit.getRelative(side);
            if (STEMS.contains(neighbour.getType())
                && neighbour.getBlockData() instanceof Directional stem
                && stem.getFacing() == side.getOppositeFace()) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowed(Entity harvester, Block block, BlockData to) {
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(harvester, block, to);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private static void removeOne(List<ItemStack> drops, Material seed) {
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item.getType() == seed) {
                if (item.getAmount() <= 1) {
                    it.remove();
                } else {
                    item.setAmount(item.getAmount() - 1);
                }
                return;
            }
        }
    }
}
