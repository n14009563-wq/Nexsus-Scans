package com.nexuscraft.nexusscan;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Renders one chunk's 16x16 surface columns into a tile image -- one color sample per column,
 * colored by that column's topmost non-air block, the same flat idea a vanilla in-game map uses.
 * <p>
 * As of v0.7.0 this reads from a {@link ChunkSnapshot} instead of live {@code World}/{@code Block}
 * API -- a cheap, immutable, thread-safe copy of one chunk's block data (Bukkit's own documented
 * mechanism for reading a chunk's contents off the main thread), so rendering happens entirely on a
 * background thread (see {@link ScanEngine}).
 * <p>
 * As of v0.8.0, columns are relief-shaded (see {@link #applyReliefShading}) rather than left as
 * flat, unmodulated color -- a pure flat-color render looks like a solid-colored blob with nothing
 * new to see no matter how far you zoom in, since a color alone carries no shape information.
 */
public final class ChunkScanner {

    /** Height difference (in blocks) beyond which shading intensity is capped, so a single sheer
     *  cliff or a one-block fence post doesn't flash a jarringly bright/dark pixel. */
    private static final int MAX_SHADED_HEIGHT_DIFF = 6;

    private ChunkScanner() {
    }

    /**
     * @param tilePixelSize          output image width/height in pixels. There's only ever one
     *                               color sample per block column, so a value above 16 doesn't add
     *                               real detail -- it upscales each column into a solid
     *                               {@code tilePixelSize/16}-pixel square with hard edges
     *                               (nearest-neighbor, never blurred/interpolated).
     * @param reliefShadingEnabled   see {@link #applyReliefShading}.
     * @param reliefShadingStrength  brightness change per block of height difference from a
     *                               column's west neighbor, e.g. 0.06 = 6% brighter/darker per
     *                               block of relative elevation (clamped, see
     *                               {@link #MAX_SHADED_HEIGHT_DIFF}).
     */
    public static BufferedImage renderTile(ChunkSnapshot snapshot, int tilePixelSize,
                                            boolean reliefShadingEnabled, double reliefShadingStrength) {
        int pixelsPerBlock = Math.max(1, tilePixelSize / 16);
        int imageSize = pixelsPerBlock * 16;
        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = image.createGraphics();
        try {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int rgb = columnColor(snapshot, localX, localZ, reliefShadingEnabled, reliefShadingStrength);
                    g.setColor(new Color(rgb));
                    g.fillRect(localX * pixelsPerBlock, localZ * pixelsPerBlock, pixelsPerBlock, pixelsPerBlock);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int columnColor(ChunkSnapshot snapshot, int localX, int localZ,
                                    boolean reliefShadingEnabled, double reliefShadingStrength) {
        int highestY = snapshot.getHighestBlockYAt(localX, localZ);
        Material type = snapshot.getBlockType(localX, highestY, localZ);
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            // an all-air column (shouldn't normally happen for a generated chunk, but the void
            // edge of the world or an oddly empty column both land here) -- unrendered per spec,
            // and never shaded (there's no "terrain" here to shade).
            return BlockColorPalette.unrenderedColor();
        }

        int baseColor = BlockColorPalette.colorFor(type);
        if (!reliefShadingEnabled || localX == 0) {
            // Column 0 of every chunk has no west neighbor within this snapshot (that block lives
            // in the neighboring chunk, which isn't loaded for this scan) -- rather than load an
            // extra chunk just to shade one edge column, that column is left flat/unshaded. This
            // is a deliberate trade-off: it avoids doubling how many chunks a scan has to touch
            // (a real lag-budget cost, see ScanEngine), at the cost of a faint, barely-visible
            // seam at chunk boundaries every 16 blocks -- much cheaper than it sounds and far
            // preferable to a flat, textureless map.
            return baseColor;
        }

        return applyReliefShading(baseColor, snapshot, localX, localZ, highestY, reliefShadingStrength);
    }

    /**
     * A cheap stand-in for real terrain shading: brighten a column relative to its west neighbor
     * if the ground rises there, darken it if the ground falls -- the same basic idea Dynmap and
     * BlueMap's own lightweight "flat" render modes use to make a solid-color map actually read as
     * terrain (ridgelines, cliffs, riverbanks, coastlines all become visible as contrast) instead
     * of a flat-colored blob with nothing to see no matter how far you zoom in. This only needs the
     * height data already present in the same chunk's own snapshot -- no extra chunk loads, no
     * extra main-thread work, no lighting/texture data at all.
     */
    private static int applyReliefShading(int rgb, ChunkSnapshot snapshot, int localX, int localZ,
                                           int height, double strength) {
        int westHeight = snapshot.getHighestBlockYAt(localX - 1, localZ);
        int diff = Math.max(-MAX_SHADED_HEIGHT_DIFF, Math.min(MAX_SHADED_HEIGHT_DIFF, height - westHeight));
        double factor = 1.0 + diff * strength;

        int r = clamp255(Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = clamp255(Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = clamp255(Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp255(long value) {
        return (int) Math.max(0, Math.min(255, value));
    }
}
