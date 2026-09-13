package com.nexuscraft.nexusscan;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Remembers every chunk (in the configured world only) that any player has ever set foot in --
 * nothing more. No player identity, no position, no timestamp is kept per chunk; once a chunk is
 * in this set it's indistinguishable from any other visited chunk. That's deliberate: this plugin
 * renders a "the world as explored so far" map, not a player-tracking tool.
 */
public class ChunkVisitTracker implements Listener {

    private final JavaPlugin plugin;
    private final ScanConfig config;
    private final Set<Long> visitedChunks = new HashSet<>();
    private File storageFile;
    private BufferedWriter appendWriter;

    public ChunkVisitTracker(JavaPlugin plugin, ScanConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void load() {
        storageFile = new File(plugin.getDataFolder(), "visited-chunks.txt");
        if (storageFile.exists()) {
            try {
                for (String line : Files.readAllLines(storageFile.toPath())) {
                    parseLine(line);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't read visited-chunks.txt, starting with an empty set.", e);
            }
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
            appendWriter = new BufferedWriter(new FileWriter(storageFile, true));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't open visited-chunks.txt for writing -- newly visited chunks won't be saved.", e);
        }
        plugin.getLogger().info("NexusScan loaded " + visitedChunks.size() + " previously-visited chunk(s).");
    }

    public void close() {
        if (appendWriter == null) return;
        try {
            appendWriter.close();
        } catch (IOException ignored) {
        }
    }

    /** A read-only snapshot safe to hand to the scan task, which may take a while to consume it. */
    public Set<Long> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(visitedChunks));
    }

    public int visitedCount() {
        return visitedChunks.size();
    }

    /**
     * Bulk-adds chunks already known to exist by some other means (namely
     * {@link RegionFileScanner}'s startup backfill) without the per-call world-name check
     * {@link #recordChunk} does -- the caller is responsible for only passing coordinates that
     * belong to the tracked world. Writes are batched into one flush at the end instead of one
     * per chunk, since this can add thousands of entries at once. Returns how many were new.
     */
    public int seedChunks(Set<Long> keys) {
        int added = 0;
        for (long key : keys) {
            if (!visitedChunks.add(key)) continue;
            added++;
            if (appendWriter == null) continue;
            try {
                appendWriter.write(chunkXFromKey(key) + "," + chunkZFromKey(key));
                appendWriter.newLine();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't persist a backfilled chunk.", e);
            }
        }
        if (appendWriter != null) {
            try {
                appendWriter.flush();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't flush backfilled chunks to disk.", e);
            }
        }
        return added;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    public static int chunkXFromKey(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZFromKey(long key) {
        return (int) key;
    }

    @EventHandler(ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        recordPlayerChunk(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // cheap chunk-boundary check via bit-shift, done before touching any Chunk object --
        // this fires on essentially every player tick on the server, so it has to stay fast
        int fromChunkX = event.getFrom().getBlockX() >> 4;
        int fromChunkZ = event.getFrom().getBlockZ() >> 4;
        int toChunkX = event.getTo().getBlockX() >> 4;
        int toChunkZ = event.getTo().getBlockZ() >> 4;
        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) return;

        recordChunk(event.getPlayer().getWorld(), toChunkX, toChunkZ);
    }

    private void recordPlayerChunk(Player player) {
        recordChunk(player.getWorld(), player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
    }

    private void recordChunk(World world, int chunkX, int chunkZ) {
        if (!world.getName().equals(config.worldName())) return;

        long key = key(chunkX, chunkZ);
        if (!visitedChunks.add(key)) return; // already known

        if (appendWriter == null) return;
        try {
            appendWriter.write(chunkX + "," + chunkZ);
            appendWriter.newLine();
            appendWriter.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't persist a newly visited chunk.", e);
        }
    }

    private void parseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        int comma = trimmed.indexOf(',');
        if (comma < 0) return;
        try {
            int x = Integer.parseInt(trimmed.substring(0, comma).trim());
            int z = Integer.parseInt(trimmed.substring(comma + 1).trim());
            visitedChunks.add(key(x, z));
        } catch (NumberFormatException ignored) {
        }
    }
}
