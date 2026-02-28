package voxmap.texture;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import voxmap.render.ExposedFaceMesher.Face;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TextureAtlasService {

    public record UVRect(float u0, float v0, float u1, float v1) {}

    private final JavaPlugin plugin; // may be null if constructed manually
    private final int tileSize;
    private final int tilesPerRow;

    private final Map<String, Integer> keyToIndex = new HashMap<>();
    private BufferedImage atlas;
    private int tilesCount;

    // Aliases if a material texture is missing in the pack or not yet mapped.
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("birch_leaves", "oak_leaves"),
            Map.entry("spruce_leaves", "oak_leaves"),
            Map.entry("jungle_leaves", "oak_leaves"),
            Map.entry("acacia_leaves", "oak_leaves"),
            Map.entry("dark_oak_leaves", "oak_leaves"),
            Map.entry("mangrove_leaves", "oak_leaves"),
            Map.entry("azalea_leaves", "oak_leaves"),
            Map.entry("flowering_azalea_leaves", "oak_leaves"),
            Map.entry("water", "water_still"),
            Map.entry("water_still", "water_still"),
            Map.entry("water_flow", "water_flow"),
            Map.entry("lava_still", "lava_still"),
            Map.entry("lava_flow", "lava_flow"),
            Map.entry("snow","snow"),
            Map.entry("snow_block", "snow_block"),

            // Common grass naming differences across packs
            Map.entry("grass", "short_grass"),
            Map.entry("tall_grass", "short_grass")
    );

    // --- Backward compatible constructor used by your Voxmap.java ---
    public TextureAtlasService(JavaPlugin plugin) {
        this(plugin, 16, 32);
    }

    public TextureAtlasService(int tileSize, int tilesPerRow) {
        this(null, tileSize, tilesPerRow);
    }

    public TextureAtlasService(JavaPlugin plugin, int tileSize, int tilesPerRow) {
        this.plugin = plugin;
        this.tileSize = tileSize;
        this.tilesPerRow = tilesPerRow;
    }

    public BufferedImage getAtlas() { return atlas; }
    public int getTilesCount() { return tilesCount; }
    public int getTilesPerRow() { return tilesPerRow; }
    public int getTileSize() { return tileSize; }

    /**
     * Backward compatible method expected by Voxmap.java
     * Builds atlas from texture pack folder (or fallback).
     */
    public void loadOrCreate() throws Exception {
        if (plugin == null) {
            throw new IllegalStateException("TextureAtlasService was not constructed with plugin; cannot loadOrCreate().");
        }

        File data = plugin.getDataFolder();
        if (!data.exists()) data.mkdirs();

        File tpDir = new File(data, "texturepacks");
        if (!tpDir.exists()) tpDir.mkdirs();

        // Config key: texturepack: "default-1.21.11.zip" OR absolute path
        String cfg = plugin.getConfig().getString("textures.packFile", "default-1.21.11.zip");
        File chosen = resolveTexturePack(tpDir, cfg);

        if (chosen != null && chosen.isFile() && chosen.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            plugin.getLogger().info("[Voxmap] Loading textures from resource pack: " + chosen.getAbsolutePath());
            loadFromResourcePackZip(chosen.getAbsolutePath());
            plugin.getLogger().info("[Voxmap] Atlas tiles=" + tilesCount + " tilesPerRow=" + tilesPerRow);
            plugin.getLogger().info("[Voxmap] Atlas ready: " + atlas.getWidth() + "x" + atlas.getHeight() + " tiles=" + tilesCount);
            return;
        }

        plugin.getLogger().warning("[Voxmap] No valid texture pack zip found. Using fallback atlas.");
        buildFallbackAtlas();
    }

    private File resolveTexturePack(File tpDir, String cfg) {
        if (cfg == null || cfg.isBlank()) cfg = "default-1.21.11.zip";

        // absolute path?
        File f = new File(cfg);
        if (f.isAbsolute() && f.exists()) return f;

        // relative to texturepacks directory
        File rel = new File(tpDir, cfg);
        if (rel.exists()) return rel;

        // if "default-1.21.11.zip" isn't found, pick first zip
        File[] zips = tpDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (zips != null && zips.length > 0) {
            Arrays.sort(zips, Comparator.comparing(File::getName));
            return zips[0];
        }

        return null;
    }

    /**
     * Backward compatible method expected by WebServer.java
     */
    public byte[] getAtlasPngBytes() throws IOException {
        if (atlas == null) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(atlas, "png", baos);
        return baos.toByteArray();
    }

    public void loadFromResourcePackZip(String zipPath) throws Exception {
        Map<String, BufferedImage> textures = new HashMap<>();

        try (ZipFile zip = new ZipFile(zipPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;

                String name = e.getName();
                if (!name.endsWith(".png")) continue;
                if (!name.startsWith("assets/minecraft/textures/")) continue;

                String rel = name.substring("assets/minecraft/textures/".length());
                if (!rel.startsWith("block/")) continue;

                // block/foo.png -> foo
                String key = rel.substring("block/".length(), rel.length() - 4);

                try (InputStream in = zip.getInputStream(e)) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) {
                        // Many animated textures (water/lava) are stored as vertical strips:
                        // 16 x (16*frames). Use the first frame (top 16x16).
                        if (img.getWidth() == tileSize && img.getHeight() > tileSize) {
                            img = img.getSubimage(0, 0, tileSize, tileSize);
                        }
                        // Some packs ship higher-res textures (e.g. 32x32, 64x64).
                        // The atlas draw step already scales to tileSize, so no extra handling needed.

                        textures.put(key, img);
                    }
                    if (img != null) textures.put(key, img);
                } catch (Exception ignored) {}
            }
        }

        if (!textures.containsKey("stone")) {
            textures.put("stone", solid(tileSize, tileSize, 120, 120, 120));
        }

        buildAtlasFromTextures(textures);
    }

    private void buildFallbackAtlas() {
        Map<String, BufferedImage> textures = new HashMap<>();
        textures.put("stone", solid(tileSize, tileSize, 120, 120, 120));
        textures.put("dirt", solid(tileSize, tileSize, 100, 70, 40));
        textures.put("grass_block_top", solid(tileSize, tileSize, 90, 170, 90));
        textures.put("grass_block_side", solid(tileSize, tileSize, 110, 140, 80));
        textures.put("oak_leaves", solid(tileSize, tileSize, 60, 120, 60));
        buildAtlasFromTextures(textures);
    }

    private void buildAtlasFromTextures(Map<String, BufferedImage> textures) {
        List<String> keys = new ArrayList<>(textures.keySet());
        keys.sort(String::compareTo);

        tilesCount = keys.size();

        int rows = (int) Math.ceil(tilesCount / (double) tilesPerRow);
        int width = tilesPerRow * tileSize;
        int height = rows * tileSize;

        atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = atlas.createGraphics();

        keyToIndex.clear();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            BufferedImage img = textures.get(key);

            keyToIndex.put(key, i);

            int tx = (i % tilesPerRow) * tileSize;
            int ty = (i / tilesPerRow) * tileSize;

            // scale/crop to tileSize
            g.drawImage(img, tx, ty, tileSize, tileSize, null);
        }
        g.dispose();
    }

    private static BufferedImage solid(int w, int h, int r, int g, int b) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var gg = img.createGraphics();
        gg.setColor(new java.awt.Color(r, g, b));
        gg.fillRect(0, 0, w, h);
        gg.dispose();
        return img;
    }

    private static String normalizeKey(String s) {
        return s.toLowerCase(Locale.ROOT).replace("minecraft:", "");
    }

    private String materialBaseKey(Material m) {
        return normalizeKey(m.name());
    }

    private String resolveKeyOrAlias(String key) {
        if (keyToIndex.containsKey(key)) return key;
        String alias = ALIASES.get(key);
        if (alias != null && keyToIndex.containsKey(alias)) return alias;
        return "stone";
    }

    public UVRect uvFor(Material m, Face face) {
        String k = materialBaseKey(m);

        // Special face mappings for a couple common blocks
        if (m == Material.GRASS_BLOCK) {
            if (face == Face.UP) k = "grass_block_top";
            else if (face == Face.DOWN) k = "dirt";
            else k = "grass_block_side";
        } else if (m == Material.DIRT_PATH) {
            if (face == Face.UP) k = "dirt_path_top";
            else if (face == Face.DOWN) k = "dirt";
            else k = "dirt_path_side";
        } else if (m == Material.FARMLAND) {
            if (face == Face.UP) k = "farmland_top";
            else if (face == Face.DOWN) k = "dirt";
            else k = "farmland_side";
        }
        if (m == Material.DIAMOND_ORE) {
            if (face == Face.UP || face == Face.DOWN) k = "stone";
            k = "stone";
        }
        if (m == Material.WATER) {
            // Vanilla pack keys
            if (face == Face.UP || face == Face.DOWN) k = "water_still";
            else k = "water_flow";
        }
        if (m == Material.LAVA) {
            if (face == Face.UP || face == Face.DOWN) k = "lava_still";
            else k = "lava_flow";
        }
        if (m == Material.SNOW) {
            k = "snow";
        }
        if (m == Material.SNOW_BLOCK) {
            k = "snow";
        }
        k = resolveKeyOrAlias(k);

        int idx = keyToIndex.getOrDefault(k, keyToIndex.getOrDefault("stone", 0));
        int x = idx % tilesPerRow;
        int y = idx / tilesPerRow;

        float atlasW = (float) (tilesPerRow * tileSize);
        float atlasH = (float) atlas.getHeight();

        float u0 = (x * tileSize) / atlasW;
        float v0 = (y * tileSize) / atlasH;
        float u1 = ((x + 1) * tileSize) / atlasW;
        float v1 = ((y + 1) * tileSize) / atlasH;

        return new UVRect(u0, v0, u1, v1);
    }
}