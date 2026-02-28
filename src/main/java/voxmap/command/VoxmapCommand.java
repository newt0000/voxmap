package voxmap.command;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import voxmap.Voxmap;
import voxmap.config.MarkerStore;
import voxmap.config.WorldsConfig;
import voxmap.render.ChunkMeshService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VoxmapCommand implements CommandExecutor, TabCompleter {

    private final Voxmap plugin;
    private final WorldsConfig worlds;
    private final MarkerStore markers;
    private final ChunkMeshService meshes;

    public VoxmapCommand(Voxmap plugin, WorldsConfig worlds, MarkerStore markers, ChunkMeshService meshes) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.markers = markers;
        this.meshes = meshes;
    }

    private boolean admin(CommandSender s) { return s.hasPermission("voxmap.admin"); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Voxmap");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " reload <config|webserver|markers|worlds|all>");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " marker add <name> [label...]");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " marker del <name>");
            sender.sendMessage(ChatColor.GRAY + "/" + label + " marker list");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!admin(sender)) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
            String target = (args.length >= 2) ? args[1].toLowerCase() : "all";
            try {
                switch (target) {
                    case "config" -> { plugin.reloadConfig(); sender.sendMessage(ChatColor.GREEN + "Reloaded config.yml"); }
                    case "webserver" -> { plugin.restartWebServer(); sender.sendMessage(ChatColor.GREEN + "Restarted web server"); }
                    case "markers" -> { markers.loadOrCreate(); sender.sendMessage(ChatColor.GREEN + "Reloaded markers.yml"); }
                    case "worlds" -> { worlds.loadOrCreate(); sender.sendMessage(ChatColor.GREEN + "Reloaded worlds.yml"); }
                    case "all" -> {
                        plugin.reloadConfig();
                        worlds.loadOrCreate();
                        markers.loadOrCreate();
                        plugin.restartWebServer();
                        sender.sendMessage(ChatColor.GREEN + "Reloaded config, worlds, markers + restarted web server");
                    }
                    default -> sender.sendMessage(ChatColor.RED + "Unknown reload target. Use config|webserver|markers|worlds|all");
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Reload failed: " + e.getMessage());
                e.printStackTrace();
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("marker")) {
            if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Player-only."); return true; }
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /" + label + " marker <add|del|list> ..."); return true; }
            String sub = args[1].toLowerCase();
            String w = p.getWorld().getName();

            if (sub.equals("add")) {
                if (!admin(sender)) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /" + label + " marker add <name> [label...]"); return true; }
                String name = args[2];
                String mlabel = (args.length > 3) ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : name;
                markers.addOrUpdate(w, name, mlabel,
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                sender.sendMessage(ChatColor.GREEN + "Marker added: " + ChatColor.AQUA + name + ChatColor.GRAY + " (" + mlabel + ")");
                return true;
            }

            if (sub.equals("del") || sub.equals("delete") || sub.equals("remove")) {
                if (!admin(sender)) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /" + label + " marker del <name>"); return true; }
                String name = args[2];
                boolean ok = markers.remove(w, name);
                sender.sendMessage(ok ? ChatColor.GREEN + "Marker removed: " + name : ChatColor.RED + "No marker named: " + name);
                return true;
            }

            if (sub.equals("list")) {
                var list = markers.list(w);
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Markers in " + w + ":");
                if (list.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "(none)"); return true; }
                for (var m : list) {
                    sender.sendMessage(ChatColor.GRAY + "- " + ChatColor.AQUA + m.name
                            + ChatColor.DARK_GRAY + " : " + ChatColor.WHITE + m.label
                            + ChatColor.DARK_GRAY + " @ " + ChatColor.GRAY + m.x + "," + m.y + "," + m.z);
                }
                return true;
            }
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /" + label + " help");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) return filter(List.of("reload","marker","help"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) return filter(List.of("config","webserver","markers","worlds","all"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("marker")) return filter(List.of("add","del","list"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> src, String q) {
        if (q == null || q.isEmpty()) return src;
        String s = q.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String it : src) if (it.toLowerCase().startsWith(s)) out.add(it);
        return out;
    }
}
