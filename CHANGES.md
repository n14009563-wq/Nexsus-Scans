# NexusScan changelog

## v0.7.0 -- take the server-lag risk seriously

Built from: "It is so important that this specific... plugin does not create a massive layer of
lag or load on the server... not at the cost of being able to play the damn game." Every version
through v0.6.0 was already spread across many ticks (`chunks-per-tick`), but that alone didn't
actually guarantee low impact -- three real lag sources were still there, and at millions of
visited chunks they mattered:

**1. Every chunk scan forced a synchronous main-thread chunk load.** `world.getHighestBlockAt(x,z)`
on a chunk that's generated but not currently loaded (true for the overwhelming majority of a
years-old server's visited chunks at any given moment) silently loads that chunk from disk --
synchronously, on the main thread, exactly the kind of disk I/O stall that causes server-wide
hitches. Fixed by switching every chunk read to `World#getChunkAtAsync`, which -- per Paper's own
contract -- does any needed disk I/O off the main thread and only resumes on the main thread once
the chunk is actually ready.

**2. 256 separate live block lookups per chunk.** `ChunkScanner` called `getHighestBlockAt` once per
column -- 256 individual live `World`/`Block` API dispatches per chunk, all necessarily on the main
thread. Replaced with one `Chunk#getChunkSnapshot(...)` call per chunk (a cheap, Bukkit-documented
thread-safe copy) -- the *only* genuinely required main-thread step per chunk now, and the actual
256-column color-mapping loop moved onto a background thread pool (`background-threads`, default 2)
since a snapshot's data is safe to read from any thread.

**3. Millions of scanned chunks staying loaded in memory, and PNG file writes on the main thread.**
A chunk NexusScan force-loaded (because nothing else needed it loaded) is now unloaded again
immediately after its snapshot is taken (`unload-forced-chunks`, default on) -- otherwise a full
scan would leave millions of chunks pinned in memory nobody's using, its own path to server lag via
memory pressure and GC pauses. Rendering the tile image, encoding it to PNG, and writing it to disk
all happen on the background thread pool too, alongside the (already-off-main-thread) Base44 push --
none of that work touches Bukkit API, so none of it needs to compete with the rest of the server
for main-thread time. The end-of-scan `manifest.json` write moved there as well.

**4. A fixed chunk-count throttle doesn't actually bound main-thread time.** `chunks-per-tick` is
now a sanity backstop only; the real throttle is `max-tick-millis` (default 3.0), which measures
actual elapsed time with `System.nanoTime()` each tick and stops dispatching once the budget's
spent -- self-adjusting to whatever else the server's doing or how fast the hardware is, instead of
a number that has to be hand-tuned per machine and can go stale as the visited set grows.

Net effect: main-thread work per chunk went from "up to 256 live block lookups, sometimes with a
forced synchronous disk load" down to "kick off an async load (cheap even when it has to wait), one
snapshot copy once it's ready" -- with actual rendering, encoding, and every file write happening
entirely off-thread, and a time-measured (not count-guessed) throttle keeping whatever main-thread
work remains within a small, configurable slice of each tick's budget.

**Verification:** recompiled with `javac -Xlint:all -Werror`: 0 errors, 0 warnings (the stub
library gained `Chunk`, `ChunkSnapshot`, and `World#getChunkAtAsync`/`isChunkLoaded`/`unloadChunk`).
Wrote a standalone concurrency stress test simulating 20,000 chunks dispatched under randomized
async timing (some completing immediately, some after a delay on a background clock, some
simulating load failures) through the exact dispatch/drain/completion-detection pattern
`ScanEngine` uses -- confirmed the scan always correctly detects completion, every successful chunk
is processed exactly once (no duplicates, none lost) even under real concurrent execution, and
`inFlight` always reaches zero. Also wrote a standalone test of the new snapshot-based
`ChunkScanner.renderTile` confirming it queries the exact `(x, highestY, z)` triple for all 256
columns and correctly distinguishes a real block from an all-air column.

## v0.6.0 -- scan outward from spawn

Built from: "I wanna add is I want you to start at actual world spawn, which is negative sixteen
thousand six hundred and forty nine by seventy two by nine thousand six hundred and forty five,
and then make a systematic grid that works outwards." Up through v0.5.0, a scan processed whatever
chunks were in the visited set in whatever order a `HashSet` happened to iterate them -- not wrong,
but not meaningful either, so the incremental Base44 push (v0.4.0) filled in the map as a scatter of
unrelated tiles rather than something that reads as "the map is filling in."

