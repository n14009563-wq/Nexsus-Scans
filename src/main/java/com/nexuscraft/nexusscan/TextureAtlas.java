package com.nexuscraft.nexusscan;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the real per-material block textures extracted from the vanilla client jar (see
 * {@link MinecraftAssetDownloader}), keyed by {@code Material.name()}, one map for the top face
 * and one for the four side faces -- most blocks use the same image for both (a single texture
 * file covers every face), so the loader fills both maps with that same image for those materials;
 * only ones with a genuinely distinct top texture (grass_block, logs, etc.) get two different
 * images. Callers never need fallback logic here as a result -- a missing entry just means this
 * material has no known texture at all (an unrecognized or oddly-named block), not "check the
 * other map."
 * <p>
 * Grass-block-top and every {@code *_LEAVES} material are biome-tinted in the real game -- their
 * raw texture file is a neutral/grayscale mask meant to be multiplied by a per-biome color, which
 * would mean implementing Minecraft's actual biome color-map system to get exactly right. Instead,
 * {@link MinecraftAssetDownloader} approximates this once, at load time, by multiplying the raw
 * texture by that material's existing {@link BlockColorPalette} representative color -- not
 * pixel-perfect vanilla tinting (no per-biome variation), but real texture detail (individual
 * blade/leaf patterns, not a flat swatch) at a plausible, consistent color, which is what actually
 * reads as "a real block texture" on a map.
 */
public final class TextureAtlas {

    private final Map<String, BufferedImage> topTextures;
    private final Map<String, BufferedImage> sideTextures;
    private final Map<String, BufferedImage> shadedCache = new ConcurrentHashMap<>();

    public TextureAtlas(Map<String, BufferedImage> topTextures, Map<String, BufferedImage> sideTextures) {
        this.topTextures = topTextures;
        this.sideTextures = sideTextures;
    }

    public boolean hasTexture(String materialName) {
        return topTextures.containsKey(materialName) || sideTextures.containsKey(materialName);
    }

    /** How many distinct materials have at least one loaded texture -- purely informational
     *  (logged at startup so an admin can tell "textures loaded, N materials covered" from "loaded
     *  but something's clearly wrong, only 3 materials covered"). */
    public int materialCount() {
        Map<String, BufferedImage> union = new HashMap<>(topTextures);
        union.putAll(sideTextures);
        return union.size();
    }

    /**
     * A shaded copy of this material's top (or side) texture, cached after the first request so
     * every chunk containing this block reuses the same shaded image instead of re-darkening pixel
     * data per chunk -- the same block type recurs constantly across a real scan, and the cache is
     * bounded by (materials x faces x distinct shade factors), a couple hundred entries at most.
     * Returns null if this material has no texture for that face at all.
     */
    public BufferedImage shaded(String materialName, boolean top, double shadeFactor) {
        BufferedImage base = top ? topTextures.get(materialName) : sideTextures.get(materialName);
        if (base == null) return null;
        if (shadeFactor >= 0.999 && shadeFactor <= 1.001) return base;

        String key = materialName + "|" + top + "|" + shadeFactor;
        return shadedCache.computeIfAbsent(key, k -> shade(base, shadeFactor));
    }

    private static BufferedImage shade(BufferedImage source, double factor) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = clamp((int) Math.round(((argb >> 16) & 0xFF) * factor));
                int g = clamp((int) Math.round(((argb >> 8) & 0xFF) * factor));
                int b = clamp((int) Math.round((argb & 0xFF) * factor));
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
