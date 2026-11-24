package dev.copperworks;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Ключи PersistentDataContainer: все данные рабочего хранятся прямо в сущности голема. */
public final class Keys {
    public static NamespacedKey OWNER;
    public static NamespacedKey JOB;
    public static NamespacedKey POST;
    public static NamespacedKey TARGET;
    public static NamespacedKey LEVELS;
    public static NamespacedKey PHASE;
    public static NamespacedKey PHASE_UNTIL;
    public static NamespacedKey LEG_MILLIS;
    public static NamespacedKey CARGO;
    public static NamespacedKey MOVED;
    /** Опыт внутри текущего уровня (double). */
    public static NamespacedKey XP;
    /** Уровень голема, 1..experience.max-level. */
    public static NamespacedKey XP_LEVEL;
    /** Черты характера: id через запятую в порядке получения. */
    public static NamespacedKey TRAITS;
    /** Начало текущего часа для лимита опыта (System.currentTimeMillis). */
    public static NamespacedKey XP_WINDOW;
    /** Сколько опыта набрано в текущем часе. */
    public static NamespacedKey XP_WINDOW_AMOUNT;

    private Keys() {
    }

    public static void init(Plugin plugin) {
        OWNER = new NamespacedKey(plugin, "owner");
        JOB = new NamespacedKey(plugin, "job");
        POST = new NamespacedKey(plugin, "post");
        TARGET = new NamespacedKey(plugin, "target");
        LEVELS = new NamespacedKey(plugin, "levels");
        PHASE = new NamespacedKey(plugin, "phase");
        PHASE_UNTIL = new NamespacedKey(plugin, "phase_until");
        LEG_MILLIS = new NamespacedKey(plugin, "leg_millis");
        CARGO = new NamespacedKey(plugin, "cargo");
        MOVED = new NamespacedKey(plugin, "moved");
        XP = new NamespacedKey(plugin, "xp");
        XP_LEVEL = new NamespacedKey(plugin, "xp_level");
        TRAITS = new NamespacedKey(plugin, "traits");
        XP_WINDOW = new NamespacedKey(plugin, "xp_window");
        XP_WINDOW_AMOUNT = new NamespacedKey(plugin, "xp_window_amount");
    }
}
