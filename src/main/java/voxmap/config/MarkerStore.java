package voxmap.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MarkerStore {
    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration yml;

    public MarkerStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "markers.yml");
    }

    public void loadOrCreate() {
        plugin.getDataFolder().mkdirs();
        if (!file.exists()) {
            try { file.createNewFile(); }
            catch (IOException e) { throw new RuntimeException("Failed to create markers.yml", e); }
        }
        this.yml = YamlConfiguration.loadConfiguration(file);
        if (!yml.contains("markers")) {
            yml.set("markers", new LinkedHashMap<>());
            save();
        }
    }

    public void addOrUpdate(String world, String name, String label, int x, int y, int z) {
        String base = "markers." + world + "." + name;
        yml.set(base + ".label", label);
        yml.set(base + ".x", x);
        yml.set(base + ".y", y);
        yml.set(base + ".z", z);
        save();
    }

    public boolean remove(String world, String name) {
        String base = "markers." + world + "." + name;
        if (!yml.contains(base)) return false;
        yml.set(base, null);
        save();
        return true;
    }

    public List<Marker> list(String world) {
        List<Marker> out = new ArrayList<>();
        var sec = yml.getConfigurationSection("markers." + world);
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            String base = "markers." + world + "." + key;
            String label = yml.getString(base + ".label", key);
            int x = yml.getInt(base + ".x");
            int y = yml.getInt(base + ".y");
            int z = yml.getInt(base + ".z");
            out.add(new Marker(key, label, x, y, z));
        }
        out.sort(Comparator.comparing(a -> a.name));
        return out;
    }

    public void save() {
        try { yml.save(file); }
        catch (IOException e) { plugin.getLogger().warning("Failed saving markers.yml: " + e.getMessage()); }
    }

    public static class Marker {
        public final String name, label;
        public final int x,y,z;
        public Marker(String name, String label, int x, int y, int z) {
            this.name=name; this.label=label; this.x=x; this.y=y; this.z=z;
        }
    }
}
