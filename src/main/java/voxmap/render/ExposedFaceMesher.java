package voxmap.render;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import voxmap.texture.TextureAtlasService;
import voxmap.texture.TextureAtlasService.UVRect;

import java.util.ArrayList;
import java.util.Locale;

public class ExposedFaceMesher {

    private static final int W = 16;
    private static final int D = 16;

    // Vertex color multipliers (textureColor * tintColor)
    // Tuned to look “vanilla-ish” without biome blending.
    private static final float[] TINT_GRASS   = new float[]{0.58f, 0.84f, 0.40f}; // lighter grass green
    private static final float[] TINT_FOLIAGE = new float[]{0.30f, 0.65f, 0.28f}; // mid foliage green
    private static final float[] TINT_WATER   = new float[]{0.40f, 0.63f, 0.98f}; // translucent-ish blue (via alphaTest + transparency in client)
    private static final float[] TINT_LAVA   = new float[]{1.25f, 0.90f, 0.21f};
    private static final float[] TINT_NONE    = new float[]{1f, 1f, 1f};


    public enum Face { UP, DOWN, NORTH, SOUTH, EAST, WEST }

    private static boolean isAirLike(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    private static boolean isLeaves(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        return n.endsWith("_leaves") || n.contains("leaves");
    }

    private static boolean isWater(Material m) {
        return m == Material.WATER;
    }
    private static boolean isLava(Material m) {
        return m == Material.LAVA;
    }

    private static boolean isGrassLikePlant(Material m) {
        String mn = m.name().toLowerCase(Locale.ROOT);
        return mn.equals("grass") || mn.equals("short_grass") || mn.equals("tall_grass")
                || mn.equals("fern") || mn.equals("large_fern")
                || mn.equals("seagrass") || mn.equals("tall_seagrass")
                || mn.equals("sugar_cane") || mn.contains("vine");
    }

    /**
     * Blocks that should render as “cutout/transparent-ish” and also should NOT occlude neighbors.
     * For phase-1 cube meshing, we treat these as cube surfaces but not occluders.
     */
    private static boolean isTransparentCutout(Material m) {
        // Leaves + water treated as non-occluding for our face culling logic
        if (isLeaves(m)) return true;
        if (isWater(m)) return true;
        if (isLava(m)) return true;
        return false;
    }

    private static boolean isSolidOccluder(BlockData bd) {
        Material m = bd.getMaterial();
        if (isAirLike(m)) return false;

        // Leaves/water shouldn’t occlude like full cubes (so we still render faces behind/around them)
        if (isTransparentCutout(m)) return false;

        return m.isSolid() && m.isOccluding();
    }

    private static boolean isEmitter(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        return n.endsWith("_torch") || n.equals("torch")
                || n.endsWith("_lantern") || n.equals("lantern")
                || n.equals("jack_o_lantern")
                || n.equals("glowstone")
                || n.equals("sea_lantern");
    }

    private static float emitterIntensity(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        if (n.contains("soul_torch") || n.contains("soul_lantern")) return 0.7f;
        if (n.contains("torch") || n.contains("lantern")) return 1.0f;
        if (n.contains("glowstone") || n.contains("sea_lantern")) return 1.2f;
        if (n.contains("jack_o_lantern")) return 1.1f;
        return 0.9f;
    }

    public static ChunkMesh meshChunkSnapshot(
            ChunkSnapshot snap,
            int minY,
            int maxYInclusive,
            TextureAtlasService atlas
    ) {
        ArrayList<Float> v = new ArrayList<>(160000);
        ArrayList<Float> n = new ArrayList<>(160000);
        ArrayList<Float> uv = new ArrayList<>(160000);
        ArrayList<Float> col = new ArrayList<>(160000);
        ArrayList<Integer> idx = new ArrayList<>(160000);

        ArrayList<Float> emitters = new ArrayList<>(2048);

        int index = 0;

        for (int y = minY; y <= maxYInclusive; y++) {
            for (int z = 0; z < D; z++) {
                for (int x = 0; x < W; x++) {
                    BlockData bd = snap.getBlockData(x, y, z);
                    Material m = bd.getMaterial();
                    if (isAirLike(m)) continue;

                    // record emitters
                    if (isEmitter(m)) {
                        emitters.add(x + 0.5f);
                        emitters.add((float) y + 0.7f);
                        emitters.add(z + 0.5f);
                        emitters.add(emitterIntensity(m));
                    }

                    // Phase-1 cube mesher:
                    // Skip non-cube model blocks to avoid “weird planes” artifacts,
                    // but allow leaves/water as cutout cubes, and allow glass/ice cubes.
                    if (!m.isSolid() && !m.isOccluding() && !isLeaves(m) && !isWater(m) && !isLava(m)) {
                        String name = m.name().toLowerCase(Locale.ROOT);
                        boolean allow = name.contains("glass") || name.contains("ice");
                        if (!allow) continue;
                    }

                    boolean nx = (x == 0)  || !isSolidOccluder(snap.getBlockData(x - 1, y, z));
                    boolean px = (x == 15) || !isSolidOccluder(snap.getBlockData(x + 1, y, z));
                    boolean nz = (z == 0)  || !isSolidOccluder(snap.getBlockData(x, y, z - 1));
                    boolean pz = (z == 15) || !isSolidOccluder(snap.getBlockData(x, y, z + 1));
                    boolean ny = (y == minY) || !isSolidOccluder(snap.getBlockData(x, y - 1, z));
                    boolean py = (y == maxYInclusive) || !isSolidOccluder(snap.getBlockData(x, y + 1, z));

                    // --- Tint selection (vertex color multiplier) ---
                    float cr = TINT_NONE[0], cg = TINT_NONE[1], cb = TINT_NONE[2];

                    // Leaves: mid foliage green
                    if (isLeaves(m)) {
                        cr = TINT_FOLIAGE[0];
                        cg = TINT_FOLIAGE[1];
                        cb = TINT_FOLIAGE[2];
                    }

                    // Grass-like plants: lighter grass green
                    if (isGrassLikePlant(m)) {
                        cr = TINT_GRASS[0];
                        cg = TINT_GRASS[1];
                        cb = TINT_GRASS[2];
                    }

                    // Water: bluish tint (translucent look is handled client-side by material settings)
                    if (isWater(m)) {
                        cr = TINT_WATER[0];
                        cg = TINT_WATER[1];
                        cb = TINT_WATER[2];
                    }
                    if (isLava(m)) {
                        cr = TINT_LAVA[0];
                        cg = TINT_LAVA[1];
                        cb = TINT_LAVA[2];
                    }

                    // Emit faces
                    if (nx) index = faceXNeg(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.WEST), cr, cg, cb);
                    if (px) index = faceXPos(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.EAST), cr, cg, cb);
                    if (nz) index = faceZNeg(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.NORTH), cr, cg, cb);
                    if (pz) index = faceZPos(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.SOUTH), cr, cg, cb);

                    if (ny) index = faceYNeg(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.DOWN), cr, cg, cb);

