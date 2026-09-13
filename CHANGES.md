# NexusScan changelog

## v1.1.0 -- real vanilla block textures, not flat colors, on the isometric renderer

Built from: "Well, that's great. But it doesn't sound like I'm getting three d imagery of actual
structures with their block textures and everything" -- the direct next reaction to v1.0.0. v1.0.0
fixed the geometry problem (real height, walls, structure instead of a flat color-per-column swatch)
but every face was still filled with `BlockColorPalette`'s single flat representative color per
block type -- correct shapes, still no actual material detail (no grass blade pattern, no stone
speckle, no wood grain). v1.1.0 closes that specific gap: real per-pixel vanilla block textures,
mapped onto the same isometric geometry v1.0.0 already draws.

**`MinecraftAssetDownloader`** (new) fetches the plain vanilla client jar directly from Mojang's own
public version-manifest endpoint (`piston-meta.mojang.com/mc/game/version_manifest_v2.json`, then
that version's own metadata JSON, verified against the SHA-1 hash Mojang's own metadata provides) --
the same official mechanism the real Minecraft launcher itself uses, and the same category of
mechanism Dynmap/BlueMap rely on. Nothing is scraped, reverse-engineered, or bundled with this
plugin's source -- the user's own server fetches it once, directly from Mojang, the first time real
textures are enabled (or whenever `isometric.minecraft-version` changes), and caches the jar under
`plugins/NexusScan/texture-cache/` so it isn't re-downloaded on every restart. **`TextureAtlas`**
(new) holds the extracted top/side texture image per `Material`, including the vanilla `_top`/
`_side` filename convention and a small explicit override map for the handful of real texture
filenames that don't follow the plain-name convention at all (`water_still.png`, `lava_still.png` --
found and fixed via a failing test before shipping, not by trial and error against a real server).
**`IsometricRenderer`** now maps each face's texture on with a real affine transform (the texture's
square corners mapped onto the diamond top face or the parallelogram wall face, so it isn't just
stretched into a bounding box) and tiles wall faces one texture copy per block of height drop,
matching how a real Minecraft cliff face repeats its texture per block instead of stretching one
copy across the whole drop.

**Fail-safe by design, matching this whole plugin's established approach to anything that touches
the network or an external resource:** the download, jar parsing, and texture extraction are all
wrapped so that any failure at any stage -- no network access, a firewall, a bad version string, a
version whose block set doesn't match -- silently leaves `textureAtlas` unset rather than blocking
startup or crashing a scan; every face just keeps using v1.0.0's flat colors until (or unless) real
textures become available, and a `Real block textures: ...` line in `/nexusscan status` (loaded
via the new `ScanEngine.hasTextureAtlas()`) reports which of the three states -- disabled, loading,
loaded -- it's actually in right now. The whole load happens on a background thread, kicked off from
`NexusScanPlugin.onEnable()`, and `ScanEngine` picks up the atlas the moment it's ready (read fresh
per chunk dispatch, not snapshotted once at scan start) rather than needing a restart.

Two approximations, both documented rather than silently papered over: grass/leaves textures are a
neutral mask in the real game meant for per-biome tinting, which NexusScan approximates by tinting
with `BlockColorPalette`'s existing representative color instead of real per-biome color data; and a
`isometric.minecraft-version` mismatch with the server's real version doesn't error, it just leaves
newer/renamed blocks on their flat-color fallback. New config: `isometric.use-real-textures`
(default `true`) and `isometric.minecraft-version` (default `"1.21.1"`).

Verified with three new standalone test suites: `MiniJsonTest.java` (5 checks on the new
dependency-free JSON parser `MiniJson`, against realistic Mojang-manifest-shaped fixtures, since
this project builds all JSON by hand rather than adding a Maven dependency for it), plus
`AssetDownloaderTest.java` (5 checks: version-manifest and client-download JSON lookup, extracting
top/side/tint from a synthetic in-memory zip built to mirror the real jar's texture layout, cropping
an animated texture strip like water/lava to its first frame only, and an empty/missing-texture jar
degrading to a valid empty atlas instead of throwing), and `TextureRenderTest.java` (3 checks: a
textured top face actually shows the real texture's pattern rather than a flat fill, an unknown
material falls back pixel-identical to the old flat-color render, and a wall face's tiled texture is
correctly shaded darker than the raw texture) -- plus a rendered checkerboard-texture preview PNG,
visually inspected before shipping, confirming the affine-mapping and per-block wall tiling actually
look right, not just pass the pixel-count assertions.

