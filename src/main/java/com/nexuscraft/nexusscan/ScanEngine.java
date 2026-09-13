package com.nexuscraft.nexusscan;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Drives the whole "periodic snapshot" model this plugin is built around: on an interval (never
 * continuously), take whatever chunks {@link ChunkVisitTracker} has recorded so far, order them
 * outward from a fixed origin point, and scan them -- deliberately engineered so this never costs
 * the server a noticeable amount of main-thread time, however many millions of chunks are visited:
 * <ul>
 *   <li>Chunks are never force-loaded synchronously. Every chunk goes through
 *       {@link World#getChunkAtAsync}, which -- per Paper's own contract -- does any needed disk
 *       I/O off the main thread and only resumes on the main thread once the chunk is actually
 *       ready, whether that was immediate (already loaded) or not.</li>
 *   <li>Reading a chunk's contents happens through one {@link ChunkSnapshot} call (a cheap,
 *       documented-thread-safe copy) instead of 256 separate live {@code World}/{@code Block} API
 *       calls per chunk -- and that snapshot is then handed to a background thread pool for the
 *       actual color-mapping, PNG encoding, and file write, none of which touch Bukkit API and so
 *       none of which need the main thread at all.</li>
 *   <li>A chunk NexusScan had to force-load (nobody else needed it loaded) gets unloaded again
 *       right after its snapshot is taken, so a huge scan never leaves millions of chunks pinned in
 *       memory.</li>
 *   <li>Each tick's dispatch loop stops once it's spent {@link ScanConfig#maxTickMillis()} of
 *       actual measured time (not just a fixed chunk count), so the scan self-throttles under real
 *       server load instead of needing hand-tuning per machine.</li>
 * </ul>
 * On top of all that, tiles are pushed to the Base44/Kairos webhook incrementally as the scan
 * progresses (every {@link ScanConfig#webhookPushBatchChunks()} chunks), not only once the entire
 * visited set has been scanned -- so a partial, growing map appears on the website within minutes
 * even on a multi-million-chunk visited set, filling in outward from spawn as it goes.
 */
public class ScanEngine {

    private final JavaPlugin plugin;
    private final ScanConfig config;
    private final ChunkVisitTracker tracker;
    private final ExecutorService backgroundExecutor;

    private BukkitTask periodicTask;
    private BukkitTask activeCycleTask;
    private volatile boolean ordering;
    private Instant lastScanStarted;
    private Instant lastScanFinished;

    public ScanEngine(JavaPlugin plugin, ScanConfig config, ChunkVisitTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.backgroundExecutor = Executors.newFixedThreadPool(config.backgroundThreads(), runnable -> {
            Thread thread = new Thread(runnable, "NexusScan-render");
            thread.setDaemon(true);
            return thread;
        });
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
        // shutdownNow() rather than a graceful drain: the server is stopping, and an abandoned
        // in-flight tile write just means that chunk gets rescanned (and its file rewritten) next
        // scan -- nothing about it is unsafe to interrupt.
        backgroundExecutor.shutdownNow();
    }

    /** True while a scan is being ordered, actively dispatching, or waiting on in-flight work. */
    public boolean isScanInProgress() {
        return activeCycleTask != null || ordering;
    }

    public Instant lastScanStarted() {
        return lastScanStarted;
    }

    public Instant lastScanFinished() {
        return lastScanFinished;
    }

    /** Returns false (and starts nothing) if a scan is already running (or being ordered). */
    public boolean startCycle() {
        if (isScanInProgress()) return false;

        World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            plugin.getLogger().warning("NexusScan: configured world '" + config.worldName()
                    + "' isn't loaded -- skipping this scan.");
            return false;
        }

        Set<Long> snapshot = tracker.snapshot();
        lastScanStarted = Instant.now();
        plugin.getLogger().info("NexusScan: starting a scan of " + snapshot.size() + " visited chunk(s) in '"
                + world.getName() + "' -- ordering outward from spawn first...");

        int originChunkX = Math.floorDiv(config.scanOriginX(), 16);
        int originChunkZ = Math.floorDiv(config.scanOriginZ(), 16);

        // Sorting a multi-million-entry list is real work -- cheap per element, but not something
        // to do synchronously on the main thread where it'd freeze the server for the duration.
        // None of this touches Bukkit API, so it's safe on a plain background thread; the actual
        // per-chunk dispatch (which does touch the world) gets scheduled back onto the main thread
        // once ordering is done.
        ordering = true;
        Thread orderingThread = new Thread(() -> {
            List<Long> ordered = new ArrayList<>(snapshot);
            ordered.sort(Comparator.comparingLong(key -> distanceSquaredFromOrigin(key, originChunkX, originChunkZ)));

            if (!plugin.isEnabled()) {
                // Server's shutting down / plugin got disabled mid-sort -- nothing left to schedule.
                ordering = false;
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                ordering = false;
                beginScanTicks(world, ordered);
            });
        }, "NexusScan-scan-order");
        orderingThread.setDaemon(true);
        orderingThread.start();

        return true;
    }

    private static long distanceSquaredFromOrigin(long chunkKey, int originChunkX, int originChunkZ) {
        long dx = ChunkVisitTracker.chunkXFromKey(chunkKey) - originChunkX;
        long dz = ChunkVisitTracker.chunkZFromKey(chunkKey) - originChunkZ;
        return dx * dx + dz * dz;
    }

    private record FinishedTile(int chunkX, int chunkZ, BufferedImage image) {
    }

    /** Runs the actual per-tick chunk dispatch, given an already spawn-outward-ordered chunk list. */
    private void beginScanTicks(World world, List<Long> orderedKeys) {
        Deque<Long> queue = new ArrayDeque<>(orderedKeys);
        List<long[]> completedTiles = new ArrayList<>(orderedKeys.size());
        int tilePixelSize = config.tilePixelSize();
        boolean unloadForced = config.unloadForcedChunks();
        IncrementalPyramid pyramid = new IncrementalPyramid(config.webhookMinZoom(), config.webhookMaxZoom(), tilePixelSize);
        int totalChunks = queue.size();

        int chunksPerTick = config.chunksPerTick();
        long maxTickNanos = (long) (config.maxTickMillis() * 1_000_000.0);
        int pushBatchChunks = config.webhookPushBatchChunks();

        ConcurrentLinkedQueue<FinishedTile> finishedQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger inFlight = new AtomicInteger(0);

        activeCycleTask = new BukkitRunnable() {
            int sinceLastPush = 0;

            @Override
            public void run() {
                long tickStart = System.nanoTime();
                int dispatchedThisTick = 0;

                while (dispatchedThisTick < chunksPerTick && !queue.isEmpty()
                        && (maxTickNanos <= 0 || System.nanoTime() - tickStart < maxTickNanos)) {
                    long key = queue.poll();
                    int chunkX = ChunkVisitTracker.chunkXFromKey(key);
                    int chunkZ = ChunkVisitTracker.chunkZFromKey(key);
                    dispatchedThisTick++;

                    if (!world.isChunkGenerated(chunkX, chunkZ)) {
                        continue; // never generated -- correctly represented by simply having no tile
                    }

                    inFlight.incrementAndGet();
                    dispatchChunk(world, chunkX, chunkZ, tilePixelSize, unloadForced, finishedQueue, inFlight);
                }

                // Applying already-finished background work is cheap (no I/O, just updating
                // in-memory maps/lists), so this always runs regardless of the time budget above.
                FinishedTile finished;
                while ((finished = finishedQueue.poll()) != null) {
                    completedTiles.add(new long[]{finished.chunkX(), finished.chunkZ()});
                    pyramid.update(finished.chunkX(), finished.chunkZ(), finished.image());
                    sinceLastPush++;
                }

                boolean allWorkDone = queue.isEmpty() && inFlight.get() == 0;

                // Push whatever's changed so far, either because a full batch's worth of chunks
                // completed or because every chunk is done -- either way, only the delta since the
                // last push is sent, so this stays cheap even after millions of chunks have gone out.
                if ((allWorkDone || sinceLastPush >= pushBatchChunks) && pyramid.hasPendingChanges()) {
                    List<TilePyramid.Tile> delta = pyramid.drainChangedTiles();
                    WebhookPusher.pushAsync(config, delta, plugin.getLogger());
                    if (!allWorkDone) {
                        plugin.getLogger().info("NexusScan: progress -- " + completedTiles.size() + "/" + totalChunks
                                + " chunk(s) scanned so far (spawn-outward order), pushed incrementally to Base44.");
                    }
                    sinceLastPush = 0;
                }

                if (allWorkDone) {
                    int completedCount = completedTiles.size();
                    // The manifest write (a full listing of every tile) also doesn't touch Bukkit
                    // API, so it goes to the background pool too rather than blocking this last tick.
                    backgroundExecutor.submit(() -> TileWriter.writeManifest(config.outputDirectory(),
                            world.getName(), completedTiles, plugin.getLogger()));
                    lastScanFinished = Instant.now();
                    plugin.getLogger().info("NexusScan: scan finished -- " + completedCount
                            + " tile(s) written to " + config.outputDirectory().getPath() + ".");

                    activeCycleTask = null;
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Gets one chunk ready (async, off the main thread if it isn't already loaded) and hands the
     * actual rendering + file write off to {@link #backgroundExecutor} once its snapshot is taken.
     * Decrements {@code inFlight} exactly once no matter which path this takes (success, a null
     * chunk, or a load failure), since {@link #beginScanTicks} waits for it to reach zero.
     */
    private void dispatchChunk(World world, int chunkX, int chunkZ, int tilePixelSize, boolean unloadForced,
                                ConcurrentLinkedQueue<FinishedTile> finishedQueue, AtomicInteger inFlight) {
        boolean wasLoadedBefore = world.isChunkLoaded(chunkX, chunkZ);

        // gen=false: never generate a chunk that doesn't exist (isChunkGenerated was already
        // checked before this was called, but this is the defense-in-depth backstop). Whether the
        // chunk was already loaded or needed a disk read, Paper completes this future on the main
        // thread, so everything in whenComplete below is safe to touch Bukkit API from directly.
        world.getChunkAtAsync(chunkX, chunkZ, false).whenComplete((chunk, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "NexusScan: couldn't load chunk " + chunkX + "," + chunkZ
                        + " for scanning.", error);
                inFlight.decrementAndGet();
                return;
            }
            if (chunk == null) {
                inFlight.decrementAndGet();
                return;
            }

            ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot(true, false, false);
            if (unloadForced && !wasLoadedBefore) {
                world.unloadChunk(chunkX, chunkZ, false);
            }

            backgroundExecutor.submit(() -> {
                try {
                    BufferedImage image = ChunkScanner.renderTile(chunkSnapshot, tilePixelSize);
                    TileWriter.writeTile(config.outputDirectory(), world.getName(), chunkX, chunkZ, image, plugin.getLogger());
                    finishedQueue.add(new FinishedTile(chunkX, chunkZ, image));
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "NexusScan: failed to render/write chunk "
                            + chunkX + "," + chunkZ, e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        });
    }
}
