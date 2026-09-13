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
import java.util.List;
import java.util.Set;

/**
 * Drives the whole "periodic snapshot" model this plugin is built around: on an interval (never
 * continuously), take whatever chunks {@link ChunkVisitTracker} has recorded so far, scan each one
 * a few at a time across many ticks so it never freezes the server, write a tile PNG per chunk as
 * it finishes, and push tiles to the Base44/Kairos webhook (if configured) in incremental batches
 * as the scan progresses -- not only once the entire visited set has been scanned.
 * <p>
 * That last part matters at scale: a server with years of exploration behind it can easily have
 * millions of visited chunks, and a scan spread a few chunks per tick can take hours to fully
 * drain. Waiting for the very last chunk before sending anything to Base44 means nothing shows up
 * on the website for that entire time. Instead, {@link IncrementalPyramid} keeps the tile pyramid
 * up to date one chunk at a time, and every {@code webhook.push-batch-chunks} scanned chunks (see
 * {@link ScanConfig#webhookPushBatchChunks()}), whatever changed since the last push gets sent --
 * so a partial, growing map appears on the website within minutes instead of after the whole scan
 * finishes, while the configured {@code scan-interval-hours} still governs how often a fresh scan
 * starts (e.g. 24 for once a day).
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
        IncrementalPyramid pyramid = new IncrementalPyramid(config.webhookMinZoom(), config.webhookMaxZoom());
        int totalChunks = queue.size();

        lastScanStarted = Instant.now();
        plugin.getLogger().info("NexusScan: starting a scan of " + totalChunks + " visited chunk(s) in '"
                + world.getName() + "'.");

        int chunksPerTick = config.chunksPerTick();
        int pushBatchChunks = config.webhookPushBatchChunks();

        activeCycleTask = new BukkitRunnable() {
            int sinceLastPush = 0;

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
                        pyramid.update(chunkX, chunkZ, tile);
                        sinceLastPush++;
                    }
                    processedThisTick++;
                }

                boolean finished = queue.isEmpty();

                // Push whatever's changed so far, either because a full batch's worth of chunks
                // completed or because this is the very last tick of the scan -- either way, only
                // the delta since the last push is sent (drainChangedTiles clears as it returns),
                // so this stays cheap even after millions of chunks have already gone out.
                if ((finished || sinceLastPush >= pushBatchChunks) && pyramid.hasPendingChanges()) {
                    List<TilePyramid.Tile> delta = pyramid.drainChangedTiles();
                    WebhookPusher.pushAsync(config, delta, plugin.getLogger());
                    if (!finished) {
                        plugin.getLogger().info("NexusScan: progress -- " + completedTiles.size() + "/" + totalChunks
                                + " chunk(s) scanned so far, pushed incrementally to Base44.");
                    }
                    sinceLastPush = 0;
                }

                if (finished) {
                    // The local manifest (a full listing of every tile, for the backup/debugging
                    // viewer) is only written once at the end rather than on every incremental push
                    // -- rewriting it from scratch costs roughly O(tiles written so far), and doing
                    // that every push batch would turn an N-chunk scan back into an O(N^2) one. The
                    // Base44 push above doesn't depend on it at all, so the website still gets
                    // progressive updates throughout the scan regardless.
                    TileWriter.writeManifest(config.outputDirectory(), world.getName(), completedTiles, plugin.getLogger());
                    lastScanFinished = Instant.now();
                    plugin.getLogger().info("NexusScan: scan finished -- " + completedTiles.size()
                            + " tile(s) written to " + config.outputDirectory().getPath() + ".");

                    activeCycleTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return true;
    }
}