## v1.0.0 -- a real isometric 3D renderer, replacing the flat tile model entirely

Built from a firm, explicit decision after weighing embedding BlueMap directly: "I can't use Blue
Maps. I can't use Dynmaps. I'm using them as a reference. It needs to be just like them" -- BlueMap
was confirmed (via its own docs) to only render already-generated/inhabited chunks and to have a
`min-inhabited-time` filter that would have solved the exact NexusTerra-pollution problem
`ChunkVisitTracker` exists for, but it was rejected anyway on the grounds that it "can't handle my
map size, with the Ender and the Nether and the Overworld" -- meaning NexusScan itself had to
become the thing that looks like Dynmap/BlueMap, not adopt them.

v0.5.0 through v0.8.0 all improved the same flat, one-flat-color-per-column tile in different ways
(bigger, sharper, shaded) -- every one of those was patching a renderer with a hard ceiling built
into its data model: one color sample per column can never show a wall, a roof, or real height, no
matter how much resolution or shading gets thrown at it. v1.0.0 doesn't patch that renderer again;
it replaces it. **`IsometricRenderer`** (new) projects each chunk into a real 2:1 isometric
diamond-tile picture: every column becomes a shaded top face, plus a shaded wall face wherever it's
taller than its east or south neighbor, drawn back-to-front (increasing `x+z`, the standard
heightfield painter's algorithm) so nearer geometry correctly overdraws farther geometry. This adds
zero extra chunk loads and zero extra main-thread work over the v0.7.0/v0.8.0 baseline -- it's
still exactly one `getHighestBlockYAt`/`getBlockType` sample per column, feeding a different
drawing step instead of a different (and much more expensive) data-gathering step. The honest limit
that remains: this is a heightfield renderer, not voxel ray-casting -- it can show real walls,
height, and outdoor structure, but not what's under an overhang or inside an open structure. Full
interior detail would need a fundamentally heavier renderer walking real voxel data, a genuinely
bigger undertaking than this version.

Two design details matter beyond the projection itself. First, every tile's height window is
measured from a fixed, config-wide `isometric.baseline-y` (default 64, sea level) rather than each
chunk's own local minimum height -- an early version of this normalized locally per-chunk, which
looked fine in an isolated single-tile test but would have made neighboring chunks' tiles float at
inconsistent baselines and fail to compose into one coherent map, which is the entire point of a
world map. Caught and fixed before shipping. Second, canvas size is fully fixed and computed
analytically (`IsometricRenderer.computeBounds`, driven only by `isometric.pixels-per-block` and
`isometric.height-window-blocks`) rather than varying per chunk, so every tile is the same
predictable size regardless of terrain -- terrain taller than the configured window gets its
topmost relief clamped rather than growing the tile.

`ScanEngine` now branches on the new `rendering-mode` config key (`isometric` by default, `flat`
kept as a fallback/rollback running the untouched pre-v1.0.0 `ChunkScanner` path). Isometric tiles
currently push to the Base44/Kairos webhook and write locally at a single native resolution (one
tile per chunk) rather than through the old multi-zoom `IncrementalPyramid` -- there's no
coarser-zoom overview pyramid for diamond-shaped isometric tiles yet, a real known follow-up, not
pretended away (see the README's "Known limitations"). The webhook manifest and local
`manifest.json` both now carry the actual projection parameters (`pixelsPerBlock`, `baselineY`,
`heightWindowBlocks`, `tileWidth`, `tileHeight`) the Base44/Kairos frontend needs to place each
chunk's tile correctly -- getting isometric tiles actually showing on the site is real frontend
work on that side, spelled out in the README, not a drop-in replacement for the old Leaflet viewer.

Multi-world support (Nether/End) was **not** added in this version, deliberately -- it's real
future work, kept to the user's own original stated scope (Overworld first, Nether/End as later
updates) rather than bundling a second large refactor into the same version as the renderer
rewrite.

Verified with a new standalone test suite (`IsoTest.java`, 7 checks: seamless tiling on flat
terrain, canvas size matches the analytical `computeBounds` formula exactly, a real height step
produces an actual shaded wall face -- not just a color difference between two flat squares, a flat
chunk produces zero wall pixels while a stepped one produces strictly more opaque pixels, an air
column renders nothing without crashing, an extreme cliff clamps to the configured window without
growing the canvas, and every possible drawn vertex across a spread of pixel sizes/height windows
stays within the analytically-computed bounds) plus a rendered PNG of a synthetic hill-and-structure
scene, visually inspected before shipping -- not just numerically asserted -- to confirm the
geometry actually reads as a real isometric 3D picture rather than merely satisfying the test math.

## v0.8.0 -- relief shading (there's finally something to see when you zoom in)

Built from: "I'm not sure if it's you or base forty four, but the graphics are still garbage. I
can't zoom in and see anything. It's super pixelated." v0.5.0 already fixed actual blur (tiles were
tiny 16x16 images being smoothed by browser upscaling), but that left a different problem
untouched: every block rendered as one flat, unmodulated color. A color alone carries no shape
information -- zooming into a flat-colored tile just shows a bigger flat-colored square, forever,
no matter the resolution. That's almost certainly what read as "pixelated garbage, can't see
anything": there was never anything *to* see past the raw color, unlike Dynmap/BlueMap's shaded
renders where terrain actually looks like terrain.

