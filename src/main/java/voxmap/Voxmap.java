package voxmap;

import org.bukkit.plugin.java.JavaPlugin;
import voxmap.command.VoxmapCommand;
import voxmap.config.MarkerStore;
import voxmap.config.WorldsConfig;
import voxmap.http.WebServer;
import voxmap.render.ChunkMeshCache;
import voxmap.render.ChunkMeshService;
import voxmap.texture.TextureAtlasService;
import voxmap.listeners.ChunkDirtyListener;
import voxmap.render.ChunkMeshCacheService;



public final class Voxmap extends JavaPlugin {

    private WebServer webServer;
    private WorldsConfig worldsConfig;
    private MarkerStore markerStore;
    private ChunkMeshService meshService;
    private TextureAtlasService atlasService;

    private ChunkMeshCacheService chunkCache;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Always re-load from disk (not a stale in-memory copy)
        reloadConfig();
        String mode = getConfig().getString("textures.mode", "resourcepack");
        String packFile = getConfig().getString("textures.packFile", "texturepacks/default-1.21.11.zip");
        int tileSize = getConfig().getInt("textures.tileSize", 16);
        int maxTiles = getConfig().getInt("textures.maxTiles", 1024);

        getLogger().info("Textures: mode=" + mode + " packFile=" + packFile
                + " tileSize=" + tileSize + " maxTiles=" + maxTiles);
        // Now read the actual value
        String pack = getConfig().getString("textures.packFile", "default");
        getLogger().info("Config texturepack=" + pack);
        worldsConfig = new WorldsConfig(this);
        worldsConfig.loadOrCreate();

        markerStore = new MarkerStore(this);
        markerStore.loadOrCreate();

        atlasService = new TextureAtlasService(this);
        // builds atlas from resource pack or fallback
        try {
            atlasService.loadOrCreate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        chunkCache = new ChunkMeshCacheService(this, atlasService);
        getServer().getPluginManager().registerEvents(new ChunkDirtyListener(chunkCache), this);
        meshService = new ChunkMeshService(this, new ChunkMeshCache(this), worldsConfig, atlasService);

        VoxmapCommand cmd = new VoxmapCommand(this, worldsConfig, markerStore, meshService);
        var c = getCommand("voxmap");
        if (c != null) {
            c.setExecutor(cmd);
            c.setTabCompleter(cmd);
        } else {
            getLogger().severe("Command 'voxmap' missing from plugin.yml");
        }

        webServer = new WebServer(this, worldsConfig, markerStore, meshService, atlasService, chunkCache);
        try {
            webServer.start();
        } catch (Exception e) {
            getLogger().severe("Failed to start web server: " + e.getMessage());
            e.printStackTrace();
        }

        getLogger().info("Voxmap enabled.");
    }

    @Override
    public void onDisable() {
        if (webServer != null) webServer.stop();
        if (meshService != null) meshService.shutdown();
        getLogger().info("Voxmap disabled.");
    }

    public void restartWebServer() throws Exception {
        if (webServer != null) webServer.stop();
        webServer = new WebServer(this, worldsConfig, markerStore, meshService, atlasService, chunkCache);
        webServer.start();
    }
}