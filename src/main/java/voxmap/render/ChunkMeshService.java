package voxmap.render;

import voxmap.config.WorldsConfig;
import voxmap.texture.TextureAtlasService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkMeshService {
    private final JavaPlugin plugin;
    private final ChunkMeshCache cacheFactory;
    private final WorldsConfig worlds;
    private final TextureAtlasService atlas; // NEW
    private final ExecutorService pool;

    private final ConcurrentHashMap<String, ChunkMeshCache.LruCache<String, ChunkMesh>> worldCaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> dirtyDebounce = new ConcurrentHashMap<>();

    public ChunkMeshService(JavaPlugin plugin, ChunkMeshCache cacheFactory, WorldsConfig worlds, TextureAtlasService atlas) {
        this.plugin = plugin;
        this.cacheFactory = cacheFactory;
        this.worlds = worlds;
        this.atlas = atlas;

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        AtomicInteger c = new AtomicInteger(1);
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Voxmap-Mesher-" + c.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public void shutdown() { pool.shutdownNow(); }

    private ChunkMeshCache.LruCache<String, ChunkMesh> cacheFor(World world) {
        return worldCaches.computeIfAbsent(world.getName(), k -> cacheFactory.createWorldCache());
    }

    private static String chunkKey(int cx, int cz) { return cx + "," + cz; }

    public ChunkMesh getOrBuild(World world, int cx, int cz) throws Exception {
        if (!worlds.isWorldEnabled(world.getName()))
            return new ChunkMesh(new float[0], new float[0], new float[0], new float[0], new int[0], new float[0]);

        boolean requireLoaded = plugin.getConfig().getBoolean("render.requireChunkLoaded", true);
        var cache = cacheFor(world);
        String k = chunkKey(cx, cz);

        synchronized (cache) {
            ChunkMesh existing = cache.get(k);
            if (existing != null) return existing;
        }

        CompletableFuture<org.bukkit.ChunkSnapshot> snapF = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (requireLoaded && !world.isChunkLoaded(cx, cz)) { snapF.complete(null); return; }
                Chunk chunk = world.getChunkAt(cx, cz); // will load if requireLoaded=false
                snapF.complete(chunk.getChunkSnapshot(true, true, false));
            } catch (Throwable t) {
                snapF.completeExceptionally(t);
            }
        });

        org.bukkit.ChunkSnapshot snap = snapF.get(2, TimeUnit.SECONDS);
        if (snap == null) return new ChunkMesh(new float[0], new float[0], new float[0], new float[0], new int[0], new float[0]);



        Future<ChunkMesh> meshF = pool.submit(() ->
                ExposedFaceMesher.meshChunkSnapshot(
                        snap,
                        world.getMinHeight(),
                        world.getMaxHeight() - 1,
                        atlas
                )
        );

        ChunkMesh mesh = meshF.get(12, TimeUnit.SECONDS);

        synchronized (cache) { cache.put(k, mesh); }
        return mesh;
    }

    public void markDirty(World world, int cx, int cz) {
        String wk = world.getName() + ":" + chunkKey(cx, cz);
        dirtyDebounce.put(wk, System.currentTimeMillis());
        int debounceMs = plugin.getConfig().getInt("performance.chunkDirtyDebounceMs", 500);

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            Long t = dirtyDebounce.get(wk);
            if (t == null) return;
            if (System.currentTimeMillis() - t < debounceMs) return;
            dirtyDebounce.remove(wk);
            var cache = cacheFor(world);
            synchronized (cache) { cache.remove(chunkKey(cx, cz)); }
        }, Math.max(1, debounceMs / 50));
    }
}