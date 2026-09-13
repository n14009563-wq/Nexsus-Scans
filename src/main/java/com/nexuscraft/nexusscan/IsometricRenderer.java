package com.nexuscraft.nexusscan;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Renders one chunk as a real isometric "heightfield" tile -- a shaded top face per column plus
 * shaded side wall faces wherever a column is taller than its east ({@code x+1}) or south
 * ({@code z+1}) neighbor -- instead of {@link ChunkScanner}'s flat one-color-per-column square.
 * <p>
 * This is deliberately NOT voxel ray-casting (what Dynmap/BlueMap actually do): it still samples
 * exactly one block per column ({@link ChunkSnapshot#getHighestBlockYAt}), the same cost model
 * {@link ScanEngine} already made lag-safe in v0.7.0 -- no extra chunk loads, no extra
 * main-thread work, nothing that scales worse than the flat renderer it replaces. What changes is
 * only the drawing step: a column's height relative to its neighbors becomes a real visible wall
 * instead of, at best, a shading hint. That means it can show a cliff, a riverbank, or the outside
 * wall of a player-built structure -- it can NOT show what's inside/under an overhang, since there
 * is still no data for anything but the topmost surface of each column. Getting that would need a
 * genuinely heavier renderer that walks real voxel data, not just this projection change.
 * <p>
 * Projection: a standard 2:1 pixel-art isometric diamond per block. Column (x, z) with pixel unit
 * {@code pb} (see {@link #renderTile}) and vertical unit {@code hb = pb / 2.0} maps to a screen
 * center of {@code screenX = (x - z) * pb}, {@code screenY = (x + z) * hb - relativeHeight * hb},
 * where {@code relativeHeight} is this column's height above a fixed, shared {@code baselineY}
 * (see {@link #renderTile}), clamped to {@code [0, heightWindowBlocks]}. Two things matter about
 * that baseline: it keeps canvas size fixed regardless of how tall the terrain in any one chunk
 * happens to be (the same kind of deliberate clamp {@link ChunkScanner} already applies to
 * relief-shading intensity, applied here to actual geometry instead) -- and, critically, it's the
 * SAME baseline for every chunk, not each chunk's own local minimum. That's what makes
 * neighboring tiles composable into one continuous map: two adjacent chunks' tiles only line up
 * at the right relative height if "relativeHeight 0" means the same absolute Y in both of them.
 * Columns are drawn back-to-front in order of increasing {@code x + z} (the standard heightfield
 * painter's algorithm) so a nearer column's top face correctly overdraws a farther column's wall
 * exactly where it should.
 */
public final class IsometricRenderer {

    /** Two-tone side shading, darker than the top face -- same fixed-multiplier idea Overviewer-
     *  style isometric Minecraft renderers use since there's no real per-face lighting data, just
     *  one color per block. Two different multipliers (rather than one) is what actually reads as
     *  a 3D corner rather than a flat cutout. */
    private static final double EAST_FACE_SHADE = 0.55;
    private static final double SOUTH_FACE_SHADE = 0.72;

    private IsometricRenderer() {
    }

    public record Bounds(int width, int height, int xOffset, int yOffset) {
    }

    /**
     * @param pixelsPerBlock    horizontal pixel unit -- the top diamond is {@code 2 * pixelsPerBlock}
     *                          wide and {@code pixelsPerBlock} tall; walls use half that as their
     *                          per-block vertical unit. Bigger = a larger, crisper tile.
     * @param baselineY         the world Y that maps to the bottom of the height window (relative
     *                          height 0) -- MUST be the same value for every chunk in a scan (a
     *                          fixed config value, not derived per-chunk), so neighboring tiles'
     *                          "floors" line up at the correct relative height when composited into
     *                          one map instead of each chunk floating at its own arbitrary baseline.
     * @param heightWindowBlocks how many blocks above (and, implicitly, at) baselineY get real
     *                          vertical room on the canvas -- a column below baselineY is clamped up
     *                          to it, a column more than heightWindowBlocks above it is clamped down
     *                          to the top of the window. Keeps every tile the exact same fixed size
     *                          regardless of terrain, at the cost of losing visible relief for
     *                          terrain far outside the window (still gets a top face, just flattened
     *                          to the nearest edge of the window instead of its true height).
     */
    public static BufferedImage renderTile(ChunkSnapshot snapshot, int pixelsPerBlock, int baselineY,
                                            int heightWindowBlocks) {
        return renderTile(snapshot, pixelsPerBlock, baselineY, heightWindowBlocks, null);
    }

    /**
     * Same as {@link #renderTile(ChunkSnapshot, int, int, int)}, but draws each face with its real
     * block texture (see {@link TextureAtlas}) wherever one is known for that column's material,
     * instead of a flat representative color -- the actual texture image is affine-mapped onto the
     * diamond (top face) or tiled once per block of drop (wall faces), then shaded the same way a
     * flat color would be. A material with no known texture (or a null {@code textures}) falls back
     * to the original flat-color fill for that face, so this is always safe to call even before/
     * without real textures being available.
     */
    public static BufferedImage renderTile(ChunkSnapshot snapshot, int pixelsPerBlock, int baselineY,
                                            int heightWindowBlocks, TextureAtlas textures) {
        int[][] heights = new int[16][16];
        boolean[][] isAir = new boolean[16][16];

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = snapshot.getHighestBlockYAt(x, z);
                heights[x][z] = y;
                Material type = snapshot.getBlockType(x, y, z);
                isAir[x][z] = (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR);
            }
        }

        int window = Math.max(1, heightWindowBlocks);
        int[][] relHeight = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int rel = heights[x][z] - baselineY;
                relHeight[x][z] = Math.max(0, Math.min(rel, window));
            }
        }

        Bounds bounds = computeBounds(pixelsPerBlock, window);
        BufferedImage image = new BufferedImage(bounds.width(), bounds.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            for (int depth = 0; depth <= 30; depth++) {
                for (int x = 0; x < 16; x++) {
                    int z = depth - x;
                    if (z < 0 || z > 15) continue;
                    drawColumn(g, snapshot, x, z, heights, isAir, relHeight, pixelsPerBlock, bounds, textures);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Exposed for tests: exact canvas size for a given pixel unit / height window, computed
     *  analytically (not by scanning drawn pixels) so it's always fixed ahead of time. */
    public static Bounds computeBounds(int pixelsPerBlock, int heightWindowBlocks) {
        double pb = pixelsPerBlock;
        double hb = pb / 2.0;
        int window = Math.max(1, heightWindowBlocks);

        double minRawX = -15 * pb;
        double maxRawX = 15 * pb;
        double xOffset = -minRawX + pb; // + diamond half-width margin

        double minRawY = 0 - window * hb; // depth 0, at the top of the height window
        double maxRawY = 30 * hb;         // depth 30 (x=15,z=15), at the bottom of the window
        double yOffset = -minRawY + hb;   // + diamond half-height margin

        int width = (int) Math.ceil(maxRawX + xOffset + pb) + 1;
        int height = (int) Math.ceil(maxRawY + yOffset + hb) + 1;
        return new Bounds(width, height, (int) Math.round(xOffset), (int) Math.round(yOffset));
    }

    private static void drawColumn(Graphics2D g, ChunkSnapshot snapshot, int x, int z, int[][] heights,
                                    boolean[][] isAir, int[][] relHeight, int pixelsPerBlock, Bounds bounds,
                                    TextureAtlas textures) {
        if (isAir[x][z]) {
            return; // nothing to draw for an unrendered column -- no top face, no walls off it
        }
        double pb = pixelsPerBlock;
        double hb = pb / 2.0;
        double cx = (x - z) * pb + bounds.xOffset();
        double cy = (x + z) * hb - relHeight[x][z] * hb + bounds.yOffset();

        Material type = snapshot.getBlockType(x, heights[x][z], z);
        String materialName = type.name();
        int baseColor = BlockColorPalette.colorFor(type);

        // East wall: toward x+1. Only drawn if this column is taller than that neighbor, and only
        // if that neighbor exists in this snapshot (x < 15) and isn't itself an unrendered column.
        if (x < 15 && !isAir[x + 1][z] && relHeight[x][z] > relHeight[x + 1][z]) {
            int dropBlocks = relHeight[x][z] - relHeight[x + 1][z];
            drawWall(g, cx, cy, pb, hb, dropBlocks, true, shade(baseColor, EAST_FACE_SHADE),
                    textures, materialName, EAST_FACE_SHADE);
        }
        // South wall: toward z+1. Same idea, other axis.
        if (z < 15 && !isAir[x][z + 1] && relHeight[x][z] > relHeight[x][z + 1]) {
            int dropBlocks = relHeight[x][z] - relHeight[x][z + 1];
            drawWall(g, cx, cy, pb, hb, dropBlocks, false, shade(baseColor, SOUTH_FACE_SHADE),
                    textures, materialName, SOUTH_FACE_SHADE);
        }

        drawTopDiamond(g, cx, cy, pb, hb, baseColor, textures, materialName);
    }

    private static void drawTopDiamond(Graphics2D g, double cx, double cy, double pb, double hb, int rgb,
                                        TextureAtlas textures, String materialName) {
        // Left, top, bottom vertices (in that order) are the 3 points a texture's (0,0)/(w,0)/(0,h)
        // corners map onto -- see drawTexturedParallelogram. The 4th (right) vertex follows
        // automatically since this is a parallelogram, both for the polygon and the texture map.
        double leftX = cx - pb, leftY = cy;
        double topX = cx, topY = cy - hb;
        double bottomX = cx, bottomY = cy + hb;

        if (textures != null) {
            BufferedImage texture = textures.shaded(materialName, true, 1.0);
            if (texture != null) {
                drawTexturedParallelogram(g, texture, leftX, leftY, topX, topY, bottomX, bottomY);
                return;
            }
        }

        Polygon diamond = new Polygon();
        diamond.addPoint((int) Math.round(topX), (int) Math.round(topY));
        diamond.addPoint((int) Math.round(cx + pb), (int) Math.round(cy));           // right
        diamond.addPoint((int) Math.round(bottomX), (int) Math.round(bottomY));
        diamond.addPoint((int) Math.round(leftX), (int) Math.round(leftY));
        g.setColor(new Color(rgb));
        g.fillPolygon(diamond);
    }

    /**
     * @param east true for the east face (bottom vertex -> right vertex), false for the south face
     *             (left vertex -> bottom vertex) -- see the class docs for why these two edges.
     */
    private static void drawWall(Graphics2D g, double cx, double cy, double pb, double hb,
                                  int dropBlocks, boolean east, int rgb,
                                  TextureAtlas textures, String materialName, double shadeFactor) {
        double bx = cx, by = cy + hb;                 // bottom vertex
        double ox, oy;                                 // the "outer" vertex (right for east, left for south)
        if (east) {
            ox = cx + pb;
            oy = cy;
        } else {
            ox = cx - pb;
            oy = cy;
        }

        BufferedImage texture = textures != null ? textures.shaded(materialName, false, shadeFactor) : null;
        if (texture != null) {
            // One texture tile per block of drop, stacked top to bottom -- matches how a real
            // cliff face in-game repeats its side texture once per block, rather than stretching
            // one copy over the whole visible height.
            for (int i = 0; i < dropBlocks; i++) {
                double topOffset = i * hb;
                double bottomOffset = (i + 1) * hb;
                drawTexturedParallelogram(g, texture,
                        bx, by + topOffset, ox, oy + topOffset, bx, by + bottomOffset);
            }
            return;
        }

        double dropPixels = dropBlocks * hb;
        Polygon wall = new Polygon();
        wall.addPoint((int) Math.round(bx), (int) Math.round(by));
        wall.addPoint((int) Math.round(ox), (int) Math.round(oy));
        wall.addPoint((int) Math.round(ox), (int) Math.round(oy + dropPixels));
        wall.addPoint((int) Math.round(bx), (int) Math.round(by + dropPixels));
        g.setColor(new Color(rgb));
        g.fillPolygon(wall);
    }

    /**
     * Draws {@code texture} skewed so its corners (0,0), (w,0), (0,h) land exactly on
     * (originX,originY), (uX,uY), (vX,vY) -- the 4th corner (w,h) follows automatically since an
     * affine map preserves parallelograms, which is exactly the shape every face here is (a top
     * diamond is two such parallelograms sharing an edge in spirit, but is drawn whole in one call
     * since {@code left/top/bottom} already define it uniquely).
     */
    private static void drawTexturedParallelogram(Graphics2D g, BufferedImage texture,
                                                    double originX, double originY,
                                                    double uX, double uY, double vX, double vY) {
        int texW = texture.getWidth();
        int texH = texture.getHeight();
        if (texW <= 0 || texH <= 0) return;

        double m00 = (uX - originX) / texW;
        double m10 = (uY - originY) / texW;
        double m01 = (vX - originX) / texH;
        double m11 = (vY - originY) / texH;
        AffineTransform transform = new AffineTransform(m00, m10, m01, m11, originX, originY);
        g.drawImage(texture, transform, null);
    }

    private static int shade(int rgb, double factor) {
        int r = (int) Math.round(((rgb >> 16) & 0xFF) * factor);
        int g2 = (int) Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = (int) Math.round((rgb & 0xFF) * factor);
        r = Math.max(0, Math.min(255, r));
        g2 = Math.max(0, Math.min(255, g2));
        b = Math.max(0, Math.min(255, b));
        return (r << 16) | (g2 << 8) | b;
    }
}
