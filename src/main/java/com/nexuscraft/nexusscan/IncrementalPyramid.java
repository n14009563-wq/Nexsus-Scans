package com.nexuscraft.nexusscan;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The same tile pyramid {@link TilePyramid} builds, but maintained incrementally, one finished
 * chunk at a time, instead of only being buildable once an entire scan has finished.
 * <p>
 * A full rebuild from scratch on every push (the naive way to get "partial progress") would cost
 * roughly O(total visited chunks) every time it ran, which on a server with millions of visited
 * chunks makes frequent partial pushes just as slow as one big push at the end. Instead, feeding
 * one chunk in via {@link #update} only recomputes that chunk's own ancestor chain (one tile per
 * zoom level, a handful of levels) rather than the whole pyramid, so cost stays proportional to
 * chunks scanned, not chunks scanned times how often progress gets pushed.
 * <p>
 * Every zoom level's tiles are kept in memory for the life of one scan cycle so ancestor tiles can
 * be recombined as siblings complete; {@link #drainChangedTiles()} hands back (and clears) just the
 * tiles that changed since the last drain, so each push to the webhook only re-sends what's new.
 */
public final class IncrementalPyramid {

    private final int minZoom;
    private final int maxZoom;
    private final Map<Integer, Map<Long, BufferedImage>> levels = new HashMap<>();
    private final Map<Integer, Set<Long>> changedSinceDrain = new HashMap<>();

    public IncrementalPyramid(int minZoom, int maxZoom) {
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
            levels.put(zoom, new HashMap<>());
            changedSinceDrain.put(zoom, new HashSet<>());
        }
    }

    /** Feeds one newly scanned chunk's finest-zoom tile in and updates every coarser ancestor. */
    public void update(int chunkX, int chunkZ, BufferedImage tile) {
        int x = chunkX;
        int y = chunkZ;
        levels.get(maxZoom).put(TilePyramid.key(x, y), tile);
        changedSinceDrain.get(maxZoom).add(TilePyramid.key(x, y));

        for (int zoom = maxZoom - 1; zoom >= minZoom; zoom--) {
            x = Math.floorDiv(x, 2);
            y = Math.floorDiv(y, 2);
            BufferedImage combined = TilePyramid.combineChildren(levels.get(zoom + 1), x, y);
            levels.get(zoom).put(TilePyramid.key(x, y), combined);
            changedSinceDrain.get(zoom).add(TilePyramid.key(x, y));
        }
    }

    public boolean hasPendingChanges() {
        for (Set<Long> changed : changedSinceDrain.values()) {
            if (!changed.isEmpty()) return true;
        }
        return false;
    }

    /** Returns every tile that changed since the last call (or since construction), then clears that set. */
    public List<TilePyramid.Tile> drainChangedTiles() {
        List<TilePyramid.Tile> out = new ArrayList<>();
        for (int zoom = minZoom; zoom <= maxZoom; zoom++) {
            Map<Long, BufferedImage> level = levels.get(zoom);
            Set<Long> changed = changedSinceDrain.get(zoom);
            for (long key : changed) {
                BufferedImage image = level.get(key);
                if (image == null) continue;
                out.add(new TilePyramid.Tile(zoom, TilePyramid.keyX(key), TilePyramid.keyY(key), image));
            }
            changed.clear();
        }
        return out;
    }
}
