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

    public int chunksPerTick() {
        return Math.max(1, plugin.getConfig().getInt("chunks-per-tick", 10));
    }

    public File outputDirectory() {
        String configured = plugin.getConfig().getString("output-directory", "web");
        return new File(plugin.getDataFolder(), configured);
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
