package dev.copperworks.config;

import dev.copperworks.worker.Job;
import dev.copperworks.worker.Upgrade;
import dev.copperworks.worker.Worker;
import io.papermc.paper.world.WeatheringCopperState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Разобранный config.yml. Пересоздаётся при /cw reload. */
public final class Settings {
    private static final int LEVELS = Upgrade.MAX_LEVEL + 1;

    public final int tickInterval;
    public final int maxWorkersPerPlayer;
    public final int postSearchRadius;
    public final boolean protectWorkers;
    public final boolean preventStatue;
    public final boolean visualEffects;
    public final Material upgradeItem;
    public final int[] upgradeCosts;
    public final double speedPerLevel;
    private final Map<WeatheringCopperState, Double> oxidationSpeed = new EnumMap<>(WeatheringCopperState.class);

    public final boolean sorterEnabled;
    public final int sorterBaseInterval;
    public final int[] sorterItemsPerMove;
    public final int[] sorterRadius;
    public final boolean sorterMatchExisting;
    public final List<Tag<Material>> sorterGroups;

    public final boolean courierEnabled;
    public final double courierBlocksPerSecond;
    public final int courierMinLegSeconds;
    public final int[] courierStacks;
    public final int[] courierMaxDistance;
    public final boolean courierNotify;

    public final boolean farmerEnabled;
    public final int farmerBaseInterval;
    public final int[] farmerCropsPerAction;
    public final int[] farmerRadius;
    public final int farmerVerticalRange;

    public Settings(FileConfiguration config, Logger log) {
        ConfigurationSection s = section(config, "settings");
        tickInterval = Math.max(1, s.getInt("tick-interval", 5));
        maxWorkersPerPlayer = s.getInt("max-workers-per-player", 5);
        postSearchRadius = Math.clamp(s.getInt("post-search-radius", 2), 1, 6);
        protectWorkers = s.getBoolean("protect-workers", true);
        preventStatue = s.getBoolean("prevent-statue", true);
        visualEffects = s.getBoolean("visual-effects", true);
        Material item = Material.matchMaterial(s.getString("upgrade-item", "COPPER_INGOT"));
        if (item == null || !item.isItem()) {
            log.warning("settings.upgrade-item: неизвестный предмет, использую COPPER_INGOT");
            item = Material.COPPER_INGOT;
        }
        upgradeItem = item;
        upgradeCosts = ints(s, "upgrade-costs", new int[]{16, 48, 128}, Upgrade.MAX_LEVEL);
        speedPerLevel = Math.max(0, s.getDouble("speed-per-level", 0.5));
        ConfigurationSection ox = section(s, "oxidation-speed");
        oxidationSpeed.put(WeatheringCopperState.UNAFFECTED, positive(ox.getDouble("unaffected", 1.0)));
        oxidationSpeed.put(WeatheringCopperState.EXPOSED, positive(ox.getDouble("exposed", 0.85)));
        oxidationSpeed.put(WeatheringCopperState.WEATHERED, positive(ox.getDouble("weathered", 0.65)));
        oxidationSpeed.put(WeatheringCopperState.OXIDIZED, positive(ox.getDouble("oxidized", 0.4)));

        ConfigurationSection sorter = section(config, "jobs.sorter");
        sorterEnabled = sorter.getBoolean("enabled", true);
        sorterBaseInterval = Math.max(5, sorter.getInt("base-interval-ticks", 40));
        sorterItemsPerMove = ints(sorter, "items-per-move", new int[]{8, 16, 32, 64}, LEVELS);
        sorterRadius = clampAll(ints(sorter, "radius", new int[]{8, 12, 16, 24}, LEVELS), 1, 48);
        sorterMatchExisting = sorter.getBoolean("match-existing", true);
        List<Tag<Material>> groups = new ArrayList<>();
        for (String raw : sorter.getStringList("group-filters")) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
            if (tag == null) {
                log.warning("jobs.sorter.group-filters: неизвестный тег предметов " + raw);
            } else {
                groups.add(tag);
            }
        }
        sorterGroups = List.copyOf(groups);

        ConfigurationSection courier = section(config, "jobs.courier");
        courierEnabled = courier.getBoolean("enabled", true);
        courierBlocksPerSecond = Math.max(0.1, courier.getDouble("blocks-per-second", 4.0));
        courierMinLegSeconds = Math.max(1, courier.getInt("min-leg-seconds", 5));
        courierStacks = clampAll(ints(courier, "stacks-per-trip", new int[]{1, 3, 6, 9}, LEVELS), 1, 27);
        courierMaxDistance = ints(courier, "max-distance", new int[]{256, 1024, 4096, -1}, LEVELS);
        courierNotify = courier.getBoolean("notify-owner", true);

        ConfigurationSection farmer = section(config, "jobs.farmer");
        farmerEnabled = farmer.getBoolean("enabled", true);
        farmerBaseInterval = Math.max(5, farmer.getInt("base-interval-ticks", 30));
        farmerCropsPerAction = clampAll(ints(farmer, "crops-per-action", new int[]{1, 2, 3, 4}, LEVELS), 1, 64);
        farmerRadius = clampAll(ints(farmer, "radius", new int[]{4, 6, 8, 10}, LEVELS), 1, 16);
        farmerVerticalRange = Math.clamp(farmer.getInt("vertical-range", 2), 0, 8);
    }

    public boolean enabled(Job job) {
        return switch (job) {
            case SORTER -> sorterEnabled;
            case COURIER -> courierEnabled;
            case FARMER -> farmerEnabled;
        };
    }

    public double oxidationFactor(WeatheringCopperState state) {
        return oxidationSpeed.getOrDefault(state, 1.0);
    }

    /** Итоговый множитель скорости: уровень «Скорости» × окисление. */
    public double speedFactor(Worker worker) {
        return (1.0 + speedPerLevel * worker.level(Upgrade.SPEED))
            * oxidationFactor(worker.golem().getWeatheringState());
    }

    public int scaledInterval(int baseTicks, double speedFactor) {
        return Math.max(10, (int) Math.round(baseTicks / Math.max(0.05, speedFactor)));
    }

    /** Стоимость перехода на уровень (level + 1). */
    public int upgradeCost(int currentLevel) {
        return upgradeCosts[Math.clamp(currentLevel, 0, upgradeCosts.length - 1)];
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        return section != null ? section : parent.createSection(path);
    }

    private static double positive(double value) {
        return value <= 0 ? 0.05 : value;
    }

    private static int[] ints(ConfigurationSection section, String path, int[] def, int size) {
        List<Integer> list = section.getIntegerList(path);
        int[] result = new int[size];
        for (int i = 0; i < size; i++) {
            if (i < list.size()) {
                result[i] = list.get(i);
            } else if (!list.isEmpty()) {
                result[i] = list.getLast();
            } else {
                result[i] = def[Math.min(i, def.length - 1)];
            }
        }
        return result;
    }

    private static int[] clampAll(int[] values, int min, int max) {
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.clamp(values[i], min, max);
        }
        return values;
    }
}
