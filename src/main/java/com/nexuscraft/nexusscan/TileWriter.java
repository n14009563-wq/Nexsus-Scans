package com.nexuscraft.nexusscan;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes one 16x16 PNG per scanned chunk (Dynmap/BlueMap's own tile-per-region idea, just at the
 * simplest possible granularity -- one tile IS one chunk, no multi-zoom pyramid) plus a
 * manifest.json describing what exists, so a website doesn't have to guess tile coordinates or
 * probe for 404s to find out what's been rendered.
 */
public final class TileWriter {

    private TileWriter() {
    }

    public static void writeTile(File outputDirectory, String worldName, int chunkX, int chunkZ,
                                  BufferedImage image, Logger logger) {
        File tilesDir = new File(new File(outputDirectory, "tiles"), worldName);
        //noinspection ResultOfMethodCallIgnored
        tilesDir.mkdirs();
        File tileFile = new File(tilesDir, chunkX + "_" + chunkZ + ".png");
        try {
            ImageIO.write(image, "png", tileFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Couldn't write tile " + tileFile.getName(), e);
        }
    }

    public static void writeManifest(File outputDirectory, String worldName, List<long[]> tileCoords,
                                      Logger logger) {
        writeManifest(outputDirectory, worldName, tileCoords, null, logger);
    }

    /**
     * @param isometric when non-null, the manifest describes isometric tiles (real projection
     *                  parameters the site needs to place each chunk's image -- see
     *                  {@link IsometricRenderer}) instead of the legacy flat renderer's fixed
     *                  16-block tile size.
     */
    public static void writeManifest(File outputDirectory, String worldName, List<long[]> tileCoords,
                                      WebhookPusher.IsometricManifest isometric, Logger logger) {
        //noinspection ResultOfMethodCallIgnored
        outputDirectory.mkdirs();

        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;
        for (long[] coord : tileCoords) {
            int x = (int) coord[0];
            int z = (int) coord[1];
            minChunkX = Math.min(minChunkX, x);
            maxChunkX = Math.max(maxChunkX, x);
            minChunkZ = Math.min(minChunkZ, z);
            maxChunkZ = Math.max(maxChunkZ, z);
        }
        if (tileCoords.isEmpty()) {
            minChunkX = 0;
            maxChunkX = 0;
            minChunkZ = 0;
            maxChunkZ = 0;
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"world\": \"").append(escape(worldName)).append("\",\n");
        json.append("  \"lastScan\": \"").append(DateTimeFormatter.ISO_INSTANT.format(Instant.now())).append("\",\n");
        if (isometric != null) {
            json.append("  \"projection\": \"isometric\",\n");
            json.append("  \"pixelsPerBlock\": ").append(isometric.pixelsPerBlock()).append(",\n");
            json.append("  \"baselineY\": ").append(isometric.baselineY()).append(",\n");
            json.append("  \"heightWindowBlocks\": ").append(isometric.heightWindowBlocks()).append(",\n");
            json.append("  \"tileWidth\": ").append(isometric.tileWidth()).append(",\n");
            json.append("  \"tileHeight\": ").append(isometric.tileHeight()).append(",\n");
        } else {
            json.append("  \"projection\": \"flat\",\n");
            json.append("  \"tileSize\": 16,\n");
        }
        json.append("  \"tileCount\": ").append(tileCoords.size()).append(",\n");
        json.append("  \"bounds\": {\n");
        json.append("    \"minChunkX\": ").append(minChunkX).append(",\n");
        json.append("    \"maxChunkX\": ").append(maxChunkX).append(",\n");
        json.append("    \"minChunkZ\": ").append(minChunkZ).append(",\n");
        json.append("    \"maxChunkZ\": ").append(maxChunkZ).append("\n");
        json.append("  },\n");
        json.append("  \"tilePathPattern\": \"tiles/").append(escape(worldName)).append("/{x}_{z}.png\",\n");
        json.append("  \"tiles\": [\n");
        for (int i = 0; i < tileCoords.size(); i++) {
            long[] coord = tileCoords.get(i);
            json.append("    {\"x\": ").append(coord[0]).append(", \"z\": ").append(coord[1]).append("}");
            json.append(i < tileCoords.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ]\n");
        json.append("}\n");

        File manifestFile = new File(outputDirectory, "manifest.json");
        try {
            java.nio.file.Files.writeString(manifestFile.toPath(), json.toString());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Couldn't write manifest.json", e);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
