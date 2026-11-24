package dev.copperworks.worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.jspecify.annotations.Nullable;

/** Загруженные сейчас рабочие големы. Выгруженные чанки не тикают, их данные лежат в PDC. */
public final class WorkerRegistry {
    private final Map<UUID, Entry> entries = new HashMap<>();

    public record Entry(CopperGolem golem, RuntimeState state) {
    }

    public void track(CopperGolem golem) {
        entries.compute(golem.getUniqueId(), (id, old) ->
            old != null && old.golem() == golem ? old : new Entry(golem, new RuntimeState()));
    }

    public void untrack(UUID id) {
        entries.remove(id);
    }

    /** Удаляет запись, только если она про этот же экземпляр сущности. */
    public void untrack(CopperGolem golem) {
        entries.computeIfPresent(golem.getUniqueId(), (id, entry) -> entry.golem() == golem ? null : entry);
    }

    public @Nullable Entry get(UUID id) {
        return entries.get(id);
    }

    public RuntimeState state(CopperGolem golem) {
        Entry entry = entries.get(golem.getUniqueId());
        if (entry == null) {
            track(golem);
            entry = entries.get(golem.getUniqueId());
        }
        return entry.state();
    }

    public List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    public void invalidateAll() {
        entries.values().forEach(e -> e.state().invalidateCaches());
    }

    public void scanLoadedWorlds() {
        for (World world : Bukkit.getWorlds()) {
            for (CopperGolem golem : world.getEntitiesByClass(CopperGolem.class)) {
                if (Worker.isWorker(golem)) {
                    track(golem);
                }
            }
        }
    }
}
