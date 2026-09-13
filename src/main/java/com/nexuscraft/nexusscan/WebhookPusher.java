package com.nexuscraft.nexusscan;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pushes a finished scan's tiles to the Base44/Kairos app's ingestWorldTiles webhook, matching
 * the contract it was given: POST {webhook.base-url}{webhook.path}, JSON body
 * {"manifest": {...}, "tiles": [{z, tx, ty, mime, data_b64}, ...]}, authenticated via the
 * x-kai-secret header. Runs entirely on a background thread -- this never touches Bukkit API, so
 * there's no need to involve the main thread or the server scheduler for it.
 */
public final class WebhookPusher {

    private WebhookPusher() {
    }

    public static void pushAsync(ScanConfig config, List<TilePyramid.Tile> tiles, Logger logger) {
        if (!config.webhookEnabled()) return;

        String baseUrl = config.webhookBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.warning("NexusScan: webhook.base-url isn't set in config.yml -- tiles were written "
                    + "locally but not pushed to Base44 this cycle.");
            return;
        }
        String secret = config.webhookSecret();
        if (secret == null || secret.isBlank()) {
            logger.warning("NexusScan: webhook.secret isn't set in config.yml -- tiles were written "
                    + "locally but not pushed to Base44 this cycle.");
            return;
        }

        String url = joinUrl(baseUrl, config.webhookPath());
        int batchSize = config.webhookTilesPerRequest();
        String manifestJson = "{\"tile_blocks_base\": " + config.webhookTileBlocksBase()
                + ", \"min_zoom\": " + config.webhookMinZoom()
                + ", \"max_zoom\": " + config.webhookMaxZoom() + "}";

        Thread pushThread = new Thread(() ->
                runPush(url, secret, manifestJson, tiles, batchSize, logger), "NexusScan-webhook-push");
        pushThread.setDaemon(true);
        pushThread.start();
    }

    private static void runPush(String url, String secret, String manifestJson, List<TilePyramid.Tile> tiles,
                                 int batchSize, Logger logger) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        int uploaded = 0;
        int failedBatches = 0;
        int totalBatches = (int) Math.ceil(tiles.size() / (double) Math.max(1, batchSize));

        for (int start = 0; start < tiles.size(); start += batchSize) {
            List<TilePyramid.Tile> batch = tiles.subList(start, Math.min(start + batchSize, tiles.size()));
            String body;
            try {
                body = buildBatchJson(manifestJson, batch);
            } catch (IOException e) {
                logger.log(Level.WARNING, "NexusScan: couldn't encode a tile batch for the Base44 push.", e);
                failedBatches++;
                continue;
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-kai-secret", secret)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    uploaded += batch.size();
                } else {
                    failedBatches++;
                    logger.warning("NexusScan: Base44 push batch failed (HTTP " + response.statusCode() + "): "
                            + truncate(response.body()));
                }
            } catch (IOException | InterruptedException e) {
                failedBatches++;
                logger.log(Level.WARNING, "NexusScan: Base44 push batch failed to send.", e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        if (failedBatches == 0) {
            logger.info("NexusScan: pushed " + uploaded + " tile(s) to Base44 across " + totalBatches + " request(s).");
        } else {
            logger.warning("NexusScan: pushed " + uploaded + " tile(s) to Base44, but " + failedBatches
                    + " of " + totalBatches + " request(s) failed -- they'll be resent (same z/tx/ty overwrites) "
                    + "on the next scan.");
        }
    }

    private static String buildBatchJson(String manifestJson, List<TilePyramid.Tile> batch) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\"manifest\": ").append(manifestJson).append(", \"tiles\": [");
        for (int i = 0; i < batch.size(); i++) {
            TilePyramid.Tile tile = batch.get(i);
            json.append("{\"z\": ").append(tile.zoom())
                    .append(", \"tx\": ").append(tile.tileX())
                    .append(", \"ty\": ").append(tile.tileY())
                    .append(", \"mime\": \"image/png\"")
                    .append(", \"data_b64\": \"").append(encodePng(tile.image())).append("\"}");
            if (i < batch.size() - 1) json.append(", ");
        }
        json.append("]}");
        return json.toString();
    }

    private static String encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String joinUrl(String baseUrl, String path) {
        String trimmedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String trimmedPath = path.startsWith("/") ? path : "/" + path;
        return trimmedBase + trimmedPath;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