**`ScanEngine`** now sorts every scan's chunk list by squared distance from a configured origin
point (`scan-origin-x` / `scan-origin-z`, block coordinates, pre-filled with the given spawn point
-16649, 9645 -- Y doesn't matter for chunk math, chunk coordinates are 2D) before scanning starts,
so the closest chunk to spawn is processed first and each successive chunk is farther out -- a
steadily expanding ring, not a scatter. Combined with the incremental push, the website now fills
in outward from spawn as the scan progresses, which is both a more useful default viewing
experience and matches "systematic grid that works outwards" directly.

Sorting a multi-million-entry list is real work, so it deliberately does **not** happen
synchronously on the main thread (that would freeze the server for however long the sort takes) --
it runs on a background thread first (nothing in the sort touches Bukkit API), and only once
ordering is done does the actual tick-by-tick scan (which does touch the world) get scheduled back
onto the main thread. `/nexusscan status` and `isScanInProgress()` both treat "still ordering" the
same as "actively scanning," so a rescan can't be double-started during that window.

The origin is a fixed config value, not read from the world's live spawn point (`World.getSpawnLocation()`)
-- deliberately, since that can be changed at any time with `/setworldspawn` and this plugin has no
existing reason to depend on it; a fixed, explicitly-set reference point is simpler and matches
what was actually asked for (a specific coordinate, not "wherever spawn happens to be today").

**Verification:** recompiled with `javac -Xlint:all -Werror`: 0 errors, 0 warnings (the stub
library gained `Plugin#isEnabled()` and `BukkitScheduler#runTask(...)`, both real Bukkit API this
needed). Wrote a standalone test confirming the block-coordinate-to-chunk-coordinate conversion for
the given spawn point is exact and brackets the original block coordinate correctly, and confirming
a scrambled set of chunk keys sorts into a strictly non-decreasing distance-from-origin order with
the origin chunk itself sorting first.

## v0.5.0 -- sharper tiles

Built from: "Is there a way to increase the detail and the quality of the image that's getting
transferred over to the website application? because the images is coming in so blurry." Every
tile up through v0.4.0 was a fixed 16x16 pixel PNG -- exactly one pixel per block column, which is
all the real detail there is (one color sample per column), but tiny enough that any website
displaying it larger than 16 real screen pixels has to stretch it, and a browser's default image
scaling smooths/blurs that stretch rather than keeping it crisp.

**`ChunkScanner`** now renders each block column as a solid `tile-pixel-size / 16`-pixel square
(new config key, default `64` -- 4 pixels per block) instead of a single pixel, using
`Graphics2D.fillRect` with no antialiasing -- a hard-edged, nearest-neighbor upscale of the exact
same data, not invented/interpolated detail. That's the actual fix for "blurry": a bigger, crisper
source image gives the website's own scaling less work to do (or none, if displayed at native
size), and if the website also renders it with nearest-neighbor ("pixelated") image scaling instead
of the browser default, block edges stay perfectly sharp at any zoom.

**`TilePyramid`/`IncrementalPyramid`** had their tile size un-hardcoded from a `16`-pixel constant
to a parameter threaded through from `ScanConfig.tilePixelSize()`, so every zoom level (not just
the finest) renders at the configured size. The coarser-zoom bilinear blend when combining 2x2
child tiles into one parent is unchanged on purpose -- that's genuinely averaging real detail from
multiple neighboring chunks into one overview pixel, which is the correct "zoomed out" look, not
blur being introduced where there's no data.

**Verification:** recompiled with `javac -Xlint:all -Werror`: 0 errors, 0 warnings. Extended the
v0.4.0 `IncrementalPyramid` test to run at the new default 64x64 tile size (not just the old
16x16), confirming pixel-identical output vs. `TilePyramid.build()` and correctly-sized tiles at
every zoom. Added a standalone test of `ChunkScanner`'s new per-block fill logic confirming all 256
blocks in a chunk map to solid, correctly-positioned, non-overlapping pixel squares with zero
blending at the boundary between adjacent blocks -- i.e. genuinely hard-edged, not a disguised blur.

## v0.4.0 -- incremental Base44 push, and a safer backfill

Built from live-server feedback after v0.3.0: "It is working. It's just taking an incredible
amount of time to send the data over to the website... Is there a better way to get this to where
it can generate the map and then only update it once a day? So we can at least get the map
generated?" The user's actual server console log showed why: v0.3.0's region-file backfill found
**7,105,078** chunks on startup -- and because `ScanEngine` only pushed anything to Base44 once the
*entire* scan queue had drained, none of that data reached the website even 35+ seconds after the
scan started (at the default 10 chunks/tick, a queue that size is roughly a 10-hour scan). Two
separate problems, both fixed here:

