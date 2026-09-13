package com.nexuscraft.nexusscan;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ScanConfig {

    private final JavaPlugin plugin;

    public ScanConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    public String worldName() {
        return plugin.getConfig().getString("world", "world");
    }

    public int scanIntervalHours() {
        return Math.max(1, plugin.getConfig().getInt("scan-interval-hours", 12));
    }

    public boolean scanOnStartup() {
        return plugin.getConfig().getBoolean("scan-on-startup", true);
    }

    public int startupScanDelaySeconds() {
        return Math.max(0, plugin.getConfig().getInt("startup-scan-delay-seconds", 60));
    }

    /**
     * Hard ceiling on how many chunks a single tick will ever dispatch, regardless of
     * {@link #maxTickMillis()}. As of v0.7.0 dispatching a chunk is cheap (kick off an async chunk
     * load, or use one already loaded) rather than doing the actual scan work synchronously, so
     * {@link #maxTickMillis()} is what actually limits main-thread impact in practice -- this is
     * just a sanity backstop against dispatching an unreasonable number of chunks in one go.
     */
    public int chunksPerTick() {
        return Math.max(1, plugin.getConfig().getInt("chunks-per-tick", 200));
    }

    /**
     * How many milliseconds of a tick's ~50ms budget a scan is allowed to spend dispatching chunks
     * before it stops and waits for the next tick, measured with {@code System.nanoTime()} against
     * actual elapsed time rather than assuming a fixed chunk count is always cheap. This is what
     * makes the scan self-limiting under real load -- if the server tick is already busy (or
     * running on weaker hardware), NexusScan automatically backs off instead of needing per-server
     * tuning. 0 disables the time check and falls back to {@link #chunksPerTick()} alone.
     */
    public double maxTickMillis() {
        return Math.max(0.0, plugin.getConfig().getDouble("max-tick-millis", 3.0));
    }

    /**
     * Size of the background thread pool that does the actual per-chunk work (rendering a tile
     * from a chunk snapshot, encoding and writing the PNG) -- none of this touches Bukkit API, so
     * none of it needs to run on the main thread. Kept small by default since this is background,
     * not time-critical, work; raise it on a server with cores to spare if scans are I/O-bound.
     */
    public int backgroundThreads() {
        return Math.max(1, plugin.getConfig().getInt("background-threads", 2));
    }

    /**
     * If a chunk being scanned wasn't already loaded (nobody nearby, no other reason to keep it in
     * memory), unload it again immediately after taking its snapshot. Without this, a scan across
     * millions of visited chunks would leave millions of chunks sitting in memory that nobody
     * actually needs loaded, which is its own path to server-wide lag (memory pressure, GC pauses)
     * even with every other optimization in place. Default on; chunks NexusScan didn't force-load
     * (a player's nearby, another plugin has a loading ticket, etc.) are never touched.
     */
    public boolean unloadForcedChunks() {
        return plugin.getConfig().getBoolean("unload-forced-chunks", true);
    }

    public File outputDirectory() {
        String configured = plugin.getConfig().getString("output-directory", "web");
        return new File(plugin.getDataFolder(), configured);
    }

    /**
     * Pixel width/height every tile (local files and the Base44 push alike) is rendered at.
     * There's only one color sample per block column, so this doesn't add real detail above 16 --
     * it controls how big a hard-edged, nearest-neighbor upscale of that data the website receives,
     * which is what actually matters for how blurry/crisp it looks once the site scales it further.
     * Always rounded to a multiple of 16 (blocks per chunk) so every block maps to a whole number
     * of pixels with no partial-pixel seams.
     */
    public int tilePixelSize() {
        int configured = Math.max(16, plugin.getConfig().getInt("tile-pixel-size", 64));
        return (configured / 16) * 16;
    }

    /**
     * Block X coordinate a scan is ordered outward from (see {@link ScanEngine}) -- not
     * necessarily the world's live spawn point, since that can be changed with /setworldspawn;
     * this is a fixed reference point set once in config.yml. Defaults to this user's actual world
     * spawn at the time this was set up.
     */
    public int scanOriginX() {
        return plugin.getConfig().getInt("scan-origin-x", -16649);
    }

    /** See {@link #scanOriginX()}. */
    public int scanOriginZ() {
        return plugin.getConfig().getInt("scan-origin-z", 9645);
    }

    public boolean backfillEnabled() {
        return plugin.getConfig().getBoolean("backfill-on-startup", false);
    }

    public boolean webhookEnabled() {
        return plugin.getConfig().getBoolean("webhook.enabled", true);
    }

    public String webhookBaseUrl() {
        return plugin.getConfig().getString("webhook.base-url", "");
    }

    public String webhookPath() {
        return plugin.getConfig().getString("webhook.path", "/functions/ingestWorldTiles");
    }

    public String webhookSecret() {
        return plugin.getConfig().getString("webhook.secret", "");
    }

    public int webhookMinZoom() {
        return Math.max(0, plugin.getConfig().getInt("webhook.min-zoom", 0));
    }

    public int webhookMaxZoom() {
        return Math.max(webhookMinZoom(), plugin.getConfig().getInt("webhook.max-zoom", 4));
    }

    public int webhookTileBlocksBase() {
        return Math.max(1, plugin.getConfig().getInt("webhook.tile-blocks-base", 256));
    }

    public int webhookTilesPerRequest() {
        return Math.max(1, plugin.getConfig().getInt("webhook.tiles-per-request", 200));
    }

    /**
     * How many successfully-scanned chunks to process before pushing everything that's changed to
     * the Base44 webhook, instead of waiting for the entire scan to finish. See {@link ScanEngine}.
     */
    public int webhookPushBatchChunks() {
        return Math.max(1, plugin.getConfig().getInt("webhook.push-batch-chunks", 2000));
    }

    /**
     * Re-reads config.yml from disk. Values read fresh every call (worldName(), chunksPerTick(),
     * etc. all go straight to plugin.getConfig()) pick this up immediately; the scan schedule
     * itself (interval/startup delay) was already used to set up a fixed-period repeating task at
     * enable time, so a config change to those two only takes effect on the next restart.
     */
    public void reloadUnderlyingConfig() {
        plugin.reloadConfig();
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }
}
