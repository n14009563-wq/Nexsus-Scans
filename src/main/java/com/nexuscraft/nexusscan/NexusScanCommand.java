package com.nexuscraft.nexusscan;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class NexusScanCommand implements CommandExecutor {

    private final ScanConfig config;
    private final ChunkVisitTracker tracker;
    private final ScanEngine engine;

    public NexusScanCommand(ScanConfig config, ChunkVisitTracker tracker, ScanEngine engine) {
        this.config = config;
        this.tracker = tracker;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nexusscan.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> handleStatus(sender);
            case "rescan" -> handleRescan(sender);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /nexusscan <status|rescan|reload>");
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "--- NexusScan status ---");
        sender.sendMessage(ChatColor.GRAY + "World: " + ChatColor.WHITE + config.worldName());
        sender.sendMessage(ChatColor.GRAY + "Visited chunks recorded: " + ChatColor.WHITE + tracker.visitedCount());
        sender.sendMessage(ChatColor.GRAY + "Scan interval: " + ChatColor.WHITE + config.scanIntervalHours() + "h");
        sender.sendMessage(ChatColor.GRAY + "Scan in progress: " + ChatColor.WHITE + engine.isScanInProgress());
        sender.sendMessage(ChatColor.GRAY + "Last scan started: " + ChatColor.WHITE + describe(engine.lastScanStarted()));
        sender.sendMessage(ChatColor.GRAY + "Last scan finished: " + ChatColor.WHITE + describe(engine.lastScanFinished()));
        sender.sendMessage(ChatColor.GRAY + "Output folder: " + ChatColor.WHITE + config.outputDirectory().getPath());
    }

    private void handleRescan(CommandSender sender) {
        boolean started = engine.startCycle();
        if (started) {
            sender.sendMessage(ChatColor.AQUA + "Scan started -- check /nexusscan status for progress.");
        } else {
            sender.sendMessage(ChatColor.RED + "A scan is already running (or the configured world isn't loaded).");
        }
    }

    private void handleReload(CommandSender sender) {
        config.reloadUnderlyingConfig();
        sender.sendMessage(ChatColor.AQUA + "Config reloaded. Note: the scan interval/startup schedule only takes "
                + "effect on the next server restart.");
    }

    private String describe(Instant instant) {
        if (instant == null) return "never";
        long secondsAgo = ChronoUnit.SECONDS.between(instant, Instant.now());
        if (secondsAgo < 60) return secondsAgo + "s ago";
        if (secondsAgo < 3600) return (secondsAgo / 60) + "m ago";
        return (secondsAgo / 3600) + "h ago";
    }
}
