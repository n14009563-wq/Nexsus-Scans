package com.nexuscraft.nexusscan;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Renders one chunk's 16x16 surface columns into a tile image -- one color sample per column,
 * colored by that column's topmost non-air block. This is the same flat idea a vanilla in-game map
 * uses (and the simplest tier of what Dynmap/BlueMap do), deliberately without any lighting/shading
 * pass to keep it cheap.
 * <p>
 * As of v0.7.0 this reads from a {@link ChunkSnapshot} instead of live {@code World}/{@code Block}
 * API. A snapshot is a cheap, immutable, thread-safe copy of one chunk's block data -- Bukkit's own
 * documented mechanism for exactly this "read a chunk's contents from off the main thread" use
 * case -- so rendering can happen on a background thread (see {@link ScanEngine}) instead of
 * competing with the rest of the server for main-thread time. Taking the snapshot itself still has
 * to happen on the main thread (it's the only genuinely required main-thread step per chunk), but
 * it's one cheap call instead of 256 separate live block lookups.
 */
public final class ChunkScanner {

    private ChunkScanner() {
    }

    /**
     * @param tilePixelSize output image width/height in pixels. There's only ever one color sample
     *                      per block column, so a value above 16 doesn't add real detail -- it
     *                      upscales each column into a solid {@code tilePixelSize/16}-pixel square
     *                      with hard edges (nearest-neighbor, never blurred/interpolated).
     */
    public static BufferedImage renderTile(ChunkSnapshot snapshot, int tilePixelSize) {
        int pixelsPerBlock = Math.max(1, tilePixelSize / 16);
        int imageSize = pixelsPerBlock * 16;
        BufferedImage image = new BufferedImage(imageSize, imageSize, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = image.createGraphics();
        try {
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int rgb = columnColor(snapshot, localX, localZ);
                    g.setColor(new Color(rgb));
                    g.fillRect(localX * pixelsPerBlock, localZ * pixelsPerBlock, pixelsPerBlock, pixelsPerBlock);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int columnColor(ChunkSnapshot snapshot, int localX, int localZ) {
        int highestY = snapshot.getHighestBlockYAt(localX, localZ);
        Material type = snapshot.getBlockType(localX, highestY, localZ);
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            // an all-air column (shouldn't normally happen for a generated chunk, but the void
            // edge of the world or an oddly empty column both land here) -- unrendered per spec
            return BlockColorPalette.unrenderedColor();
        }
        return BlockColorPalette.colorFor(type);
    }
}
