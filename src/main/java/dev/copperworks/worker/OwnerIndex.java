package dev.copperworks.worker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Кто сколько нанял големов. Нужен для лимита на игрока: големы могут лежать
 * в выгруженных чанках, поэтому считаем их отдельно в workers.yml.
 */
public final class OwnerIndex {
    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Set<UUID>> byOwner = new HashMap<>();
    private final Object writeLock = new Object();
    private boolean dirty;

    public OwnerIndex(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "workers.yml");
    }

    public void load() {
        byOwner.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                Set<UUID> golems = new HashSet<>();
                for (String raw : yaml.getStringList(key)) {
                    golems.add(UUID.fromString(raw));
                }
                if (!golems.isEmpty()) {
                    byOwner.put(owner, golems);
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("workers.yml: пропускаю некорректную запись " + key);
            }
        }
    }

    public int count(UUID owner) {
        return byOwner.getOrDefault(owner, Set.of()).size();
    }

    public void add(UUID owner, UUID golem) {
        if (byOwner.computeIfAbsent(owner, k -> new HashSet<>()).add(golem)) {
            dirty = true;
        }
    }

    public void remove(UUID owner, UUID golem) {
        Set<UUID> golems = byOwner.get(owner);
        if (golems != null && golems.remove(golem)) {
            if (golems.isEmpty()) {
                byOwner.remove(owner);
            }
            dirty = true;
        }
    }

    /** Вызывается периодически: снимок делается в главном потоке, запись — асинхронно. */
    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        String data = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    public void saveNow() {
        dirty = false;
        write(serialize());
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        byOwner.forEach((owner, golems) ->
            yaml.set(owner.toString(), golems.stream().map(UUID::toString).sorted().toList()));
        return yaml.saveToString();
    }

    private void write(String data) {
        synchronized (writeLock) {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                File tmp = new File(file.getParentFile(), "workers.yml.tmp");
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Не удалось сохранить workers.yml", e);
            }
        }
    }

    public List<UUID> owners() {
        return List.copyOf(byOwner.keySet());
    }
}
