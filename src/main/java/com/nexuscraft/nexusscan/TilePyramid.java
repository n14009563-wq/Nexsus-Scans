package com.nexuscraft.nexusscan;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds a slippy-map-style multi-zoom tile pyramid out of the finest-resolution per-chunk tiles
 * ChunkScanner produces, to match Base44's ingestWorldTiles contract: tiles addressed by
 * (z, tx, ty), where z = maxZoom is one tile per chunk (matching webhook.tile-blocks-base halved
 * maxZoom times, e.g. 256 >> 4 = 16 blocks = one chunk) and each zoom level out from there covers
 * 2x its predecessor's area per tile. Every tile at every zoom is rendered as a fixed 16x16 pixel
 * PNG -- there's no fixed tile-pixel-size in the webhook spec, so this keeps payloads small and
 * consistent by downsampling instead of growing image dimensions as zoom decreases.
 */
public final class TilePyramid {

    public static final int TILE_PIXELS = 16;

    private TilePyramid() {
    }

    public record Tile(int zoom, int tileX, int tileY, BufferedImage image) {
    }

    /**
     * @param finestTiles chunk-coordinate-keyed 16x16 images, i.e. exactly what ChunkScanner
     *                    produced per visited chunk -- these become the z = maxZoom level directly
     *                    (tileX = chunkX, tileY = chunkZ).
     */
    public static java.util.List<Tile> build(Map<Long, BufferedImage> finestTiles, int minZoom, int maxZoom) {
        java.util.List<Tile> allTiles = new java.util.ArrayList<>();
        Map<Long, BufferedImage> currentLevel = new HashMap<>();

        for (Map.Entry<Long, BufferedImage> entry : finestTiles.entrySet()) {
            int chunkX = ChunkVisitTracker.chunkXFromKey(entry.getKey());
            int chunkZ = ChunkVisitTracker.chunkZFromKey(entry.getKey());
            currentLevel.put(key(chunkX, chunkZ), entry.getValue());
            allTiles.add(new Tile(maxZoom, chunkX, chunkZ, entry.getValue()));
        }

        for (int zoom = maxZoom - 1; zoom >= minZoom; zoom--) {
            Map<Long, BufferedImage> nextLevel = new HashMap<>();
            Set<Long> parentCoords = new HashSet<>();
            for (long childKey : currentLevel.keySet()) {
                int childX = keyX(childKey);
                int childY = keyY(childKey);
                parentCoords.add(key(Math.floorDiv(childX, 2), Math.floorDiv(childY, 2)));
            }

            for (long parentKey : parentCoords) {
                int parentX = keyX(parentKey);
                int parentY = keyY(parentKey);
                BufferedImage combined = combineChildren(currentLevel, parentX, parentY);
                nextLevel.put(parentKey, combined);
                allTiles.add(new Tile(zoom, parentX, parentY, combined));
            }

            currentLevel = nextLevel;
        }

        return allTiles;
    }

    /** Package-private so {@link IncrementalPyramid} can reuse the exact same combine/downsample logic. */
    static BufferedImage combineChildren(Map<Long, BufferedImage> level, int parentX, int parentY) {
        BufferedImage canvas = new BufferedImage(TILE_PIXELS * 2, TILE_PIXELS * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    BufferedImage child = level.get(key(parentX * 2 + dx, parentY * 2 + dy));
                    if (child != null) {
                        g.drawImage(child, dx * TILE_PIXELS, dy * TILE_PIXELS, null);
                    }
                }
            }
        } finally {
            g.dispose();
        }

        BufferedImage downsampled = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D dg = downsampled.createGraphics();
        try {
            dg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            dg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            dg.drawImage(canvas, 0, 0, TILE_PIXELS, TILE_PIXELS, null);
        } finally {
            dg.dispose();
        }
        return downsampled;
    }

    /** Package-private so {@link IncrementalPyramid} can address the same tile-coordinate keying. */
    static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyY(long key) {
        return (int) key;
    }
}