**`ChunkScanner`** now relief-shades every column (`relief-shading.enabled`, default on): each
column is brightened or darkened relative to its west neighbor's height -- rising ground reads
brighter, falling ground reads darker (`relief-shading.strength`, default `0.06` = 6% per block of
relative elevation, clamped internally so an extreme cliff can't blow a pixel out to pure
white/black). This is the same basic technique Dynmap and BlueMap's own lightweight "flat" render
modes use, and it uses only height data already present in the chunk's own snapshot -- no new chunk
loads, no extra main-thread work, nothing that touches the lag-reduction work from v0.7.0. A
constant, unbroken slope shades uniformly (relief shading reflects *local* slope, not cumulative
elevation), while an actual hill's uphill and downhill sides now render with real, visible
contrast -- ridgelines, cliffs, riverbanks, and coastlines become distinguishable shapes instead of
a solid-colored blob.

One honest trade-off: the west-most column of every chunk has no west-neighbor height data
available without loading the neighboring chunk, so it's left unshaded -- a faint, largely
unnoticeable seam every 16 blocks. Loading an extra chunk per scanned chunk just to shade one edge
column would meaningfully undercut the v0.7.0 lag-reduction work for a barely-visible improvement,
so this was a deliberate call, documented in both config.yml and code.

Also worth knowing, independent of anything this version changes: there's a hard ceiling on zoom
detail no shading trick can get past -- NexusScan samples exactly one block per column, so zooming
in past the point where one block covers a few real screen pixels will always show a single colored
(now shaded) square for that block, the same ceiling vanilla Minecraft's own map item has. Getting
real detail past that point would mean rendering actual block textures per pixel instead of a flat
representative color -- a fundamentally heavier renderer, in tension with the original "lightweight,
no 200GB pre-render" spec and the v0.7.0 lag-reduction work. Not built here; flagged in case it
turns out to still be wanted after this fix.

**Verification:** recompiled with `javac -Xlint:all -Werror`: 0 errors, 0 warnings. Wrote a
standalone test confirming: a constant-slope ramp shades every sloped column identically (correct
local-slope behavior) and brighter than flat ground; a real hill's uphill and downhill sides render
with measurably different brightness (terrain shape is genuinely visible); the west-edge column is
always left unshaded regardless of the enabled flag; an extreme 100-block cliff is clamped rather
than blown out to pure white or crushed to pure black; and `relief-shading.enabled: false`
reproduces the exact flat-color v0.7.0 behavior with zero variation across a tile.

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
