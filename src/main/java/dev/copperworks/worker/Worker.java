package dev.copperworks.worker;

import dev.copperworks.Keys;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Обёртка над PersistentDataContainer голема.
 * Плагину не нужна база данных: профессия, улучшения и груз курьера сохраняются вместе с сущностью.
 */
public final class Worker {
    private final CopperGolem golem;
    private final PersistentDataContainer pdc;

    public Worker(CopperGolem golem) {
        this.golem = golem;
        this.pdc = golem.getPersistentDataContainer();
    }

    public static boolean isWorker(@Nullable Entity entity) {
        return entity instanceof CopperGolem golem
            && golem.getPersistentDataContainer().has(Keys.JOB, PersistentDataType.STRING);
    }

    public static @Nullable Worker of(@Nullable Entity entity) {
        return isWorker(entity) ? new Worker((CopperGolem) entity) : null;
    }

    public CopperGolem golem() {
        return golem;
    }

    public @Nullable UUID owner() {
        String raw = pdc.get(Keys.OWNER, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void owner(UUID owner) {
        pdc.set(Keys.OWNER, PersistentDataType.STRING, owner.toString());
    }

    public @Nullable Job job() {
        return Job.byId(pdc.get(Keys.JOB, PersistentDataType.STRING));
    }

    public void job(Job job) {
        pdc.set(Keys.JOB, PersistentDataType.STRING, job.id());
    }

    public @Nullable BlockKey post() {
        return BlockKey.fromArray(pdc.get(Keys.POST, PersistentDataType.INTEGER_ARRAY));
    }

    public void post(BlockKey key) {
        pdc.set(Keys.POST, PersistentDataType.INTEGER_ARRAY, key.toArray());
    }

    /** Точка доставки курьера: "мир;x;y;z". */
    public @Nullable Location target() {
        String raw = pdc.get(Keys.TARGET, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void target(Block block) {
        pdc.set(Keys.TARGET, PersistentDataType.STRING,
            block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ());
    }

    public int level(Upgrade upgrade) {
        int[] levels = pdc.get(Keys.LEVELS, PersistentDataType.INTEGER_ARRAY);
        if (levels == null || levels.length <= upgrade.ordinal()) {
            return 0;
        }
        return Math.clamp(levels[upgrade.ordinal()], 0, Upgrade.MAX_LEVEL);
    }

    public void level(Upgrade upgrade, int level) {
        int[] levels = pdc.get(Keys.LEVELS, PersistentDataType.INTEGER_ARRAY);
        if (levels == null || levels.length < Upgrade.values().length) {
            levels = levels == null ? new int[Upgrade.values().length] : Arrays.copyOf(levels, Upgrade.values().length);
        }
        levels[upgrade.ordinal()] = Math.clamp(level, 0, Upgrade.MAX_LEVEL);
        pdc.set(Keys.LEVELS, PersistentDataType.INTEGER_ARRAY, levels);
    }

    public Phase phase() {
        String raw = pdc.get(Keys.PHASE, PersistentDataType.STRING);
        if (raw == null) {
            return Phase.IDLE;
        }
        try {
            return Phase.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Phase.IDLE;
        }
    }

    public void phase(Phase phase) {
        pdc.set(Keys.PHASE, PersistentDataType.STRING, phase.name());
    }

    /** Момент окончания текущей фазы рейса (System.currentTimeMillis). */
    public long phaseUntil() {
        return pdc.getOrDefault(Keys.PHASE_UNTIL, PersistentDataType.LONG, 0L);
    }

    public void phaseUntil(long millis) {
        pdc.set(Keys.PHASE_UNTIL, PersistentDataType.LONG, millis);
    }

    public long legMillis() {
        return pdc.getOrDefault(Keys.LEG_MILLIS, PersistentDataType.LONG, 0L);
    }

    public void legMillis(long millis) {
        pdc.set(Keys.LEG_MILLIS, PersistentDataType.LONG, millis);
    }

    public List<ItemStack> cargo() {
        byte[] raw = pdc.get(Keys.CARGO, PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) {
            return new ArrayList<>();
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : ItemStack.deserializeItemsFromBytes(raw)) {
            if (item != null && !item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    public void cargo(List<ItemStack> items) {
        List<ItemStack> clean = items.stream().filter(i -> i != null && !i.isEmpty()).toList();
        if (clean.isEmpty()) {
            pdc.remove(Keys.CARGO);
        } else {
            pdc.set(Keys.CARGO, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(clean));
        }
    }

    public long moved() {
        return pdc.getOrDefault(Keys.MOVED, PersistentDataType.LONG, 0L);
    }

    public void addMoved(long amount) {
        pdc.set(Keys.MOVED, PersistentDataType.LONG, moved() + amount);
    }

    /** Уровень опыта, начиная с 1. Ограничение сверху (max-level) применяет Progression. */
    public int xpLevel() {
        return Math.max(1, pdc.getOrDefault(Keys.XP_LEVEL, PersistentDataType.INTEGER, 1));
    }

    public void xpLevel(int level) {
        pdc.set(Keys.XP_LEVEL, PersistentDataType.INTEGER, Math.max(1, level));
    }

    /** Опыт, набранный внутри текущего уровня. */
    public double xp() {
        double xp = pdc.getOrDefault(Keys.XP, PersistentDataType.DOUBLE, 0.0);
        return Double.isFinite(xp) && xp > 0 ? xp : 0;
    }

    public void xp(double xp) {
        pdc.set(Keys.XP, PersistentDataType.DOUBLE, Double.isFinite(xp) ? Math.max(0, xp) : 0);
    }

    /** Начало текущего часа для лимита опыта (0 — опыт ещё не начислялся). */
    public long xpWindowStart() {
        return pdc.getOrDefault(Keys.XP_WINDOW, PersistentDataType.LONG, 0L);
    }

    /** Опыт, набранный в текущем часе. */
    public double xpWindowAmount() {
        double amount = pdc.getOrDefault(Keys.XP_WINDOW_AMOUNT, PersistentDataType.DOUBLE, 0.0);
        return Double.isFinite(amount) && amount > 0 ? amount : 0;
    }

    public void xpWindow(long start, double amount) {
        pdc.set(Keys.XP_WINDOW, PersistentDataType.LONG, start);
        pdc.set(Keys.XP_WINDOW_AMOUNT, PersistentDataType.DOUBLE, Double.isFinite(amount) ? Math.max(0, amount) : 0);
    }

    /** Черты в порядке получения. Неизвестные id (например, из будущей версии) пропускаются. */
    public List<Trait> traits() {
        String raw = pdc.get(Keys.TRAITS, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Trait> traits = new ArrayList<>(3);
        for (String id : raw.split(",")) {
            Trait trait = Trait.byId(id.trim());
            if (trait != null && !traits.contains(trait)) {
                traits.add(trait);
            }
        }
        return List.copyOf(traits);
    }

    public void traits(List<Trait> traits) {
        String raw = traits.stream().distinct().map(Trait::id).collect(Collectors.joining(","));
        if (raw.isEmpty()) {
            pdc.remove(Keys.TRAITS);
        } else {
            pdc.set(Keys.TRAITS, PersistentDataType.STRING, raw);
        }
    }

    /** Сбрасывает только данные рейса (при смене профессии). Опыт, уровень и черты остаются. */
    public void clearTrip() {
        pdc.remove(Keys.PHASE);
        pdc.remove(Keys.PHASE_UNTIL);
        pdc.remove(Keys.LEG_MILLIS);
        pdc.remove(Keys.CARGO);
    }

    /** Полностью удаляет данные плагина — голем снова обычный. */
    public void clearAll() {
        clearTrip();
        pdc.remove(Keys.OWNER);
        pdc.remove(Keys.JOB);
        pdc.remove(Keys.POST);
        pdc.remove(Keys.TARGET);
        pdc.remove(Keys.LEVELS);
        pdc.remove(Keys.MOVED);
        pdc.remove(Keys.XP);
        pdc.remove(Keys.XP_LEVEL);
        pdc.remove(Keys.TRAITS);
        pdc.remove(Keys.XP_WINDOW);
        pdc.remove(Keys.XP_WINDOW_AMOUNT);
    }
}
