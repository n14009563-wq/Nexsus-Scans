package com.nexuscraft.nexusscan;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.awt.image.BufferedImage;

/**
 * Reads one chunk's 16x16 surface columns and renders them into a 16x16 image -- one pixel per
 * column, colored by that column's topmost non-air block. This is the same flat idea a vanilla
 * in-game map uses (and the simplest tier of what Dynmap/BlueMap do), deliberately without any
 * lighting/shading pass to keep it cheap: it only ever runs on a schedule, never continuously.
 */
public final class ChunkScanner {

    private ChunkScanner() {
    }

    /**
     * Returns null if the chunk isn't generated (nothing has ever loaded/created it), which the
     * caller renders as the spec's 000000 "unrendered" color instead of guessing at terrain that
     * doesn't exist yet.
     */
    public static BufferedImage scanChunk(World world, int chunkX, int chunkZ) {
        if (!world.isChunkGenerated(chunkX, chunkZ)) {
            return null;
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = baseX + localX;
                int worldZ = baseZ + localZ;
                int rgb = columnColor(world, worldX, worldZ);
                image.setRGB(localX, localZ, 0xFF000000 | rgb);
            }
        }
        return image;
    }

    private static int columnColor(World world, int worldX, int worldZ) {
        Block highest = world.getHighestBlockAt(worldX, worldZ);
        Material type = highest.getType();
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            // an all-air column (shouldn't normally happen for a generated chunk, but the void
            // edge of the world or an oddly empty column both land here) -- unrendered per spec
            return BlockColorPalette.unrenderedColor();
        }
        return BlockColorPalette.colorFor(type);
    }
}
