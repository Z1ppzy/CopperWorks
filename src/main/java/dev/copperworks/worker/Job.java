package dev.copperworks.worker;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

public enum Job {
    SORTER("sorter", Material.HOPPER),
    COURIER("courier", Material.BUNDLE),
    FARMER("farmer", Material.COPPER_HOE);

    private final String id;
    private final Material icon;

    Job(String id, Material icon) {
        this.id = id;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    public Material icon() {
        return icon;
    }

    public static @Nullable Job byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Job job : values()) {
            if (job.id.equals(id)) {
                return job;
            }
        }
        return null;
    }
}
