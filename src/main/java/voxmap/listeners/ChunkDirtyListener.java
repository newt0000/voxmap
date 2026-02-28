package voxmap.listeners;

import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import voxmap.render.ChunkMeshCacheService;

import java.util.Objects;

/**
 * Marks chunk meshes dirty when blocks change.
 * This makes the web map update without requiring a server restart.
 */
public class ChunkDirtyListener implements Listener {

    private final ChunkMeshCacheService cache;

    public ChunkDirtyListener(ChunkMeshCacheService cache) {
        this.cache = Objects.requireNonNull(cache);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Chunk c = e.getBlock().getChunk();
        cache.markDirty(c.getWorld().getName(), c.getX(), c.getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Chunk c = e.getBlock().getChunk();
        cache.markDirty(c.getWorld().getName(), c.getX(), c.getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onExplode(EntityExplodeEvent e) {
        // mark each affected block's chunk dirty
        e.blockList().forEach(b -> {
            Chunk c = b.getChunk();
            cache.markDirty(c.getWorld().getName(), c.getX(), c.getZ());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        cache.evict(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
    }
}