# NexusScan v0.4.0

Tracks every Overworld chunk any player has ever set foot in, and on a schedule (not
continuously) renders each one into a small top-down color tile -- the same basic idea
Dynmap/BlueMap use, just without their constant re-rendering and without any live player
tracking. Nothing about a player's identity or position is ever stored; only the bare fact that a
given chunk has been visited.

## What it does NOT do

- It does not track players live, does not know or store who visited a chunk or when, and never
  broadcasts anyone's position (unlike Dynmap/BlueMap's default live player markers).
- It does not re-render on every block change. It only re-scans on the configured interval
  (`scan-interval-hours`, default every 12h).
- It does not run a web server. It writes tile images to a folder on disk (always, one per
  scanned chunk as it's scanned), and also pushes them straight to your Base44/Kairos app's
  webhook if `webhook.base-url` is configured -- incrementally, throughout the scan, not just
  once it finishes -- see "Getting this onto your website" below.
- Overworld only, on purpose, for this version. The End and Nether are planned as later updates
  (same approach, once you want them: a second configured world + a second tiles/<world>/ folder).

## How it works

1. **Backfill** (`RegionFileScanner`, off by default -- see below) -- reads just the 4KB header of
   every file in `<world>/region/*.mca` to find every chunk that's ever been generated and saved to
   disk, and seeds those straight into the visited set. This is what makes a server with years of
   history behind it show up immediately instead of starting from a blank map and only growing from
   whatever's visited from here on -- a chunk only exists on disk at all because something caused it
   to generate at some point. **As of v0.4.0 this is opt-in, run on demand with `/nexusscan
   backfill`**, not automatic on every startup: a chunk existing on disk is normally because a
   player got near it, but if any other plugin on your server also generates or imports terrain
   programmatically (a world importer, a pregenerator, etc.), the same header read can't tell the
   difference, and the "visited" count can end up wildly larger than real exploration -- which also
   means proportionally longer scans. `/nexusscan backfill` reports the count it *would* add before
   touching anything, specifically so that's easy to catch before it happens.
2. **Tracking** (`ChunkVisitTracker`) -- listens for players changing chunks (on join, and on any
   chunk-boundary crossing while moving) in the configured world only, and adds that chunk
   coordinate to a persistent set (`plugins/NexusScan/visited-chunks.txt`, one `x,z` per line,
   appended immediately as new chunks are found so nothing is lost on a crash). This is what keeps
   the map growing over time, whether or not the backfill above is ever used.
3. **Scanning** (`ScanEngine` + `ChunkScanner`) -- on the configured interval (and once shortly
   after startup, so there's something to look at right away), takes a snapshot of every visited
   chunk and works through it a few chunks per server tick (`chunks-per-tick`, default 20) so a
   large visited area doesn't cause a lag spike. For each chunk, it reads the top non-air block of
   all 256 columns and maps each to a flat RGB color (`BlockColorPalette`) -- unloaded/ungenerated
   chunks are skipped (nothing to render yet).
4. **Local output** (`TileWriter`) -- each scanned chunk becomes one 16x16 PNG at
   `plugins/NexusScan/web/tiles/<world>/<chunkX>_<chunkZ>.png`, written as soon as that chunk is
   scanned. Once the whole scan finishes, a `manifest.json` is written alongside the tiles listing
   every tile's coordinates, the overall bounds, the last-scan timestamp, and the tile naming
   pattern -- this is a backup/debugging copy (see "Getting this onto your website" below for what
   the actual website uses), so it's only written once at the end rather than repeatedly during the
   scan, to avoid rewriting a potentially huge JSON file over and over.
5. **Base44 push** (`IncrementalPyramid` + `WebhookPusher`) -- **as of v0.4.0, this happens
   incrementally throughout the scan, not just once at the very end.** Each scanned chunk's tile
   becomes the finest zoom level (`webhook.max-zoom`) of a multi-zoom tile pyramid as soon as it's
   scanned; each coarser zoom level combines a 2x2 block of the level below it and downsamples back
   to a fixed 16x16 pixels, all the way down to `webhook.min-zoom` (default a 0-4 range, where
   max-zoom 4 means exactly one tile per chunk). Every `webhook.push-batch-chunks` scanned chunks
   (default 2000), whatever's changed since the last push gets base64-PNG-encoded and POSTed in
   batches (`webhook.tiles-per-request`) to `{webhook.base-url}{webhook.path}`, with the shared
   secret in the `x-kai-secret` header, on a background thread so it never blocks the server. This
   is what makes a huge visited area (millions of chunks) show up on the website progressively
   within minutes, instead of the whole map waiting for the last chunk in a scan that could take
   hours. A batch that fails is simply retried on the next scan cycle (resending the same
   `z`/`tx`/`ty` overwrites, per the webhook's own contract).

## Getting this onto your website

As of v0.2.0 this is wired directly: NexusScan pushes every scan's tiles straight to your Base44
app's `ingestWorldTiles` webhook (in addition to still writing the local files from step 3 above,
kept as a backup/debugging copy). Both settings needed for that are already filled in as of v0.2.1:

- `webhook.base-url` in config.yml is pre-filled with `https://kai-core-genesis.base44.app`.
- `webhook.secret` is pre-filled with the value generated for the `KAI_ROSS_WEBHOOK_SECRET`
  secret on the Base44 side -- paste that same value into Base44's Secrets settings (if you
  haven't already) and the two sides match with no further changes.

If either side ever changes (a new app domain, a rotated secret), update `config.yml` and run
`/nexusscan reload` (or restart) to pick it up.

To help with the actual map-rendering side (for your own testing, or if any part of the site still
needs a plain example), `web-viewer-example/viewer.html` (shipped alongside this source, not part
of the plugin jar) is a working reference: a small Leaflet-based page that reads manifest.json and
stitches the local tiles into a pannable/zoomable map, in the same spirit as Dynmap/BlueMap's own
web front ends.

## Getting it running for the first time

1. `mvn clean package` this source against the real Paper API (the sandbox this was built in has
   no network access to `repo.papermc.io`, so only a stub-verified compile happened here -- see
   "Build" below). Drop the resulting `target/NexusScan-0.4.0.jar` into your server's `plugins/`
   folder.
2. Restart the server (a plugin reload isn't enough the first time -- `onEnable` needs to run).
   Check the console for `NexusScan enabled -- tracking N visited chunk(s) in 'world'...`. `N` will
   be whatever's already in `visited-chunks.txt` (0 on a brand-new install) -- backfilling from
   existing region files is a separate, deliberate step now (see next).
3. If this server has history behind it (played before NexusScan was installed) and you want that
   reflected on the map, run `/nexusscan backfill`. It reports how many chunks it *would* add
   without changing anything yet -- sanity-check that number against how much you'd actually
   expect to have explored. If it looks right, run `/nexusscan backfill confirm` to commit it. If
   it looks absurdly large (millions, or far more than you'd expect), you likely have another
   plugin that generates/imports terrain programmatically on this server -- skip committing it, or
   commit it and then use `/nexusscan resetvisited confirm` afterward to undo it.
4. Don't wait for the scheduled scan -- run `/nexusscan rescan` right away to force an immediate
   scan + Base44 push, so you can confirm it end-to-end without waiting the default 60s startup
   delay (or the 24h interval after that). Tiles now get pushed to Base44 *while the scan is still
   running* (every `webhook.push-batch-chunks` chunks, default 2000), so watch the console for the
   first `NexusScan: progress -- ...` line rather than waiting for "scan finished" -- on a large
   visited set that first progress push is what tells you it's actually working end-to-end.
5. Run `/nexusscan status` afterward. If `Visited chunks recorded` is 0 and you expected more, make
   sure you ran the backfill step above, and double check `world` in config.yml matches your actual
   Overworld's folder name. Watch the console for `NexusScan: pushed N tile(s) to Base44...`; a
   `401` in the log means the secret doesn't match what's in Base44's `KAI_ROSS_WEBHOOK_SECRET`, and
   a connection error means `webhook.base-url` is wrong or the server can't reach the internet.

### Upgrading from v0.3.0 (already has a backfilled `visited-chunks.txt`)

`backfill-on-startup` changed its default from `true` to `false` in v0.4.0, but an *existing*
`config.yml` already has `backfill-on-startup: true` written into it explicitly from before --
upgrading the jar alone won't change that value back, since config.yml is only filled in for keys
that don't already exist. If your visited-chunk count looks too large to be real exploration (for
example, it came from a backfill on a server that also runs a plugin generating terrain
programmatically), either set `backfill-on-startup: false` in config.yml yourself, or leave it and
just run `/nexusscan resetvisited confirm` once to clear the inflated set and start over with the
new opt-in `/nexusscan backfill` flow above.

## Commands (`nexusscan.admin`, default op)

- `/nexusscan status` -- world, visited-chunk count, whether a scan is currently running, when the
  last one started/finished, and the output folder path.
- `/nexusscan rescan` -- force a scan right now instead of waiting for the next scheduled interval.
- `/nexusscan reload` -- reloads config.yml. Note: `scan-interval-hours` and the startup-scan delay
  are only read once, at server start, to set up the repeating task -- changing those two in
  particular needs a restart to take effect.
- `/nexusscan backfill` -- reads the world's region files and reports how many chunks it *would*
  add to the visited set, without changing anything yet.
- `/nexusscan backfill confirm` -- commits the most recent `/nexusscan backfill` preview.
- `/nexusscan resetvisited` -- reports how many visited chunks exist right now (does nothing else).
- `/nexusscan resetvisited confirm` -- permanently clears the entire visited set. Irreversible; use
  this to undo a backfill that turned out to be too large.

## Config (`plugins/NexusScan/config.yml`)

- `world` -- which world to track/scan. Default `world` (your main Overworld).
- `backfill-on-startup` -- seed the visited set from every chunk that already exists in the
  world's region files, on every startup. Default `false` as of v0.4.0 -- use `/nexusscan backfill`
  instead to preview the count before committing it (see "How it works" above for why).
- `scan-interval-hours` -- how often a new scan starts. Default `24` (once a day). Tiles reach
  Base44 incrementally during each scan (see `webhook.push-batch-chunks` below), not just once a
  scan finishes, so this mainly controls freshness, not how long you wait to see anything.
- `scan-on-startup` / `startup-scan-delay-seconds` -- run one scan shortly after the server starts
  instead of waiting for the first interval. Default: on, 60s delay.
- `chunks-per-tick` -- how many chunks to process per server tick during a scan. Default `20`.
- `output-directory` -- where tiles + manifest.json get written, relative to the plugin's data
  folder. Default `web`.
- `webhook.enabled` -- master on/off switch for the Base44 push. Default `true`.
- `webhook.base-url` -- your Base44 app's domain. Pre-filled with `https://kai-core-genesis.base44.app`.
- `webhook.path` -- the endpoint path. Default `/functions/ingestWorldTiles`.
- `webhook.secret` -- shared secret sent as the `x-kai-secret` header; must match Base44's
  `KAI_ROSS_WEBHOOK_SECRET`. Pre-filled with the value already generated for it.
- `webhook.min-zoom` / `webhook.max-zoom` / `webhook.tile-blocks-base` -- the tile pyramid's zoom
  range, matching the manifest fields `ingestWorldTiles` expects. Defaults `0` / `4` / `256`.
- `webhook.tiles-per-request` -- how many tiles go in each POST request. Default `200`.
- `webhook.push-batch-chunks` -- as of v0.4.0, how many scanned chunks trigger an incremental push
  of whatever's changed to Base44, instead of waiting for the whole scan to finish. Default `2000`.

## Color palette

`BlockColorPalette` maps the common terrain/surface blocks (grass, stone, sand, water, ores,
crops, wood/leaves/wool/concrete/terracotta families by name pattern, etc.) to a representative
flat color, no lighting or shading -- it's meant to read like a vanilla in-game map, not a fully
shaded render. Any block it doesn't recognize renders as loud magenta (`FF00FF`) on purpose,
instead of silently guessing wrong -- if you spot magenta on the map, that's this plugin telling
you a block type is missing from the palette and needs adding.

## Build

```
mvn clean package
```

Produces `target/NexusScan-0.4.0.jar`. Requires Java 21 and network access to `repo.papermc.io` /
Maven Central. See CHANGES.md for how this was verified in the sandbox (a from-scratch stub
library covering this plugin's Bukkit API surface, `javac -Xlint:all -Werror`: 0 errors, 0
warnings) -- run your own `mvn clean package` against the real Paper API.
