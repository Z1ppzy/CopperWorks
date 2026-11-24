package dev.copperworks.worker;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

/** Координаты блока без привязки к миру. */
public record BlockKey(int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getX(), block.getY(), block.getZ());
    }

    public static @Nullable BlockKey fromArray(int @Nullable [] array) {
        if (array == null || array.length != 3) {
            return null;
        }
        return new BlockKey(array[0], array[1], array[2]);
    }

    public int[] toArray() {
        return new int[]{x, y, z};
    }

    public Block block(World world) {
        return world.getBlockAt(x, y, z);
    }

    public Location center(World world) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    public boolean isLoaded(World world) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    public double distanceSquared(BlockKey other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
