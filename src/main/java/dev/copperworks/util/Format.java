package dev.copperworks.util;

public final class Format {
    private Format() {
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    public static String roman(int level) {
        return switch (level) {
            case 0 -> "0";
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> String.valueOf(level);
        };
    }

    public static String decimal(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
