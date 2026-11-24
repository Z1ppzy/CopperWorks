package dev.copperworks.util;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Логгер плагина поверх штатного логгера Paper. Каждая строка начинается с модуля: «[xp] …».
 * Paper не печатает уровень FINE, поэтому отладка выводится как INFO с пометкой [debug].
 * Отладка включается в config.yml (settings.debug: true) или переменной окружения LOG_LEVEL=debug,
 * переменная окружения важнее конфига.
 */
public final class Log {
    private static Logger logger = Logger.getLogger("CopperWorks");
    private static volatile boolean debug;

    private Log() {
    }

    public static void init(Logger pluginLogger) {
        logger = pluginLogger;
    }

    /** @return откуда взят уровень: для строки старта */
    public static String configure(boolean configDebug) {
        String env = System.getenv("LOG_LEVEL");
        if (env != null && !env.isBlank()) {
            String level = env.trim().toLowerCase(Locale.ROOT);
            switch (level) {
                case "debug", "trace", "all" -> {
                    debug = true;
                    return "debug (LOG_LEVEL=" + env.trim() + ")";
                }
                case "info", "warn", "warning", "error", "off" -> {
                    debug = false;
                    return "info (LOG_LEVEL=" + env.trim() + ")";
                }
                default -> logger.warning("[log] Неизвестное значение LOG_LEVEL=" + env.trim()
                    + ", ожидаю debug или info; беру settings.debug=" + configDebug);
            }
        }
        debug = configDebug;
        return (configDebug ? "debug" : "info") + " (settings.debug=" + configDebug + ")";
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void debug(String module, Supplier<String> message) {
        if (debug) {
            logger.info("[debug] [" + module + "] " + message.get());
        }
    }

    public static void info(String module, String message) {
        logger.info("[" + module + "] " + message);
    }

    public static void warn(String module, String message) {
        logger.warning("[" + module + "] " + message);
    }

    public static void warn(String module, String message, Throwable error) {
        logger.log(Level.WARNING, "[" + module + "] " + message + ": " + describe(error), error);
    }

    public static void error(String module, String message, Throwable error) {
        logger.log(Level.SEVERE, "[" + module + "] " + message + ": " + describe(error), error);
    }

    /** Короткий id сущности для логов (первые 8 символов UUID). */
    public static String id(@Nullable Entity entity) {
        return entity == null ? "?" : id(entity.getUniqueId());
    }

    public static String id(@Nullable UUID uuid) {
        return uuid == null ? "?" : uuid.toString().substring(0, 8);
    }

    /** Тип, сообщение и цепочка причин одной строкой; полный стек JUL печатает сам. */
    private static String describe(Throwable error) {
        StringBuilder out = new StringBuilder(error.getClass().getName()).append(": ").append(error.getMessage());
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && cause != error && depth++ < 5) {
            out.append(" ← ").append(cause.getClass().getName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return out.toString();
    }
}
