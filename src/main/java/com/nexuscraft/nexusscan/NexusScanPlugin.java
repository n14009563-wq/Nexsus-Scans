package com.nexuscraft.nexusscan;

import org.bukkit.plugin.java.JavaPlugin;

public class NexusScanPlugin extends JavaPlugin {

    private ChunkVisitTracker tracker;
    private ScanEngine engine;

    @Override
    public void onEnable() {
        ScanConfig config = new ScanConfig(this);

        tracker = new ChunkVisitTracker(this, config);
        tracker.load();
        getServer().getPluginManager().registerEvents(tracker, this);

        engine = new ScanEngine(this, config, tracker);
        engine.startSchedule();

        NexusScanCommand command = new NexusScanCommand(config, tracker, engine);
        getCommand("nexusscan").setExecutor(command);

        getLogger().info("NexusScan enabled -- tracking visited chunks in '" + config.worldName()
                + "', scanning every " + config.scanIntervalHours() + "h.");
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.stop();
        if (tracker != null) tracker.close();
    }
}