**1. The backfill count itself was almost certainly wrong.** The same server also runs a
NexusTerra-style plugin that programmatically imports real-world terrain -- chunks it generates
exist on disk exactly like player-explored ones, so `RegionFileScanner`'s "exists on disk = a
player's been there" assumption breaks down and inflates the visited set by orders of magnitude.
`backfill-on-startup` now **defaults to off**, and running it is now a two-step, explicit choice:
`/nexusscan backfill` reads the region files and reports how many chunks it *would* add without
touching anything, and `/nexusscan backfill confirm` commits the most recent preview -- so a
NexusTerra-sized number is visible before it gets baked into the visited set, not after. A new
`/nexusscan resetvisited confirm` clears an already-inflated visited set (e.g. the 7.1M-chunk one
already written to this user's `visited-chunks.txt`) so the map can be rebuilt from a clean slate.

**2. Even with an honest chunk count, waiting for the whole scan to finish before pushing anything
doesn't scale.** `IncrementalPyramid` (new) is the same tile pyramid `TilePyramid` always built,
but maintained one finished chunk at a time instead of only being buildable from a complete
snapshot: feeding in one chunk only recomputes that chunk's own ancestor chain (one tile per zoom
level -- a handful, not the whole pyramid), so the cost stays proportional to chunks scanned, not
chunks scanned times how often progress gets pushed. `ScanEngine` now pushes whatever's changed to
Base44 every `webhook.push-batch-chunks` successfully-scanned chunks (default 2000), not just once
at the very end -- so a partial, growing map shows up on the website within minutes even on a
multi-million-chunk visited set, and keeps filling in as the scan continues. `scan-interval-hours`
still governs how often a *new* scan starts (default changed to 24, i.e. once a day, matching what
was actually asked for); the local `manifest.json` (a backup/debugging copy, not what the website
uses) is still written once at the end only, since rewriting a millions-of-entries JSON file on
every push batch would reintroduce the same O(N²) problem this version set out to fix.

**Verification:** recompiled with `javac -Xlint:all -Werror`: 0 errors, 0 warnings. Wrote a
standalone test feeding the same set of chunks into both `TilePyramid.build()` (the original
from-scratch builder) and `IncrementalPyramid` fed one chunk at a time, and compared every tile at
every zoom level pixel-for-pixel -- confirmed identical output. Also confirmed a single `update()`
call only marks its own ancestor chain dirty (5 tiles for a 5-level pyramid), not the whole tree,
which is the whole point of doing this incrementally instead of rebuilding from scratch each push.

## v0.3.0 -- backfill from existing region files

Built from troubleshooting "Base44 says it's not receiving any data yet" after install: the real
cause is that v0.1.0/v0.2.x only ever recorded chunks visited *after* the plugin started running --
years of prior exploration on a long-lived server (this user has played continuously since 2010)
simply wasn't in the visited set yet, so the first scan had nothing to push. That's a real gap
against the original ask ("any chunk we've ever been in") and against "build it the exact same way
as Dynmap/BlueMap," which both render everything already generated, not just new activity.

**`RegionFileScanner`:** on every plugin startup (config: `backfill-on-startup`, default on),
reads just the first 4KB (the chunk offset table) of every `<world>/region/r.<x>.<z>.mca` file to
find every chunk that's ever been generated and saved -- no Bukkit chunk-loading API involved at
all, just a header read per region file, so it's cheap even on a large world. A chunk's offset
table entry is non-zero if and only if it's been generated and saved at some point, which in
normal survival play (no pregenerator) only happens because a player got near it -- exactly the
retroactive signal needed. Verified against a synthetic `.mca` header (including negative region
coordinates) confirming the local-index math and chunk coordinate conversion are correct.

**`ChunkVisitTracker#seedChunks`:** bulk-adds the backfilled coordinates into the same visited set
`PlayerMoveEvent`/`PlayerJoinEvent` tracking already uses, so nothing downstream (scanning,
tile pyramid, webhook push) needed to change -- the backfill just means the set isn't empty on
first run anymore. Already-known chunks are skipped, so this is safe to run on every startup
rather than needing a "did we already do this" marker.

**README** gained a "Getting it running for the first time" walkthrough (build, install, restart,
`/nexusscan rescan` to test immediately rather than waiting for the schedule, and what a 0-chunk
`/nexusscan status` or a 401 in the console log each actually mean).

**Verification:** recompiled the whole plugin with `javac -Xlint:all -Werror`: 0 errors, 0
warnings; separately built a synthetic region file with known chunk entries (including a negative
region coordinate) and ran `RegionFileScanner` against it standalone to confirm it recovers exactly
the expected chunk coordinates before wiring it into the plugin.

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
