package com.nexuscraft.nexusscan;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.logging.Logger;

public class NexusScanCommand implements CommandExecutor {

    private final ScanConfig config;
    private final ChunkVisitTracker tracker;
    private final ScanEngine engine;
    private final Logger logger;

    /** Set by a plain "/nexusscan backfill" preview, consumed (and cleared) by "... backfill confirm". */
    private Set<Long> pendingBackfill;

    public NexusScanCommand(ScanConfig config, ChunkVisitTracker tracker, ScanEngine engine, Logger logger) {
        this.config = config;
        this.tracker = tracker;
        this.engine = engine;
        this.logger = logger;
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
            case "backfill" -> handleBackfill(sender, args);
            case "resetvisited" -> handleResetVisited(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /nexusscan <status|rescan|reload|backfill|resetvisited>");
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "--- NexusScan status ---");
        sender.sendMessage(ChatColor.GRAY + "World: " + ChatColor.WHITE + config.worldName());
        sender.sendMessage(ChatColor.GRAY + "Visited chunks recorded: " + ChatColor.WHITE + tracker.visitedCount());
        sender.sendMessage(ChatColor.GRAY + "Scan interval: " + ChatColor.WHITE + config.scanIntervalHours() + "h");
        sender.sendMessage(ChatColor.GRAY + "Scan order origin: " + ChatColor.WHITE + config.scanOriginX() + ", "
                + config.scanOriginZ() + " (chunks scanned outward from here first)");
        sender.sendMessage(ChatColor.GRAY + "Scan in progress: " + ChatColor.WHITE + engine.isScanInProgress());
        sender.sendMessage(ChatColor.GRAY + "Last scan started: " + ChatColor.WHITE + describe(engine.lastScanStarted()));
        sender.sendMessage(ChatColor.GRAY + "Last scan finished: " + ChatColor.WHITE + describe(engine.lastScanFinished()));
        sender.sendMessage(ChatColor.GRAY + "Output folder: " + ChatColor.WHITE + config.outputDirectory().getPath());
    }

    private void handleRescan(CommandSender sender) {
        boolean started = engine.startCycle();
        if (started) {
            sender.sendMessage(ChatColor.AQUA + "Scan started -- tiles will start reaching Base44 incrementally "
                    + "as they're scanned; check /nexusscan status for progress.");
        } else {
            sender.sendMessage(ChatColor.RED + "A scan is already running (or the configured world isn't loaded).");
        }
    }

    private void handleReload(CommandSender sender) {
        config.reloadUnderlyingConfig();
        sender.sendMessage(ChatColor.AQUA + "Config reloaded. Note: the scan interval/startup schedule only takes "
                + "effect on the next server restart.");
    }

    /**
     * Two-step on purpose: a plain "/nexusscan backfill" only *reports* how many chunks exist in
     * the world's region files without touching the visited set, because that count can be wildly
     * larger than actual player exploration if another plugin (a world importer, a pregenerator,
     * etc.) generates chunks programmatically. "backfill confirm" commits the count from the most
     * recent preview.
     */
    private void handleBackfill(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            if (pendingBackfill == null) {
                sender.sendMessage(ChatColor.RED + "Run /nexusscan backfill first to see how many chunks it would add.");
                return;
            }
            int added = tracker.seedChunks(pendingBackfill);
            sender.sendMessage(ChatColor.AQUA + "Backfill committed -- " + added + " new chunk(s) added (now "
                    + tracker.visitedCount() + " visited total). Run /nexusscan rescan to render and push them.");
            pendingBackfill = null;
            return;
        }

        World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + config.worldName() + "' isn't loaded.");
            return;
        }
        Set<Long> generated = RegionFileScanner.findGeneratedChunks(world.getWorldFolder(), logger);
        pendingBackfill = generated;
        sender.sendMessage(ChatColor.AQUA + "Found " + generated.size() + " chunk(s) already generated on disk "
                + "(currently " + tracker.visitedCount() + " tracked as visited).");
        sender.sendMessage(ChatColor.YELLOW + "This counts every chunk saved in the region files, not just ones a "
                + "player walked into -- if another plugin generates terrain programmatically, this can be far "
                + "larger than the real explored area.");
        sender.sendMessage(ChatColor.YELLOW + "Run /nexusscan backfill confirm to add them, or leave it alone if "
                + "that number looks too big to be real exploration.");
    }

    /** Two-step and irreversible on purpose -- clears the whole visited set, not just recent additions. */
    private void handleResetVisited(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            int cleared = tracker.visitedCount();
            tracker.resetAll();
            sender.sendMessage(ChatColor.AQUA + "Cleared " + cleared + " visited chunk(s). The map starts over from "
                    + "here -- players exploring, or a fresh /nexusscan backfill, will rebuild it.");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "This permanently erases all " + tracker.visitedCount()
                + " tracked visited chunk(s) (e.g. to undo a backfill that swept up chunks another plugin generated). "
                + "Run /nexusscan resetvisited confirm to actually do it.");
    }

    private String describe(Instant instant) {
        if (instant == null) return "never";
        long secondsAgo = ChronoUnit.SECONDS.between(instant, Instant.now());
        if (secondsAgo < 60) return secondsAgo + "s ago";
        if (secondsAgo < 3600) return (secondsAgo / 60) + "m ago";
        return (secondsAgo / 3600) + "h ago";
    }
}
