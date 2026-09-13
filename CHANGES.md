# NexusScan changelog

## v0.2.1 -- filled in the real Base44 domain

Config-only follow-up: `webhook.base-url` now defaults to `https://kai-core-genesis.base44.app`
(the real app domain) instead of shipping blank, and re-confirmed `webhook.secret` matches the
`KAI_ROSS_WEBHOOK_SECRET` value already set on the Base44 side. With both filled in, the webhook
push added in v0.2.0 works out of the box instead of needing `config.yml` hand-edited first. No
code changes -- recompiled clean regardless (`javac -Xlint:all -Werror`: 0 errors, 0 warnings).

## v0.2.0 -- push straight to Base44

Built from the follow-up once the Base44/Kairos side of the integration existed: a webhook
`ingestWorldTiles` (secured by a shared secret sent as the `x-kai-secret` header, the app secret
`KAI_ROSS_WEBHOOK_SECRET`) expecting `POST {base-url}/functions/ingestWorldTiles` with
`{"manifest": {tile_blocks_base, min_zoom, max_zoom}, "tiles": [{z, tx, ty, mime, data_b64}, ...]}`
-- tiles addressed by a slippy-map-style zoom/x/y scheme rather than the flat per-chunk files v0.1.0
wrote to disk.

**Tile pyramid (`TilePyramid`):** the flat 16x16 per-chunk tiles from v0.1.0's scan become the
pyramid's finest zoom level directly (`z = max-zoom`, `tx/ty = chunk x/z` -- `tile-blocks-base`
right-shifted `max-zoom` times works out to 16 blocks, exactly one chunk, with the default 256/4).
Every coarser zoom level is derived, not rescanned: combine the four finer tiles under a given
coordinate into a 32x32 canvas (transparent where a child hasn't been visited/scanned yet) and
downsample to a fixed 16x16 -- kept at a constant pixel size across every zoom level on purpose,
since the webhook contract doesn't specify one and this keeps payloads small and uniform rather
than growing image dimensions as zoom decreases.

**Push (`WebhookPusher`):** base64-PNG-encodes every tile at every zoom level and POSTs them in
configurable-size batches (default 200 tiles/request) to `{webhook.base-url}{webhook.path}`,
entirely on a background thread so a slow or unreachable endpoint never affects the server. A
failed batch is just logged and left for the next scan cycle to retry -- safe because resending
the same `z`/`tx`/`ty` overwrites per the webhook's own contract, so nothing needs its own retry
queue. The `purge` field in the contract isn't used: chunks only ever get added to the visited set,
never removed, so no previously-pushed tile ever needs deleting.

**Local output kept as-is:** `plugins/NexusScan/web/` (flat tiles + manifest.json) still gets
written every scan exactly like v0.1.0 -- the webhook push is additive, not a replacement, so the
existing `web-viewer-example/viewer.html` reference and the local copy as a backup/debugging aid
both still work.

**Secret handling:** a secret was generated for `KAI_ROSS_WEBHOOK_SECRET`/`webhook.secret` and
given to the user to paste into Base44's Secrets settings; `webhook.secret` in config.yml defaults
to that same value so the two sides match without any further setup, beyond filling in
`webhook.base-url` (the plugin has no way to know that on its own) once the Base44 app's real
domain is known.

**Verification:** extended the existing stub library only where genuinely needed -- `TilePyramid`
and `WebhookPusher` use nothing but the JDK's own `java.awt`/`javax.imageio`/`java.net.http`/
`java.util.Base64`, no Bukkit API at all -- and recompiled the whole plugin with
`javac -Xlint:all -Werror`: 0 errors, 0 warnings.

## v0.1.0 -- first release

Built from: "we need to make a new plugin called Nexus scan... scan the surface level of the
map... any chunks that we've ever been in will pop up and be visualized. This will not play or
track anybody, and the map will only be updated a couple of times or once a day... register all
of the chunks that we've ever been in... only doing it for the overworld for now." Clarified over
a few follow-ups: it's not operated by any in-game/console command -- the user's own website
(linked into their Kairos application on Base44 as a clickable tab) is what actually displays the
map; and it should work "the exact same way" as Dynmap/BlueMap, minus their constant re-render and
live player tracking.

**Tracking, not surveillance:** `ChunkVisitTracker` records only the bare chunk coordinate the
moment any player first enters it (on join, and on crossing a chunk boundary while moving) --
never who, never when, never their live position. Once a chunk is in the visited set it's
indistinguishable from any other visited chunk. Persisted to a flat `x,z`-per-line file, appended
immediately so nothing is lost between scans or on a crash.

**Scanning is scheduled, not continuous:** `ScanEngine` runs one full pass over the current
visited-chunk set on a configurable interval (default every 12h, i.e. twice a day) plus once
shortly after server startup so there's something to see immediately, rather than reacting to
every block placed/broken the way Dynmap/BlueMap do by default. A scan is spread across many
server ticks (a configurable number of chunks per tick) specifically so a large visited area never
causes a lag spike -- this was the original spec's own "lightweight -- no tile server, no 200GB
pre-render" framing, just implemented as an internal scheduled task instead of an externally
pulled console command once the user clarified nothing external would be pulling it.

**Rendering:** `ChunkScanner` reads each chunk's 256 columns' topmost non-air block and
`BlockColorPalette` maps each to a flat representative RGB color (grass/stone/sand/water/ore/crop
families, plus the wood/leaves/wool/concrete/terracotta/stained-glass families matched by name
pattern so the whole palette doesn't need one entry per dye color) -- unrecognized blocks render as
loud magenta on purpose, so a palette gap is obvious on the map instead of silently wrong.
Unloaded/ungenerated chunks are skipped rather than guessed at.

**Output matches how Dynmap/BlueMap's own front ends actually consume tiles:** one 16x16 PNG per
scanned chunk (`tiles/<world>/<chunkX>_<chunkZ>.png`), plus a `manifest.json` listing every tile's
coordinates, the overall bounds, the last-scan time, and the tile path pattern, so a web map
doesn't have to probe for what exists. A reference Leaflet-based viewer (`web-viewer-example/
viewer.html`, not part of the plugin jar) demonstrates stitching that into a pannable/zoomable map
-- meant to be adapted into the real Base44/Kairos tab, not used as-is, since getting the
`plugins/NexusScan/web/` folder from the Minecraft server onto that site is a delivery step outside
this plugin's scope (the user still needs to decide/build that hand-off -- see README).

**Scope for this version, agreed up front:** Overworld only; the End and Nether are explicitly
future updates, same approach, once wanted.

**Verification:** built a from-scratch stub library covering this plugin's Bukkit API surface
(`World`/`Block`/`Material`, `PlayerJoinEvent`/`PlayerMoveEvent`, `BukkitScheduler`/
`BukkitRunnable`, `JavaPlugin`/`FileConfiguration`, `CommandExecutor`) and compiled the whole
plugin with `javac -Xlint:all -Werror`: 0 errors, 0 warnings.
