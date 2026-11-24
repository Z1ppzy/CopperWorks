package dev.copperworks.config;

import dev.copperworks.util.Format;
import dev.copperworks.util.Log;
import dev.copperworks.worker.Job;
import dev.copperworks.worker.Trait;
import dev.copperworks.worker.Upgrade;
import io.papermc.paper.world.WeatheringCopperState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Predicate;
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
    public final boolean debug;
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
    public final boolean courierFrameFilter;

    public final boolean farmerEnabled;
    public final int farmerBaseInterval;
    public final int[] farmerCropsPerAction;
    public final int[] farmerRadius;
    public final int farmerVerticalRange;

    public final boolean xpEnabled;
    public final int maxLevel;
    private final double xpBase;
    private final double xpExponent;
    public final double xpSorterPerItem;
    public final double xpCourierPerItem;
    public final double xpCourierTripBase;
    public final double xpCourierTripPerBlock;
    public final double xpFarmerPerCrop;
    /** Сколько опыта голем может набрать за час работы; 0 — без лимита. */
    public final double xpMaxPerHour;
    /** Уровни, на которых голем получает черту: по возрастанию, без повторов, в пределах 2..max-level. */
    public final List<Integer> traitLevels;

    public final boolean traitsEnabled;
    public final int maxTraits;
    public final boolean rerollEnabled;
    public final int rerollCost;
    private final Map<Trait, Boolean> enabledTraits = new EnumMap<>(Trait.class);
    private final Map<Trait, Integer> traitWeights = new EnumMap<>(Trait.class);
    public final double swiftTripTime;
    public final int porterCourierStacks;
    public final double porterSorterItems;
    public final double tidyRescan;
    public final double greedyChance;
    public final double tirelessPause;
    public final double stainlessPenalty;
    public final int farsightedRadius;
    public final double lazySpeed;
    public final int lazyCapacityLevels;

    public Settings(FileConfiguration config) {
        this(config, Material::isItem);
    }

    /**
     * @param isItem проверка «этот Material — предмет». Material#isItem ходит в реестр сервера,
     *               поэтому юнит-тесты без сервера подставляют сюда свою проверку.
     */
    public Settings(FileConfiguration config, Predicate<Material> isItem) {
        ConfigurationSection s = section(config, "settings");
        tickInterval = Math.max(1, s.getInt("tick-interval", 5));
        maxWorkersPerPlayer = s.getInt("max-workers-per-player", 5);
        postSearchRadius = Math.clamp(s.getInt("post-search-radius", 2), 1, 6);
        protectWorkers = s.getBoolean("protect-workers", true);
        preventStatue = s.getBoolean("prevent-statue", true);
        visualEffects = s.getBoolean("visual-effects", true);
        debug = s.getBoolean("debug", false);
        String rawItem = s.getString("upgrade-item", "COPPER_INGOT");
        Material item = Material.matchMaterial(rawItem);
        if (item == null || !isItem.test(item)) {
            Log.warn("config", "settings.upgrade-item: неизвестный предмет «" + rawItem + "», использую COPPER_INGOT");
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
                Log.warn("config", "jobs.sorter.group-filters: неизвестный тег предметов «" + raw + "», пропускаю");
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
        courierFrameFilter = courier.getBoolean("frame-filter", true);

        ConfigurationSection farmer = section(config, "jobs.farmer");
        farmerEnabled = farmer.getBoolean("enabled", true);
        farmerBaseInterval = Math.max(5, farmer.getInt("base-interval-ticks", 30));
        farmerCropsPerAction = clampAll(ints(farmer, "crops-per-action", new int[]{1, 2, 3, 4}, LEVELS), 1, 64);
        farmerRadius = clampAll(ints(farmer, "radius", new int[]{4, 6, 8, 10}, LEVELS), 1, 16);
        farmerVerticalRange = Math.clamp(farmer.getInt("vertical-range", 2), 0, 8);

        ConfigurationSection xp = section(config, "experience");
        xpEnabled = xp.getBoolean("enabled", true);
        maxLevel = Math.clamp(xp.getInt("max-level", 10), 1, 100);
        ConfigurationSection curve = section(xp, "curve");
        xpBase = Math.max(1.0, curve.getDouble("base", 100.0));
        xpExponent = Math.clamp(curve.getDouble("exponent", 1.5), 0.0, 5.0);
        ConfigurationSection gain = section(xp, "gain");
        xpSorterPerItem = nonNegative(gain.getDouble("sorter-per-item", 1.0));
        xpCourierPerItem = nonNegative(gain.getDouble("courier-per-item", 0.5));
        xpCourierTripBase = nonNegative(gain.getDouble("courier-trip-base", 5.0));
        xpCourierTripPerBlock = nonNegative(gain.getDouble("courier-trip-per-block", 0.05));
        xpFarmerPerCrop = nonNegative(gain.getDouble("farmer-per-crop", 4.0));
        xpMaxPerHour = nonNegative(xp.getDouble("max-per-hour", 2000.0));
        List<Integer> rawLevels = xp.isList("trait-levels") ? xp.getIntegerList("trait-levels") : List.of(3, 6, 9);
        List<Integer> levels = new ArrayList<>();
        for (int level : rawLevels) {
            if (level < 2 || level > maxLevel) {
                Log.warn("config", "experience.trait-levels: уровень " + level + " вне диапазона 2.." + maxLevel + ", пропускаю");
            } else if (!levels.contains(level)) {
                levels.add(level);
            }
        }
        levels.sort(null);
        traitLevels = List.copyOf(levels);

        ConfigurationSection traits = section(config, "traits");
        traitsEnabled = traits.getBoolean("enabled", true);
        maxTraits = Math.clamp(traits.getInt("max-per-golem", 3), 0, Trait.values().length);
        ConfigurationSection reroll = section(traits, "reroll");
        rerollEnabled = reroll.getBoolean("enabled", true);
        rerollCost = Math.max(0, reroll.getInt("cost", 32));
        for (Trait trait : Trait.values()) {
            ConfigurationSection t = section(traits, trait.id());
            enabledTraits.put(trait, t.getBoolean("enabled", true));
            int weight = t.getInt("weight", 10);
            if (weight < 0) {
                Log.warn("config", "traits." + trait.id() + ".weight: " + weight + " меньше нуля, считаю 0");
            }
            traitWeights.put(trait, Math.max(0, weight));
        }
        swiftTripTime = Math.clamp(section(traits, "swift").getDouble("trip-time", 0.75), 0.1, 1.0);
        ConfigurationSection porter = section(traits, "porter");
        porterCourierStacks = Math.clamp(porter.getInt("courier-extra-stacks", 1), 0, 27);
        porterSorterItems = Math.clamp(porter.getDouble("sorter-items-multiplier", 1.5), 1.0, 10.0);
        tidyRescan = Math.clamp(section(traits, "tidy").getDouble("rescan-multiplier", 0.5), 0.1, 1.0);
        greedyChance = Math.clamp(section(traits, "greedy").getDouble("extra-drop-chance", 0.2), 0.0, 1.0);
        tirelessPause = Math.clamp(section(traits, "tireless").getDouble("pause-multiplier", 0.85), 0.1, 1.0);
        stainlessPenalty = Math.clamp(section(traits, "stainless").getDouble("oxidation-penalty-multiplier", 0.5), 0.0, 1.0);
        farsightedRadius = Math.clamp(section(traits, "farsighted").getInt("extra-radius", 2), 0, 8);
        ConfigurationSection lazy = section(traits, "lazy");
        lazySpeed = Math.clamp(lazy.getDouble("speed-multiplier", 0.9), 0.1, 1.0);
        lazyCapacityLevels = Math.clamp(lazy.getInt("capacity-levels", 1), 0, 3);
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

    /**
     * Пауза между действиями: база / скорость × множитель паузы (черта «Неутомимый»), не меньше 10 тиков.
     * Итоговую скорость с учётом черт считает {@link dev.copperworks.worker.Traits#speedFactor}.
     */
    public int scaledInterval(int baseTicks, double speedFactor, double pauseMultiplier) {
        return Math.max(10, (int) Math.round(baseTicks / Math.max(0.05, speedFactor) * pauseMultiplier));
    }

    /** Сколько опыта нужно, чтобы перейти с уровня level на level + 1: base × level^exponent. */
    public long xpToNext(int level) {
        return Math.max(1L, Math.round(xpBase * Math.pow(Math.max(1, level), xpExponent)));
    }

    /** Черта может выпасть (включена и вес больше нуля). */
    public boolean traitObtainable(Trait trait) {
        return traitEnabled(trait) && traitWeight(trait) > 0;
    }

    /** Черта включена: выключенная не выпадает и не действует, даже если уже есть у голема. */
    public boolean traitEnabled(Trait trait) {
        return traitsEnabled && enabledTraits.getOrDefault(trait, true);
    }

    public int traitWeight(Trait trait) {
        return traitWeights.getOrDefault(trait, 0);
    }

    /** Ключевые настройки одной строкой — для лога старта и /cw reload. */
    public String summary() {
        StringJoiner pool = new StringJoiner(", ");
        for (Trait trait : Trait.values()) {
            if (traitObtainable(trait)) {
                pool.add(trait.id() + "=" + traitWeight(trait));
            }
        }
        return "tick-interval=" + tickInterval
            + " max-workers=" + maxWorkersPerPlayer
            + " visual-effects=" + visualEffects
            + " upgrade-item=" + upgradeItem.name().toLowerCase(Locale.ROOT)
            + " jobs=[sorter=" + sorterEnabled + ", courier=" + courierEnabled + ", farmer=" + farmerEnabled + "]"
            + " | experience: enabled=" + xpEnabled
            + " max-level=" + maxLevel
            + " curve=" + xpBase + "*L^" + xpExponent
            + " max-per-hour=" + (xpMaxPerHour > 0 ? Format.decimal(xpMaxPerHour) : "off")
            + " (до max-level нужно " + totalXp() + ")"
            + " trait-levels=" + traitLevels
            + " | traits: enabled=" + traitsEnabled
            + " max-per-golem=" + maxTraits
            + " reroll=" + (rerollEnabled ? rerollCost : "off")
            + " pool=[" + pool + "]";
    }

    private long totalXp() {
        long total = 0;
        for (int level = 1; level < maxLevel; level++) {
            total += xpToNext(level);
        }
        return total;
    }

    /** Стоимость перехода на уровень (level + 1). */
    public int upgradeCost(int currentLevel) {
        return upgradeCosts[Math.clamp(currentLevel, 0, upgradeCosts.length - 1)];
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        return section != null ? section : parent.createSection(path);
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
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
