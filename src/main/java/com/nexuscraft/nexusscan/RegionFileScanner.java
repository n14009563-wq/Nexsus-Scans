package com.nexuscraft.nexusscan;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads just the header (the first 4KB) of every Anvil region file
 * (&lt;world folder&gt;/region/r.&lt;regionX&gt;.&lt;regionZ&gt;.mca) to find every chunk that's
 * ever been generated and saved to disk -- a cheap way to seed "everywhere anyone has ever
 * explored" retroactively, without touching Bukkit's chunk-loading API at all and without needing
 * this plugin to have been installed for that exploration to have counted. A chunk exists in a
 * region file at all only because something (almost always a nearby player, in normal survival
 * play with no world pregenerator) caused it to generate at some point in the past -- the same
 * signal Dynmap/BlueMap rely on for their own "render everything that already exists" default.
 * <p>
 * The region file format's first 4096 bytes are 1024 four-byte big-endian entries (one per chunk
 * in the region's 32x32 grid, index = localX + localZ*32): the first three bytes are a sector
 * offset and the fourth a sector count. An all-zero entry means that chunk was never saved. That's
 * all this needs to know -- there's no reason to parse the actual chunk data (terrain, NBT, etc.)
 * just to answer "does this chunk exist."
 */
public final class RegionFileScanner {

    private static final Pattern REGION_FILE_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final int HEADER_BYTES = 4096;

    private RegionFileScanner() {
    }

    public static Set<Long> findGeneratedChunks(File worldFolder, Logger logger) {
        Set<Long> chunks = new HashSet<>();
        File regionFolder = new File(worldFolder, "region");
        File[] regionFiles = regionFolder.listFiles();
        if (regionFiles == null) return chunks;

        for (File regionFile : regionFiles) {
            Matcher matcher = REGION_FILE_NAME.matcher(regionFile.getName());
            if (!matcher.matches()) continue;

            int regionX = Integer.parseInt(matcher.group(1));
            int regionZ = Integer.parseInt(matcher.group(2));
            readRegionHeader(regionFile, regionX, regionZ, chunks, logger);
        }
        return chunks;
    }

    private static void readRegionHeader(File regionFile, int regionX, int regionZ, Set<Long> out, Logger logger) {
        byte[] header = new byte[HEADER_BYTES];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "r")) {
            int totalRead = 0;
            while (totalRead < HEADER_BYTES) {
                int read = raf.read(header, totalRead, HEADER_BYTES - totalRead);
                if (read < 0) break;
                totalRead += read;
            }
            if (totalRead < HEADER_BYTES) return; // truncated/empty region file -- nothing usable
        } catch (IOException e) {
            logger.log(Level.WARNING, "NexusScan: couldn't read region file " + regionFile.getName()
                    + " during backfill.", e);
            return;
        }

        for (int localZ = 0; localZ < 32; localZ++) {
            for (int localX = 0; localX < 32; localX++) {
                int offset = (localX + localZ * 32) * 4;
                boolean present = header[offset] != 0 || header[offset + 1] != 0
                        || header[offset + 2] != 0 || header[offset + 3] != 0;
                if (!present) continue;

                int chunkX = (regionX << 5) + localX;
                int chunkZ = (regionZ << 5) + localZ;
                out.add(ChunkVisitTracker.key(chunkX, chunkZ));
            }
        }
    }
}
