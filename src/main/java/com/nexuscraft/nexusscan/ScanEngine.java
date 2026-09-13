package com.nexuscraft.nexusscan;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the whole "periodic snapshot" model this plugin is built around: on an interval (never
 * continuously), take whatever chunks {@link ChunkVisitTracker} has recorded so far, scan each
 * one a few at a time across many ticks so it never freezes the server, write a tile PNG per
 * chunk as it finishes, and once the whole batch is done write manifest.json locally and (if
 * configured) push the full tile pyramid to the Base44/Kairos webhook via {@link WebhookPusher}.
 */
public class ScanEngine {

    private final JavaPlugin plugin;
    private final ScanConfig config;
    private final ChunkVisitTracker tracker;

    private BukkitTask periodicTask;
    private BukkitTask activeCycleTask;
    private Instant lastScanStarted;
    private Instant lastScanFinished;

    public ScanEngine(JavaPlugin plugin, ScanConfig config, ChunkVisitTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    public void startSchedule() {
        long periodTicks = config.scanIntervalHours() * 60L * 60L * 20L;

        long initialDelayTicks;
        if (config.scanOnStartup()) {
            initialDelayTicks = config.startupScanDelaySeconds() * 20L;
        } else {
            initialDelayTicks = periodTicks;
        }

        periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, this::startCycle, initialDelayTicks, periodTicks);
    }

    public void stop() {
        if (periodicTask != null) periodicTask.cancel();
        if (activeCycleTask != null) activeCycleTask.cancel();
    }

    public boolean isScanInProgress() {
        return activeCycleTask != null;
    }

    public Instant lastScanStarted() {
        return lastScanStarted;
    }

    public Instant lastScanFinished() {
        return lastScanFinished;
    }

    /** Returns false (and starts nothing) if a scan is already running. */
    public boolean startCycle() {
        if (activeCycleTask != null) return false;

        World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            plugin.getLogger().warning("NexusScan: configured world '" + config.worldName()
                    + "' isn't loaded -- skipping this scan.");
            return false;
        }

        Set<Long> snapshot = tracker.snapshot();
        Deque<Long> queue = new ArrayDeque<>(snapshot);
        List<long[]> completedTiles = new ArrayList<>(snapshot.size());
        Map<Long, BufferedImage> finestTiles = new HashMap<>(snapshot.size());

        lastScanStarted = Instant.now();
        plugin.getLogger().info("NexusScan: starting a scan of " + queue.size() + " visited chunk(s) in '"
                + world.getName() + "'.");

        int chunksPerTick = config.chunksPerTick();
        activeCycleTask = new BukkitRunnable() {
            @Override
            public void run() {
                int processedThisTick = 0;
                while (processedThisTick < chunksPerTick && !queue.isEmpty()) {
                    long key = queue.poll();
                    int chunkX = ChunkVisitTracker.chunkXFromKey(key);
                    int chunkZ = ChunkVisitTracker.chunkZFromKey(key);

                    BufferedImage tile = ChunkScanner.scanChunk(world, chunkX, chunkZ);
                    if (tile != null) {
                        TileWriter.writeTile(config.outputDirectory(), world.getName(), chunkX, chunkZ, tile, plugin.getLogger());
                        completedTiles.add(new long[]{chunkX, chunkZ});
                        finestTiles.put(key, tile);
                    }
                    processedThisTick++;
                }

                if (queue.isEmpty()) {
                    TileWriter.writeManifest(config.outputDirectory(), world.getName(), completedTiles, plugin.getLogger());
                    lastScanFinished = Instant.now();
                    plugin.getLogger().info("NexusScan: scan finished -- " + completedTiles.size()
                            + " tile(s) written to " + config.outputDirectory().getPath() + ".");

                    List<TilePyramid.Tile> pyramid = TilePyramid.build(finestTiles, config.webhookMinZoom(), config.webhookMaxZoom());
                    WebhookPusher.pushAsync(config, pyramid, plugin.getLogger());

                    activeCycleTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }
}