                    if (py) {
                        // Grass block: ONLY the top face gets the grass tint (like vanilla)
                        float tr = cr, tg = cg, tb = cb;
                        if (m == Material.GRASS_BLOCK) {
                            tr = TINT_GRASS[0];
                            tg = TINT_GRASS[1];
                            tb = TINT_GRASS[2];
                        }
                        index = faceYPos(v, n, uv, col, idx, index, x, y, z, atlas.uvFor(m, Face.UP), tr, tg, tb);
                    }
                }
            }
        }

        return new ChunkMesh(toFloat(v), toFloat(n), toFloat(uv), toFloat(col), toInt(idx), toFloat(emitters));
    }

    private static float[] toFloat(ArrayList<Float> a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i);
        return out;
    }

    private static int[] toInt(ArrayList<Integer> a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i);
        return out;
    }

    private static void add3(ArrayList<Float> a, float x, float y, float z) { a.add(x); a.add(y); a.add(z); }
    private static void add2(ArrayList<Float> a, float u, float v) { a.add(u); a.add(v); }
    private static void addColor4(ArrayList<Float> c, float r, float g, float b) {
        for (int i = 0; i < 4; i++) { c.add(r); c.add(g); c.add(b); }
    }

    private static int quad(
            ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
            ArrayList<Integer> idx, int index,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float nx, float ny, float nz,
            UVRect r,
            float cr, float cg, float cb
    ) {
        add3(v, x0, y0, z0); add3(v, x1, y1, z1); add3(v, x2, y2, z2); add3(v, x3, y3, z3);

        for (int i = 0; i < 4; i++) add3(n, nx, ny, nz);

        add2(uv, r.u0(), r.v1());
        add2(uv, r.u0(), r.v0());
        add2(uv, r.u1(), r.v0());
        add2(uv, r.u1(), r.v1());

        addColor4(col, cr, cg, cb);

        // CCW winding (fixed earlier)
        idx.add(index); idx.add(index + 2); idx.add(index + 1);
        idx.add(index); idx.add(index + 3); idx.add(index + 2);

        return index + 4;
    }

    private static int faceXNeg(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fx = x;
        return quad(v, n, uv, col, idx, index,
                fx, y, z,
                fx, y + 1, z,
                fx, y + 1, z + 1,
                fx, y, z + 1,
                -1, 0, 0, r, cr, cg, cb);
    }

    private static int faceXPos(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fx = x + 1;
        return quad(v, n, uv, col, idx, index,
                fx, y, z + 1,
                fx, y + 1, z + 1,
                fx, y + 1, z,
                fx, y, z,
                1, 0, 0, r, cr, cg, cb);
    }

    private static int faceZNeg(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fz = z;
        return quad(v, n, uv, col, idx, index,
                x + 1, y, fz,
                x + 1, y + 1, fz,
                x, y + 1, fz,
                x, y, fz,
                0, 0, -1, r, cr, cg, cb);
    }

    private static int faceZPos(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fz = z + 1;
        return quad(v, n, uv, col, idx, index,
                x, y, fz,
                x, y + 1, fz,
                x + 1, y + 1, fz,
                x + 1, y, fz,
                0, 0, 1, r, cr, cg, cb);
    }

    private static int faceYNeg(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fy = y;
        return quad(v, n, uv, col, idx, index,
                x, fy, z + 1,
                x + 1, fy, z + 1,
                x + 1, fy, z,
                x, fy, z,
                0, -1, 0, r, cr, cg, cb);
    }

    private static int faceYPos(ArrayList<Float> v, ArrayList<Float> n, ArrayList<Float> uv, ArrayList<Float> col,
                                ArrayList<Integer> idx, int index, int x, int y, int z, UVRect r, float cr, float cg, float cb) {
        float fy = y + 1;
        return quad(v, n, uv, col, idx, index,
                x, fy, z,
                x + 1, fy, z,
                x + 1, fy, z + 1,
                x, fy, z + 1,
                0, 1, 0, r, cr, cg, cb);
    }
}