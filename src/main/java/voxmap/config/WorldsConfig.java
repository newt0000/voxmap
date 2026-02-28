package voxmap.config;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WorldsConfig {

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    public WorldsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "worlds.yml");
    }

    public void loadOrCreate() {
        plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException e) { throw new RuntimeException("Failed to create worlds.yml", e); }
        }
        this.yml = YamlConfiguration.loadConfiguration(file);

        boolean changed = false;
        for (World w : Bukkit.getWorlds()) {
            String base = "worlds." + w.getName();
            if (!yml.contains(base)) {
                yml.set(base + ".enabled", true);
                yml.set(base + ".displayName", defaultDisplayName(w));
                yml.set(base + ".icon", defaultIcon(w));
                changed = true;
            }
        }
        if (changed) save();
    }

    private String defaultDisplayName(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    private String defaultIcon(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "🔥";
            case THE_END -> "🌌";
            default -> "🌍";
        };
    }

    public void save() {
        try { yml.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Failed saving worlds.yml: " + e.getMessage()); }
    }

    public boolean isWorldEnabled(String world) {
        return yml.getBoolean("worlds." + world + ".enabled", true);
    }

    public List<WorldEntry> enabledWorlds() {
        List<WorldEntry> out = new ArrayList<>();
        var sec = yml.getConfigurationSection("worlds");
        if (sec == null) return out;
        for (String name : sec.getKeys(false)) {
            boolean enabled = yml.getBoolean("worlds." + name + ".enabled", true);
            if (!enabled) continue;
            out.add(entry(name));
        }
        out.sort(Comparator.comparing(a -> a.name));
        return out;
    }

    public WorldEntry entry(String name) {
        String base = "worlds." + name;
        String displayName = yml.getString(base + ".displayName", name);
        String icon = yml.getString(base + ".icon", "");
        boolean enabled = yml.getBoolean(base + ".enabled", true);
        return new WorldEntry(name, displayName, icon, enabled);
    }

    public static class WorldEntry {
        public final String name, displayName, icon;
        public final boolean enabled;
        public WorldEntry(String name, String displayName, String icon, boolean enabled) {
            this.name = name; this.displayName = displayName; this.icon = icon; this.enabled = enabled;
        }
    }
}
