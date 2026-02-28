package voxmap.render;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.LinkedHashMap;
import java.util.Map;

public class ChunkMeshCache {

    private final JavaPlugin plugin;

    public ChunkMeshCache(JavaPlugin plugin) { this.plugin = plugin; }

    public LruCache<String, ChunkMesh> createWorldCache() {
        int max = plugin.getConfig().getInt("performance.maxCachedChunkMeshesPerWorld", 1024);
        return new LruCache<>(Math.max(128, max));
    }

    public static class LruCache<K,V> extends LinkedHashMap<K,V> {
        private final int max;
        public LruCache(int max) { super(256, 0.75f, true); this.max = max; }
        @Override protected boolean removeEldestEntry(Map.Entry<K,V> eldest) { return size() > max; }
    }
}
