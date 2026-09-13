package com.nexuscraft.nexusscan;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;

public class NexusScanPlugin extends JavaPlugin {

    private ChunkVisitTracker tracker;
    private ScanEngine engine;

    @Override
    public void onEnable() {
        ScanConfig config = new ScanConfig(this);

        tracker = new ChunkVisitTracker(this, config);
        tracker.load();
        getServer().getPluginManager().registerEvents(tracker, this);

        runBackfillIfConfigured(config);

        engine = new ScanEngine(this, config, tracker);
        engine.startSchedule();
        startTextureLoadIfConfigured(config, engine);

        NexusScanCommand command = new NexusScanCommand(config, tracker, engine, getLogger());
        getCommand("nexusscan").setExecutor(command);

        getLogger().info("NexusScan enabled -- tracking " + tracker.visitedCount() + " visited chunk(s) in '"
                + config.worldName() + "', scanning every " + config.scanIntervalHours() + "h.");
    }

    /**
     * One-time-per-startup catch-up for a server that already has years of exploration on disk
     * before this plugin ever existed: the world's own region files already record every chunk
     * that's ever been generated (see RegionFileScanner), so there's no need to wait for players
     * to re-walk ground they've already covered before it shows up on the map.
     */
    private void runBackfillIfConfigured(ScanConfig config) {
        if (!config.backfillEnabled()) return;

        World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            getLogger().warning("NexusScan: couldn't backfill from existing region files -- world '"
                    + config.worldName() + "' isn't loaded yet.");
            return;
        }

        Set<Long> generated = RegionFileScanner.findGeneratedChunks(world.getWorldFolder(), getLogger());
        int added = tracker.seedChunks(generated);
        getLogger().info("NexusScan: backfill found " + generated.size() + " already-generated chunk(s) on disk ("
                + added + " new).");
    }

    /**
     * Kicks off the one-time (per configured Minecraft version) vanilla texture download in the
     * background -- see {@link MinecraftAssetDownloader} for why this is safe to fire off and
     * forget: it never touches the main thread, and any failure just leaves isometric rendering on
     * flat colors rather than breaking anything. Does nothing at all if isometric rendering or
     * real textures are turned off in config.yml.
     */
    private void startTextureLoadIfConfigured(ScanConfig config, ScanEngine engine) {
        if (!config.isometricRenderingEnabled() || !config.isometricUseRealTextures()) return;

        File cacheDirectory = new File(getDataFolder(), "texture-cache");
        MinecraftAssetDownloader.loadAsync(cacheDirectory, config.isometricMinecraftVersion(), getLogger(),
                engine::setTextureAtlas);
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.stop();
        if (tracker != null) tracker.close();
    }
}
