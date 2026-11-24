package dev.copperworks.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Тексты из messages.yml (MiniMessage). Отсутствующие ключи берутся из версии внутри jar. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private YamlConfiguration yaml = new YamlConfiguration();

    public void load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось прочитать встроенный messages.yml: " + e.getMessage());
        }
        yaml = loaded;
    }

    public String raw(String key) {
        String value = yaml.getString(key);
        return value != null ? value : "<red>[" + key + "]";
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(MM.deserialize(raw("prefix") + raw(key), resolvers));
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        audience.sendActionBar(MM.deserialize(raw(key), resolvers));
    }
}
