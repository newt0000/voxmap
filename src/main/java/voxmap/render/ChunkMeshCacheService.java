package voxmap.render;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import voxmap.texture.TextureAtlasService;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches chunk meshes and supports "dirty chunk" invalidation.
 * When a chunk is dirty, the next request rebuilds the mesh from a fresh ChunkSnapshot.
 */
public class ChunkMeshCacheService {

    private final JavaPlugin plugin;
    private final TextureAtlasService atlas;

    // worldName -> (key(cx,cz) -> mesh)
    private final Map<String, Map<Long, ChunkMesh>> cache = new ConcurrentHashMap<>();

    // worldName -> dirty keys
    private final Map<String, java.util.Set<Long>> dirty = new ConcurrentHashMap<>();

    public ChunkMeshCacheService(JavaPlugin plugin, TextureAtlasService atlas) {
        this.plugin = Objects.requireNonNull(plugin);
        this.atlas = Objects.requireNonNull(atlas);
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    private Map<Long, ChunkMesh> worldCache(String worldName) {
        return cache.computeIfAbsent(worldName, w -> new ConcurrentHashMap<>());
    }

    private java.util.Set<Long> worldDirty(String worldName) {
        return dirty.computeIfAbsent(worldName, w -> ConcurrentHashMap.newKeySet());
    }

    public void markDirty(String worldName, int cx, int cz) {
        worldDirty(worldName).add(key(cx, cz));
    }

    public void evict(String worldName, int cx, int cz) {
        worldCache(worldName).remove(key(cx, cz));
        worldDirty(worldName).remove(key(cx, cz));
    }

    /**
     * Returns cached mesh if present and not dirty, otherwise rebuilds now.
     * NOTE: This requires the chunk to be loaded (ChunkSnapshot comes from loaded chunk).
     */
    public ChunkMesh getOrBuild(World world, int cx, int cz, int minY, int maxYInclusive) {
        final String worldName = world.getName();
        final long k = key(cx, cz);

        ChunkMesh existing = worldCache(worldName).get(k);
        boolean isDirty = worldDirty(worldName).contains(k);

        if (existing != null && !isDirty) return existing;

        // Only build if loaded; otherwise return existing (or empty mesh)
        if (!world.isChunkLoaded(cx, cz)) {
            return existing != null ? existing : new ChunkMesh(
                    new float[0], new float[0], new float[0], new float[0],
                    new int[0], new float[0]
            );
        }

        Chunk chunk = world.getChunkAt(cx, cz);
        ChunkSnapshot snap = chunk.getChunkSnapshot(true, true, true);

        ChunkMesh rebuilt = ExposedFaceMesher.meshChunkSnapshot(snap, minY, maxYInclusive, atlas);

        worldCache(worldName).put(k, rebuilt);
        worldDirty(worldName).remove(k);

        return rebuilt;
    }
}