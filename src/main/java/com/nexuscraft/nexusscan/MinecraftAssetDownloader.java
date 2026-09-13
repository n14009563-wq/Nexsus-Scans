package com.nexuscraft.nexusscan;

import org.bukkit.Material;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches real vanilla block textures for {@link IsometricRenderer} to draw instead of
 * {@link BlockColorPalette}'s flat representative colors -- the same basic mechanism real
 * Minecraft map renderers (Dynmap, BlueMap) use: download the actual client jar directly from
 * Mojang's own official servers (never bundled or redistributed by this plugin itself), and pull
 * the block texture images out of it. Cached to disk after the first successful download so this
 * only happens once per configured Minecraft version, not once per scan or per server restart.
 * <p>
 * Every step here is designed to fail SAFE: a missing/unreachable network, a version string that
 * doesn't match anything in Mojang's manifest, a corrupted download, or any other problem along
 * the way is caught, logged clearly, and results in isometric rendering simply falling back to
 * flat colors (the pre-texture behavior) -- never a broken plugin, never a blocked startup. This
 * entire pipeline runs on a background thread (see {@link #loadAsync}); nothing here ever touches
 * the main thread or Bukkit API, so it can't cost the server the noticeable-lag guarantees the
 * rest of this plugin (see {@link ScanEngine}) was built around.
 */
public final class MinecraftAssetDownloader {

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String TEXTURE_PATH_PREFIX = "assets/minecraft/textures/block/";

    /** Materials whose textures are biome-tinted in the real game (a neutral/grayscale mask,
     *  multiplied by a per-biome color) -- approximated here by tinting with
     *  {@link BlockColorPalette}'s existing representative color instead of implementing real
     *  per-biome color maps. See {@link TextureAtlas}'s class docs for why. */
    private static boolean isTintedTop(Material material) {
        return material.name().equals("GRASS_BLOCK") || material.name().endsWith("_LEAVES");
    }

    /** Most vanilla block texture files are named exactly {@code material.name().toLowerCase()} --
     *  but not all. A handful of common, highly visible materials use a different base filename in
     *  the real game (most notably the animated liquids), so those get an explicit override here
     *  rather than silently falling back to flat color for something as common as water or lava.
     *  This list is deliberately NOT exhaustive -- any other material with a mismatched filename
     *  just falls back to a flat color for that face, the same safe degradation as a texture that's
     *  missing entirely. Extend this map if another common mismatch turns out to matter. */
    private static final Map<String, String> SPECIAL_CASE_BASE_FILENAMES = Map.of(
            "WATER", "water_still",
            "LAVA", "lava_still"
    );

    private static String baseFileName(Material material) {
        String override = SPECIAL_CASE_BASE_FILENAMES.get(material.name());
        return override != null ? override : material.name().toLowerCase();
    }

    private MinecraftAssetDownloader() {
    }

    /**
     * Kicks off the whole fetch/cache/extract pipeline on a background thread. {@code onReady} is
     * called exactly once, with a populated {@link TextureAtlas} on success or {@code null} if
     * anything went wrong (already logged by then) -- still on the background thread, so a caller
     * that needs to touch Bukkit API with the result must hop back to the main thread itself.
     */
    public static void loadAsync(File cacheDirectory, String minecraftVersion, Logger logger,
                                  Consumer<TextureAtlas> onReady) {
        Thread thread = new Thread(() -> {
            TextureAtlas atlas = null;
            try {
                atlas = load(cacheDirectory, minecraftVersion, logger);
            } catch (Exception e) {
                logger.log(Level.WARNING, "NexusScan: couldn't load real block textures -- isometric "
                        + "tiles will use flat representative colors instead until this is resolved. ("
                        + e.getMessage() + ")", e);
            }
            onReady.accept(atlas);
        }, "NexusScan-texture-load");
        thread.setDaemon(true);
        thread.start();
    }

    private static TextureAtlas load(File cacheDirectory, String minecraftVersion, Logger logger) throws IOException {
        File jarFile = new File(cacheDirectory, "client-" + minecraftVersion + ".jar");
        if (jarFile.exists() && jarFile.length() > 0) {
            logger.info("NexusScan: using cached vanilla client jar for block textures (" + jarFile.getName() + ").");
        } else {
            downloadClientJar(minecraftVersion, jarFile, logger);
        }
        TextureAtlas atlas = extractTextures(jarFile, logger);
        logger.info("NexusScan: real block textures ready (" + atlas.materialCount() + " material(s) covered).");
        return atlas;
    }

    private static void downloadClientJar(String minecraftVersion, File destination, Logger logger) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        String manifestJson = httpGetString(client, VERSION_MANIFEST_URL);
        String versionUrl = findVersionMetadataUrl(manifestJson, minecraftVersion);
        if (versionUrl == null) {
            throw new IOException("Minecraft version '" + minecraftVersion + "' wasn't found in Mojang's version "
                    + "manifest -- check isometric.minecraft-version in config.yml matches your server's exact version.");
        }

        String versionJson = httpGetString(client, versionUrl);
        String[] clientUrlAndSha1 = findClientDownload(versionJson);
        String clientUrl = clientUrlAndSha1[0];
        String expectedSha1 = clientUrlAndSha1[1];
        if (clientUrl == null) {
            throw new IOException("Mojang's metadata for '" + minecraftVersion + "' didn't include a client download URL.");
        }

        logger.info("NexusScan: downloading the vanilla " + minecraftVersion + " client from Mojang for block "
                + "textures (one-time; cached afterward)...");

        //noinspection ResultOfMethodCallIgnored
        destination.getParentFile().mkdirs();
        File tempFile = File.createTempFile("client-", ".jar.tmp", destination.getParentFile());
        HttpRequest request = HttpRequest.newBuilder(URI.create(clientUrl)).timeout(Duration.ofMinutes(5)).build();
        HttpResponse<Path> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading the client jar.", e);
        }
        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile.toPath());
            throw new IOException("Mojang returned HTTP " + response.statusCode() + " downloading the client jar.");
        }

        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            String actualSha1 = sha1(tempFile.toPath());
            if (!expectedSha1.equalsIgnoreCase(actualSha1)) {
                Files.deleteIfExists(tempFile.toPath());
                throw new IOException("Downloaded client jar's sha1 didn't match Mojang's manifest -- discarded "
                        + "rather than caching a possibly-corrupt file.");
            }
        }

        Files.move(tempFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        logger.info("NexusScan: client jar downloaded and verified (" + (destination.length() / (1024 * 1024))
                + " MB), cached at " + destination.getPath());
    }

    @SuppressWarnings("unchecked")
    static String findVersionMetadataUrl(String manifestJson, String minecraftVersion) {
        Map<String, Object> manifest = (Map<String, Object>) MiniJson.parse(manifestJson);
        List<Object> versions = (List<Object>) manifest.get("versions");
        if (versions == null) return null;
        for (Object entry : versions) {
            Map<String, Object> versionEntry = (Map<String, Object>) entry;
            if (minecraftVersion.equals(versionEntry.get("id"))) {
                return (String) versionEntry.get("url");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String[] findClientDownload(String versionJson) {
        Map<String, Object> version = (Map<String, Object>) MiniJson.parse(versionJson);
        Map<String, Object> downloads = (Map<String, Object>) version.get("downloads");
        if (downloads == null) return new String[]{null, null};
        Map<String, Object> clientEntry = (Map<String, Object>) downloads.get("client");
        if (clientEntry == null) return new String[]{null, null};
        return new String[]{(String) clientEntry.get("url"), (String) clientEntry.get("sha1")};
    }

    private static String httpGetString(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Mojang.", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Mojang returned HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available on this JVM.", e);
        }
    }

    /**
     * Reads every PNG under {@code assets/minecraft/textures/block/} out of the client jar, then
     * builds the top/side maps {@link TextureAtlas} needs by the vanilla naming convention: a
     * material with a {@code <name>_top.png} gets that for its top face (else the bare
     * {@code <name>.png}, if any), and similarly {@code <name>_side.png} (else the bare file) for
     * its side faces -- most blocks only have the bare file, so they end up with the same image on
     * every face, matching how they actually look in-game. A material with no matching file at all
     * simply isn't added to either map, which is exactly what tells {@link IsometricRenderer} to
     * fall back to a flat color for it -- never a hard error over one missing/oddly-named texture.
     */
    /** Package-private (not private) so a same-package test can exercise it directly against a
     *  synthetic fixture jar without needing a real network download. */
    static TextureAtlas extractTextures(File jarFile, Logger logger) throws IOException {
        Map<String, BufferedImage> rawByFileName = new HashMap<>();
        try (ZipFile zip = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(TEXTURE_PATH_PREFIX) || !name.endsWith(".png")) continue;

                String fileName = name.substring(TEXTURE_PATH_PREFIX.length(), name.length() - ".png".length());
                try (InputStream in = zip.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(in);
                    if (image == null) continue;
                    rawByFileName.put(fileName, firstFrame(image));
                } catch (IOException e) {
                    logger.log(Level.FINE, "NexusScan: couldn't decode texture " + name, e);
                }
            }
        }

        Map<String, BufferedImage> topTextures = new HashMap<>();
        Map<String, BufferedImage> sideTextures = new HashMap<>();
        for (Material material : Material.values()) {
            String lower = baseFileName(material);
            BufferedImage bare = rawByFileName.get(lower);
            BufferedImage top = rawByFileName.getOrDefault(lower + "_top", bare);
            BufferedImage side = rawByFileName.getOrDefault(lower + "_side", bare);

            if (top != null && isTintedTop(material)) {
                top = tint(top, BlockColorPalette.colorFor(material));
                // Leaves are tinted on every face, not just the top; grass_block's side texture is
                // only partially tinted in the real game (a thin overlay strip) -- approximated
                // here as untinted dirt-colored side, which is closer to the real look than fully
                // tinting the whole side face green.
                if (material.name().endsWith("_LEAVES") && side != null) {
                    side = tint(side, BlockColorPalette.colorFor(material));
                }
            }

            if (top != null) topTextures.put(material.name(), top);
            if (side != null) sideTextures.put(material.name(), side);
        }

        return new TextureAtlas(topTextures, sideTextures);
    }

    /** Animated textures (water, lava, fire, ...) are stored as a tall strip of stacked frames --
     *  a square (or near-square) image is used as-is; anything clearly taller than it is wide is
     *  assumed to be such a strip, and only its first frame (the top {@code width x width} square)
     *  is kept, since a static map render has no use for animation. */
    private static BufferedImage firstFrame(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (h <= w) return image;
        return image.getSubimage(0, 0, w, w);
    }

    private static BufferedImage tint(BufferedImage source, int tintRgb) {
        double tr = ((tintRgb >> 16) & 0xFF) / 255.0;
        double tg = ((tintRgb >> 8) & 0xFF) / 255.0;
        double tb = (tintRgb & 0xFF) / 255.0;

        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = clamp((int) Math.round(((argb >> 16) & 0xFF) * tr));
                int g = clamp((int) Math.round(((argb >> 8) & 0xFF) * tg));
                int b = clamp((int) Math.round((argb & 0xFF) * tb));
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
