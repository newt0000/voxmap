package voxmap.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import voxmap.config.MarkerStore;
import voxmap.config.WorldsConfig;
import voxmap.render.ChunkMesh;
import voxmap.render.ChunkMeshService;
import voxmap.texture.TextureAtlasService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

import org.bukkit.World;
import voxmap.render.ChunkMeshCacheService;
import voxmap.render.ChunkMesh;

public class WebServer {
    private final JavaPlugin plugin;
    private final WorldsConfig worlds;
    private final MarkerStore markers;
    private final ChunkMeshService meshes;
    private final TextureAtlasService atlas;
    private HttpServer server;
    private final ChunkMeshCacheService chunkCache;

    public WebServer(JavaPlugin plugin, WorldsConfig worlds, MarkerStore markers, ChunkMeshService meshes, TextureAtlasService atlas, ChunkMeshCacheService chunkCache) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.markers = markers;
        this.meshes = meshes;
        this.atlas = atlas;
        this.chunkCache = chunkCache;
    }

    public void start() throws Exception {
        String host = plugin.getConfig().getString("web.host", "0.0.0.0");
        int port = plugin.getConfig().getInt("web.port", 8765);
        server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        ));

        server.createContext("/", this::handleRoot);
        server.createContext("/static/", this::handleStatic);

        server.createContext("/api/worlds", this::handleWorlds);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/players", this::handlePlayers);
        server.createContext("/api/markers", this::handleMarkers);
        server.createContext("/api/chunk", this::handleChunk);
        server.createContext("/api/atlas.png", this::handleAtlasPng);

        server.start();
        plugin.getLogger().info("[Voxmap] Web server started on http://" + host + ":" + port + "/");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            plugin.getLogger().info("[Voxmap] Web server stopped.");
        }
    }

    private boolean isOptions(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            withCors(ex);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private void withCors(HttpExchange ex) {
        if (!plugin.getConfig().getBoolean("web.enableCors", true)) return;
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void reply(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        withCors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);

        try {
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (IOException ioe) {
            // Client disconnected mid-response (common while panning/zooming).
            String msg = String.valueOf(ioe.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("broken pipe") || msg.contains("connection was aborted") || msg.contains("connection reset")) {
                return; // ignore
            }
            throw ioe;
        } finally {
            try { ex.close(); } catch (Exception ignored) {}
        }
    }

    private void replyJson(HttpExchange ex, int code, String json) throws IOException {
        reply(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        if (!"/".equals(ex.getRequestURI().getPath())) { ex.sendResponseHeaders(404, -1); return; }
        byte[] html = readResource("web/index.html");
        if (html == null) {
            reply(ex, 500, "text/plain; charset=utf-8", "Missing web/index.html".getBytes(StandardCharsets.UTF_8));
            return;
        }
        reply(ex, 200, "text/html; charset=utf-8", html);
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith("/static/")) { ex.sendResponseHeaders(404, -1); return; }
        String rel = path.substring("/static/".length());
        byte[] data = readResource("web/static/" + rel);
        if (data == null) {
            reply(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        reply(ex, 200, contentType(rel), data);
    }

    private void handleAtlasPng(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        byte[] png = atlas.getAtlasPngBytes();
        if (png == null || png.length == 0) {
            reply(ex, 500, "text/plain; charset=utf-8", "Atlas not ready".getBytes(StandardCharsets.UTF_8));
            return;
        }
        reply(ex, 200, "image/png", png);
    }

    private void handleWorlds(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        int viewDist = plugin.getConfig().getInt("render.defaultViewDistanceChunks", 10);
        var list = worlds.enabledWorlds();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"defaultViewDistanceChunks\":").append(viewDist).append(",\"worlds\":[");
        for (int i = 0; i < list.size(); i++) {
            var w = list.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(json(w.name))
                    .append(",\"displayName\":").append(json(w.displayName))
                    .append(",\"icon\":").append(json(w.icon))
                    .append("}");
        }
        sb.append("]}");

        replyJson(ex, 200, sb.toString());
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String worldName = q.getOrDefault("world", Bukkit.getWorlds().get(0).getName());
        World w = Bukkit.getWorld(worldName);
        if (w == null) { replyJson(ex, 400, "{\"error\":\"world_not_found\"}"); return; }

        boolean showClock = plugin.getConfig().getBoolean("ui.showClock", true);
        boolean showWeather = plugin.getConfig().getBoolean("ui.showWeather", true);
        boolean showDayNight = plugin.getConfig().getBoolean("ui.showDayNight", true);

        String weather = "CLEAR";
        if (w.hasStorm()) weather = "RAIN";
        if (w.isThundering()) weather = "THUNDER";
        boolean isDay = (w.getTime() % 24000L) < 12000L;

        var spawn = w.getSpawnLocation();
        String out = "{"
                + "\"showClock\":" + showClock + ","
                + "\"showWeather\":" + showWeather + ","
                + "\"showDayNight\":" + showDayNight + ","
                + "\"timeTicks\":" + w.getTime() + ","
                + "\"weather\":" + json(weather) + ","
                + "\"isDay\":" + isDay + ","
                + "\"spawn\":{\"x\":" + spawn.getX() + ",\"y\":" + spawn.getY() + ",\"z\":" + spawn.getZ() + "}"
                + "}";
        replyJson(ex, 200, out);
    }

    private void handlePlayers(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        var players = Bukkit.getOnlinePlayers();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"players\":[");
        int i = 0;
        for (Player p : players) {
            if (i++ > 0) sb.append(',');
            var loc = p.getLocation();
            sb.append("{\"uuid\":").append(json(p.getUniqueId().toString()))
                    .append(",\"name\":").append(json(p.getName()))
                    .append(",\"world\":").append(json(p.getWorld().getName()))
                    .append(",\"x\":").append(String.format(Locale.US, "%.3f", loc.getX()))
                    .append(",\"y\":").append(String.format(Locale.US, "%.3f", loc.getY()))
                    .append(",\"z\":").append(String.format(Locale.US, "%.3f", loc.getZ()))
                    .append("}");
        }
        sb.append("]}");
        replyJson(ex, 200, sb.toString());
    }

    private void handleMarkers(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String world = q.get("world");
        if (world == null) { replyJson(ex, 400, "{\"error\":\"world_required\"}"); return; }

        var list = markers.list(world);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"markers\":[");
        for (int j = 0; j < list.size(); j++) {
            var m = list.get(j);
            if (j > 0) sb.append(',');
            sb.append("{\"name\":").append(json(m.name))
                    .append(",\"label\":").append(json(m.label))
                    .append(",\"x\":").append(m.x)
                    .append(",\"y\":").append(m.y)
                    .append(",\"z\":").append(m.z)
                    .append("}");
        }
        sb.append("]}");
        replyJson(ex, 200, sb.toString());
    }

    private void handleChunk(HttpExchange ex) throws IOException {
        if (isOptions(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String worldName = q.get("world");
        String scx = q.get("cx");
        String scz = q.get("cz");
        if (worldName == null || scx == null || scz == null) {
            replyJson(ex, 400, "{\"error\":\"world,cx,cz required\"}");
            return;
        }

        World w = Bukkit.getWorld(worldName);
        if (w == null) { replyJson(ex, 400, "{\"error\":\"world_not_found\"}"); return; }
        int cx, cz;
        int minY = w.getMinHeight();
        int maxYInclusive = w.getMaxHeight() - 1;



        try { cx = Integer.parseInt(scx); cz = Integer.parseInt(scz); }
        catch (NumberFormatException nfe) { replyJson(ex, 400, "{\"error\":\"cx,cz must be int\"}"); return; }

        try {
            ChunkMesh mesh = chunkCache.getOrBuild(w, cx, cz, minY, maxYInclusive);
            if (mesh.isEmpty()) {
                replyJson(ex, 200, "{\"vertices\":[],\"normals\":[],\"uvs\":[],\"colors\":[],\"indices\":[],\"emitters\":[]}");
                return;
            }

            int ox = cx << 4;
            int oz = cz << 4;

            float[] vtx = mesh.vertices.clone();
            for (int i = 0; i < vtx.length; i += 3) {
                vtx[i] += ox;
                vtx[i + 2] += oz;
            }

            float[] em = mesh.emitters == null ? new float[0] : mesh.emitters.clone();
            for (int i = 0; i < em.length; i += 4) {
                em[i] += ox;      // x
                // y unchanged
                em[i + 2] += oz;  // z
                // intensity unchanged
            }

            String out = "{"
                    + "\"vertices\":" + floatArray(vtx) + ","
                    + "\"normals\":" + floatArray(mesh.normals) + ","
                    + "\"uvs\":" + floatArray(mesh.uvs) + ","
                    + "\"colors\":" + floatArray(mesh.colors) + ","
                    + "\"indices\":" + intArray(mesh.indices) + ","
                    + "\"emitters\":" + floatArray(em)
                    + "}";
            replyJson(ex, 200, out);
        } catch (Exception e) {
            // If client disconnected, reply() ignores it; don't spam hard.
            plugin.getLogger().warning("Chunk mesh error: " + e.getMessage());
            try { replyJson(ex, 500, "{\"error\":\"meshing_failed\"}"); } catch (Exception ignored) {}
        }
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) return null;
            return in.readAllBytes();
        }
    }

    private String contentType(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return map;

        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            map.put(urlDecode(part.substring(0, i)), urlDecode(part.substring(i + 1)));
        }
        return map;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 32) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String floatArray(float[] a) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.6f", a[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String intArray(int[] a) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}