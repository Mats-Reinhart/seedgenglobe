# SeedGenGlobe

Fast Distant Horizons LODs for **Big Globe** worlds.

Distant Horizons (DH) normally builds distant terrain by generating real chunks, which for Big Globe means its whole
chunk pipeline (terrain layers, structures, features, lighting) for every chunk in view. Seed-based generators such
as *Distant Horizons SeedGen* skip that by predicting terrain straight from the world's generation algorithm, but they
read vanilla's noise router, and Big Globe does not use it: its terrain comes from its own scripted, column-based
generator. SeedGenGlobe is the same idea for Big Globe. It asks Big Globe's own column scripts and terrain layers
for the terrain of each LOD column, at the spacing DH needs, and hands DH finished LOD sections.

## Why it is so much faster

DH asks for terrain in sections of 64 x 64 LOD columns. At detail level *d* each column stands for a 2^d x 2^d block
area, so one section covers 16 x 4^d chunks:

| detail | section size (blocks) | chunks covered | work for SeedGenGlobe |
|---|---|---|---|
| 0 | 64 x 64 | 16 | 4,096 column evaluations |
| 3 | 512 x 512 | 1,024 | 4,096 column evaluations |
| 6 | 4,096 x 4,096 | 65,536 | 4,096 column evaluations |

Generating chunks costs more for every ring of terrain further out; SeedGenGlobe's cost per section stays the same.
Columns are computed in 2 x 2 quads exactly the way Big Globe's chunk generator does, with Big Globe's LOD hints
(`isLod`, the column spacing), which Big Globe's data packs use to skip work that would never be visible.

## What the LODs contain

- Big Globe's terrain layers: surface blocks, water, rivers, snow, the underground (see `undergroundMode`), using the
  same scripts as real chunks, so mountains, coasts and rivers line up with the terrain you later walk on.
- Biomes from Big Globe's biome script, and with `bigGlobeColors` Big Globe's per-position grass, foliage and water
  colours instead of flat per-biome colours.
- Approximate tree canopies from Big Globe's foliage, wood palette and river data, so forests stay forests at a
  distance. Big Globe places trees as features after terrain, so without this distant forests would be bare.
- Ground cover (`groundCover`): the short grass, ferns, dead bushes, flower patches, cherry petals, leaf litter, bushes,
  sticks and rocks Big Globe's surface decorators place, with the same chances and Big Globe's own flower patch layout
  (exact on servers and in singleplayer, statistical on multiplayer clients, which do not receive the flower
  configuration). Distant Horizons colours a block by the plants on it, so without this generated LODs looked flatter
  and lighter than LODs of explored chunks, which showed as a square around the spawn chunks.
- Structures (`structures`): Big Globe's obelisks, mega trees, villages, houses, lakes and the rest, both the terrain
  they shape and the blocks they place, found and built by Big Globe's own structure placement, so they stand where
  they will stand in real chunks. Every structure appears in LODs up to `structureDetail` (default 5: LOD cells
  32 blocks wide), small ones only while a LOD cell is at most half their width (houses, wells and campfires to
  about detail 3, decided from the first 32 of each set SeedGenGlobe finds); two levels coarser still the large ones
  (lakes, mega trees), and obelisks, which are huge and rare, at every distance. Structures Big Globe tags as underground are left out, as Big Globe does for its own
  LODs. They need the world seed, so servers and singleplayer have them, client generation in multiplayer does not.
  `/seedgenglobe debug structures` lists the structures around you.
- Not included: ores and other small features, mobs, and anything players built. As you explore, DH replaces LODs
  with data from real chunks as usual.

Distant Horizons keeps the LODs it has and only asks for what is missing, so LODs saved before (by an older
SeedGenGlobe, or before structures were turned on) stay as they are. To regenerate them, close the world and delete
Distant Horizons' `DistantHorizons.sqlite` file in its save folder (one per dimension, in the dimension's `data`
folder).

## How fast it generates

Distant Horizons decides which sections it needs; SeedGenGlobe decides in which order and how many run at once. A
section takes a few CPU-seconds whatever its size (much more with structures far out), so the CPU sets the pace.

- Every section goes through one queue on SeedGenGlobe's own threads. A thread always takes
  the waiting section nearest to a player, worked out when it takes it, so generation follows you as you move and
  fixes holes and coarse terrain next to you before it expands outward. Sections Distant Horizons asks for, sections
  a multiplayer client generates and the Xaero LOD map's own sections share the queue.
- `cpuLoad` sets the thread count from the CPU's physical cores, like Distant Horizons' own CPU setting: Minimal,
  Low, Balanced (the default, two cores left for the game), High and Extreme. More threads than cores make every
  section slower and the server lag without generating any faster. `generatorThreads` sets an exact number instead.
- The threads run at normal priority, like the server's and Distant Horizons' own threads, and drop below normal
  while the server falls behind (`threadPriority`, Automatic). In 1.3.0 they always ran below normal, and on Windows
  got only the CPU time the server and Distant Horizons left over: the same sections took several times as long while
  those were busy. `threadPriority=lower` brings that back; Minimal and Low CPU load always run at low priority.
- On its own, Distant Horizons keeps only its thread count plus one sections in flight, and stops asking while its
  render loader or file queue holds five tasks per thread, so on machines where it runs few threads it asks for very
  few sections at a time, and none while it turns finished sections into render data. With `dhRequestBoost` it hands
  SeedGenGlobe as many sections as SeedGenGlobe's queue holds, and while SeedGenGlobe's threads are about to run out
  of sections it keeps asking until its queues hold the `memoryLoad` limit (8, 12 or 20 tasks per thread); back to
  its own limits while memory is short. With enough sections waiting, its own limit applies, so generation and
  render loading take turns instead of fighting over the CPU.
- Distant Horizons also stops asking while more than 20 LOD updates per thread from loaded chunks wait, which
  happens when you fly over unexplored land. `dhChunkLodBuilding=false` stops it from building LODs from the chunks
  players load in dimensions SeedGenGlobe generates, which leaves its threads to SeedGenGlobe; player builds then no
  longer show in LODs.
- Server tps priority (`yieldToServer`) runs fewer sections at once while the server falls behind 20 ticks per
  second or the heap is nearly full, and speeds up again afterwards.
- Progress: the overlay (`progressOverlay`) shows sections running and waiting, the speed, how far out LODs and the
  map are complete and what slows generation down; the chat (`chatProgress`) says when they are complete out to 64,
  128, 256, ... chunks and when everything is done; and while sections are generated the log gets a summary
  every minute (`statsLogSeconds`), like `SeedGenGlobe [minecraft:overworld]: last 60 s: 140 started, 138 finished
  (2.3 per second), 5.8 at a time, 2521 ms per section (terrain 840 ms, structures 1210 ms, ...); ...; complete out
  to 192 of 1,024 chunks`, followed by the threads and what Distant Horizons' queues hold. Distances are in chunks,
  like Distant Horizons' render distance, or in blocks with `progressUnit=blocks`. When a world closes, the log also
  gets what structures cost, by structure set. The first line says
  when Distant Horizons first asked for sections, and `logSections` logs every section as it starts and finishes.

## Requirements

- Minecraft 26.1.x on Fabric (built for 26.1.2), Fabric Loader 0.19.5 or newer (0.20.1 works), Fabric API, Java 25
- Distant Horizons 3.0.x (API 6). Built and checked against 3.0.3-b with the atlasfix patch (which only touches
  DH's Iris texture code, so the plain 3.0.3-b works the same). On other versions the generator keeps working; any
  of SeedGenGlobe's DH mixins that do not fit are left out and listed on the status page
- Big Globe 6.x (checked against 6.3.0)
- Optional: Mod Menu 18.x (the settings screen), Xaero's World Map 1.47.x and Xaero's Minimap 26.6.x (see below),
  Vertigo, Iris, Sodium, Lithium, C2ME, Noisium, ScalableLux (see Compatibility)

## Where to install it

| Setup | Server | Client | What happens |
|---|---|---|---|
| Singleplayer | | SeedGenGlobe | The integrated server generates LODs with SeedGenGlobe. |
| Server with DH | SeedGenGlobe | optional | The server generates LODs with SeedGenGlobe; DH sends them to players. SeedGenGlobe on the client adds Big Globe colours and the Vertigo guard. |
| Server without DH | not needed | SeedGenGlobe | Each player's client generates LODs locally from the terrain scripts Big Globe syncs to it (the world seed is never needed: Big Globe only sends a hashed column seed). |

For the last row Big Globe has to sync its terrain layers. It does so when its `lodRendering` option is on, which its
default `AUTO` setting does on servers without DH or Voxy. If the client log says the layers were not synced, set
`lodRendering.enabled` to `ON` in the server's Big Globe config, or install SeedGenGlobe on the server instead.

On a server with DH, players only ask the server for full-detail sections (64 x 64 blocks) unless DH's experimental
*N-sized generation* is turned on, on the server and on the clients (DH config: server > experimental >
`enableNSizedGeneration`, command name `generation.nSized`). Full-detail sections are still far cheaper with
SeedGenGlobe than generating chunks, but the large speedup for far terrain comes from coarse sections, so turn the
option on. SeedGenGlobe logs a reminder and shows one on its status page while it is off. Singleplayer and client-side
generation are not affected by this option.

## Xaero's World Map and Minimap

Xaero's maps only map chunks the game loads, so they cover your render distance while Distant Horizons shows terrain
far beyond it. With `xaeroLodMap` SeedGenGlobe also writes the **surface of the whole LOD radius** into Xaero's World
Map, and the minimap shows it too (its *Use World Map Mod Chunks* option, on by default, draws World Map data;
`xaeroMinimapLodMap=false` keeps the minimap to explored land, see below). `xaeroLodMap` can be switched on and off
while playing.

- The map is drawn from the sections SeedGenGlobe generates for Distant Horizons, as they are generated: every one
  is read the way Xaero reads a chunk column and merged into the map, so the map shows exactly the terrain, trees,
  ground cover and structures of the LODs, at their detail level (finer near you, coarser far away; coming closer
  refines it), and grows at the pace Distant Horizons generates.
- Distant Horizons does not generate what it already has, so LODs from earlier sessions, LODs built from explored
  chunks and LODs downloaded from a Distant Horizons server never pass through SeedGenGlobe. The map computes those
  areas itself, as sections of its own in the shared queue, nearest to a player first: gaps anywhere within the map
  radius (far ones quickly at a coarser level first, then refined), and finer detail where the map is coarser than
  Distant Horizons' detail levels (its horizontal quality setting), the latter only behind the area Distant Horizons
  is generating at the moment, so it never computes what is about to arrive anyway. While Distant Horizons has
  sections running, the map uses about half the threads.
- Near you the map has one pixel per block (`mapFullDetailRadius`, 32 chunks by default) whatever Distant Horizons'
  detail is there, and so does every coarse or empty tile next to a chunk Xaero mapped itself, so explored land never
  surrounds a patch of coarse terrain.
- Each map pixel is what Xaero itself would record for that column (Xaero's own rules for grass, flowers, glass,
  water and ice decide what shows), with **Big Globe's surface biome**, so hovering the map names the biome you would
  find on the ground.
- Real terrain always wins: Xaero maps a loaded chunk over the LOD tile right away, and LOD tiles are only written
  where Xaero has nothing (or an empty chunk, see Vertigo below). Explored areas and anything players built are
  never touched.
- Radius: Distant Horizons' render distance, or `xaeroLodMapRadius` chunks. Regions (512 x 512 blocks) Xaero holds in
  memory, the minimap's and the open world map's, get new LOD data within a few seconds; other regions are loaded into
  Xaero for it once their data has stopped changing for a while, nearest first. Xaero builds the map textures itself
  and that sets the pace for those: expect a 2048 chunk radius (about 13,000 regions) to take hours to complete the
  first time. SeedGenGlobe spends at most 2 ms per game tick on it, and logs the map's progress every minute.
- Multiplayer maps (and singleplayer maps with a custom multiworld) are saved by Xaero, so each region is written
  once. Singleplayer maps are rebuilt by Xaero from the world save whenever it loads a region, and Xaero keeps only a
  texture cache of them: SeedGenGlobe hands regions back to Xaero from that cache in later sessions, and puts LOD
  tiles back into regions Xaero rebuilds.
- Disk: SeedGenGlobe keeps its copy of the map in `.minecraft/seedgenglobe/lodmap/` (up to about 100 MB for a 2048
  chunk radius; each SeedGenGlobe version that changes the map's format starts a new folder there, and older ones
  can be deleted). Xaero's own files for the filled regions grow with the radius and can reach a few GB for a 2048
  chunk radius; lower `xaeroLodMapRadius` if that matters.
- With Vertigo, chunks whose surface has not been synced are no longer mapped as empty black chunks (shown at
  y = -1024 with the plains biome of an unsynced section); Xaero maps them once the surface arrives. Such empty chunks
  already in a saved map get LOD terrain until then.
- Not included: Big Globe's per-position grass and foliage colours. Xaero tints the map per biome, as it does for
  explored chunks.
- Minimap: with `xaeroMinimapLodMap=false` the minimap leaves out every 64 x 64 block square that holds only LOD
  tiles, and the World Map keeps showing them. Squares with some explored chunks are drawn whole, so explored land
  keeps a fringe of LOD terrain at most one square wide.

The progress overlay, the chat messages and the status page show the map's progress, and the log gets a summary of
it every minute.

## Checking that it works

1. Start the world and look for `SeedGenGlobe now generates Distant Horizons LODs for minecraft:overworld` in the log
   (`... on this client` in multiplayer without a DH server), then for the line saying when Distant Horizons first
   asked for sections.
2. The progress overlay shows sections running and waiting, the speed and how far out LODs are complete. The status
   page (the Status button in SeedGenGlobe's settings, through Mod Menu) shows the same per dimension, with the
   threads, Distant Horizons' queues, the Xaero LOD map and anything that could not be set up;
   `/seedgenglobe debug status` shows the server's part of it.
3. `/seedgenglobe debug verify` compares predicted surface heights with the real chunks loaded around you. Expect most
   columns exact or within 2 blocks; trees, structures and player builds account for the rest.
4. `/seedgenglobe debug benchmark` times generation on one thread at detail 0, 3 and 6, for example
   `/seedgenglobe debug benchmark 32 6`.

## Regenerating LODs

To throw away a world's LODs and have them generated again (after changing settings that shape the terrain, or a
SeedGenGlobe update), use **Regenerate LODs...** on the General page of SeedGenGlobe's settings (singleplayer, with
the world open), or send `/seedgenglobe regenerate` (operators; it says what will be deleted, and sending it again
within 30 seconds confirms).

This deletes Distant Horizons' LOD database of every dimension of the world (`DistantHorizons.sqlite` in each
dimension's `data` folder, with its `-wal`, `-shm` and `-journal` files) and, in singleplayer, SeedGenGlobe's copy of
the Xaero LOD map of those dimensions (`seedgenglobe/lodmap/...` in the game folder). The world itself, Xaero's map of
explored chunks and waypoints are not touched.

Distant Horizons keeps its databases open while the world runs, so they are not deleted at once: confirming writes
`seedgenglobe/regenerate-lods.txt` into the world folder, and the files are deleted when the world closes, once
Distant Horizons has closed them (Save and Quit to Title in singleplayer, stopping a server). Anything still open or
locked then is deleted the next time the world loads, before Distant Horizons opens it. Open the world again and
Distant Horizons asks for every LOD anew. Until you leave, **Keep the LODs (undo)** (the same button) or
`/seedgenglobe regenerate cancel` takes it back; so does deleting that file.

## Commands

Everything players need is in the settings screen, the progress overlay and the status page. The commands are
for operators (in singleplayer, with cheats allowed):

| Command | What |
|---|---|
| `/seedgenglobe regenerate` | deletes this world's LODs so they are generated again (see above); send twice within 30 seconds |
| `/seedgenglobe regenerate cancel` | keeps the LODs after all, until the world closes |
| `/seedgenglobe debug status` | the server's side of the status page: settings, threads, per-dimension progress and Distant Horizons' queues, detected mods, anything that could not be set up |
| `/seedgenglobe debug verify [radiusChunks]` | predicted surface vs. loaded chunks (default radius 8, max 32) |
| `/seedgenglobe debug benchmark [sections] [detail]` | generation speed, on a background thread |
| `/seedgenglobe debug probe` | the predicted column at your position |
| `/seedgenglobe debug structures [radiusChunks]` | the nearest structures LODs get around you (default 64 chunks; beyond 128 chunks only large ones, beyond 512 only obelisk-sized ones) |

1.2.0's `/seedgenglobe`, `/seedgenglobe status` and `/seedgenglobeclient` are gone: the status page has their content.

## Configuration

In game: Mod Menu > SeedGenGlobe (the button next to the mod), one page per category, every setting explained in
its tooltip. After the page buttons, **Enabled** (green) / **Disabled** (red) is the master switch for everything
SeedGenGlobe does; it takes effect after a game restart. Like Minecraft's own option screens, the title and the Status button (top right) stay at the top,
Page defaults, Cancel and Save & quit stay at the bottom, and the page buttons and settings between them scroll when
the window is too small. Settings with more than three values are sliders, the others buttons that step through
their values. Most settings apply at once; the others say in their tooltip, and in the note above the bottom buttons
(or the Save & quit tooltip in a short window), whether they apply when a world is loaded again or after a restart.
Without Mod Menu, edit `config/seedgenglobe.properties` (written with comments on first start, read when the game
starts).

The CPU load presets give these thread counts. Java only sees logical CPUs (threads the CPU runs at once), so
SeedGenGlobe counts half of them as physical cores on CPUs with 8 or more. The priority column is each preset's own
lower priority: Minimal and Low always run at it, the others with `threadPriority=lower` (as in 1.3.0) and, with the
default `auto`, while the server falls behind (at most one below normal); otherwise they run at normal priority.

| `cpuLoad` | Threads | Lower priority | 16 logical CPUs | 8 logical CPUs | 4 logical CPUs |
|---|---|---|---|---|---|
| `minimal` | 1 | lowest | 1 | 1 | 1 |
| `low` | a quarter of the physical cores | low | 2 | 1 | 1 |
| `balanced` | physical cores - 2 (at least 1, at most logical - 1) | below normal | 6 | 2 | 2 |
| `high` | logical - max(2, a quarter of them) | slightly below normal | 12 | 6 | 2 |
| `extreme` | logical - 1 | normal | 15 | 7 | 3 |

`memoryLoad` sizes what SeedGenGlobe keeps in memory; `auto` picks Low below 4 GB of heap, Medium below 8 GB and High
above:

| `memoryLoad` | Sections waiting per thread | Structure tiles remembered | LOD map cache | Xaero regions loaded for the map | Distant Horizons queue per thread |
|---|---|---|---|---|---|
| `low` | 1 | 4,096 | about 8 MB | 2 | 8 |
| `medium` | 2 | 16,384 | about 32 MB | 6 | 12 |
| `high` | 3 | 49,152 | about 64 MB | 10 | 20 |

(A Xaero region Xaero would not have loaded itself takes 10 to 20 MB while SeedGenGlobe fills it. Distant Horizons'
own queue limit is 5 tasks per thread; the last column applies with `dhRequestBoost` while SeedGenGlobe runs short
of sections.)

**General**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `enabled` | Enabled / Disabled (after the page buttons) | `true` | Master switch for everything SeedGenGlobe does: Distant Horizons LODs, multiplayer generation, the Xaero LOD maps, the Vertigo guard, Big Globe colours, progress and LOD regeneration. Off: SeedGenGlobe does nothing, and Distant Horizons and Xaero's maps behave as if it were not installed (a LOD regeneration confirmed before still completes). *Takes effect after a game restart.* |
| `dhGeneration` | Distant Horizons LODs | `true` | Generate Distant Horizons LODs for Big Globe dimensions from Big Globe's terrain scripts. Off leaves them to Distant Horizons' own (much slower) generator; the Xaero LOD map still works. *Takes effect when a world is loaded again.* |
| `dhChunkLodBuilding` | DH LODs from loaded chunks | `true` | Let Distant Horizons also build LODs from the real chunks players load, in dimensions SeedGenGlobe generates. While many of those wait, it stops asking SeedGenGlobe for sections. Off frees its threads for SeedGenGlobe, but buildings and other changes players make no longer show in LODs. |
| `xaeroLodMap` | Xaero's World Map LOD map | `true` | Map the surface of the whole LOD radius on Xaero's World Map, from Big Globe's terrain scripts. Real chunks replace it as soon as Xaero maps them. Off stops mapping more. On a multiplayer map, LOD tiles already on it stay until Xaero maps those chunks; a singleplayer map loses them wherever Xaero rebuilds a region from the world save. |
| `xaeroMinimapLodMap` | Xaero's Minimap LOD map | `true` | Show that LOD terrain on Xaero's Minimap too. Off: the minimap leaves out every 64-block square that has only LOD tiles, so it shows the chunks Xaero mapped itself (with LOD tiles that share a square with them). Only matters while the minimap takes its chunks from the World Map. |

**Performance**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `cpuLoad` | CPU load | `balanced` | How many threads compute LOD sections and map sections. Balanced leaves two cores for the game; High and Extreme generate faster but the game will stutter more. Minimal and Low also run at low priority. (`minimal`, `low`, `balanced`, `high`, `extreme`) |
| `memoryLoad` | Memory load | `auto` | How much memory SeedGenGlobe keeps for speed: queued sections, remembered structures, map regions in memory, and how far Distant Horizons' queues may grow. Automatic picks from the memory Java was given. (`auto`, `low`, `medium`, `high`) |
| `yieldToServer` | Server tps priority | `true` | Run fewer sections at once while the server cannot keep up 20 ticks per second, and speed up again once it does. |
| `threadPriority` | Thread priority | `auto` | Priority of the generator threads. Automatic: normal, like the server's and Distant Horizons' threads, and lower while the server cannot keep up (with Server tps priority on). Normal: always normal. Lower: always below normal, as in 1.3.0, which leaves generation only the CPU time the game does not use. Minimal and Low CPU load always run low. (`auto`, `normal`, `lower`) |
| `dhRequestBoost` | Feed SeedGenGlobe faster | `true` | Let Distant Horizons hand SeedGenGlobe as many sections as SeedGenGlobe's queue holds (its own limit is its thread count plus one), and, while SeedGenGlobe is about to run out of sections, keep asking although its render and file queues are busy, up to the memory load's limit (it stops at 5 tasks per thread). Back to its own limits while memory is short. |
| `generatorThreads` | Threads | `0` | Threads for LOD sections. Automatic follows the CPU load setting. (0-64, 0 = automatic) |

**Xaero's maps**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `xaeroLodMapRadius` | LOD map radius | `0` | How far the LOD map reaches, in chunks. Automatic follows Distant Horizons' render distance. (0-4096, 0 = automatic) |
| `mapFullDetailRadius` | Full detail radius | `32` | Within this many chunks of the player, and right next to chunks Xaero has mapped, the LOD map shows one pixel per block. Farther out it gets coarser, like LODs. (0-256, 0 = off) |

**Progress**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `progressOverlay` | Progress overlay | `working` | A few lines on screen with what SeedGenGlobe is doing: sections running and waiting, speed, and how far out LODs and the map are complete. (`off`, `working`, `always`) |
| `overlayPosition` | Overlay position | `top_right` | Screen corner of the progress overlay. (`top_left`, `top_right`, `bottom_left`, `bottom_right`) |
| `chatProgress` | Chat messages | `milestones` | Milestones: when LODs or the map are complete out to 1, 2, 4, 8, ... thousand blocks, and when all is done. Everything: also when generation starts and resumes. (`off`, `milestones`, `all`) |
| `progressUnit` | Distances in | `chunks` | Count how far LODs and the map are complete in chunks, like Distant Horizons' render distance, or in blocks. Applies to the overlay, chat messages, the status page and the log. (`chunks`, `blocks`) |
| `statsLogSeconds` | Log summary interval | `60` | While sections are generated, log a summary (started and finished sections, speed, time per phase, Distant Horizons' queues) this often. (0-3600, 0 = never) |
| `logSections` | Log every section | `false` | Log a line when each section starts and when it finishes. Many lines; for finding out what generation is doing. |

**LOD terrain**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `trees` | Trees | `true` | Approximate tree canopies so forests remain visible in the distance. *Takes effect when a world is loaded again.* |
| `groundCover` | Ground cover | `true` | Big Globe's grass, flowers, leaf litter and similar ground cover on generated LODs, so they match LODs of explored chunks. *Takes effect when a world is loaded again.* |
| `structures` | Structures | `true` | Big Globe's structures in generated LODs (obelisks, mega trees, villages, houses, lakes, ...): the terrain they shape and the blocks they place. Server and singleplayer only. *Takes effect when a world is loaded again.* |
| `structureDetail` | Structure detail | `5` | Coarsest detail level (0-15) that gets every structure (5 = LOD cells 32 blocks wide). Small structures stop earlier, once a LOD cell is more than half their size (houses and campfires at about 3). Two levels coarser still get lakes and mega trees; obelisks are in every LOD. (0-15) *Takes effect when a world is loaded again.* |
| `undergroundMode` | Underground | `fill` | How much of the underground to compute. Fill is fastest and looks the same from above; Big Globe's setting uses Big Globe's own Distant Horizons option. (`none`, `fill`, `carve`, `decorate`, `bigglobe`) *Takes effect when a world is loaded again.* |
| `bigGlobeColors` | Big Globe colours | `true` | Tint distant grass, leaves and water with Big Globe's own colour scripts instead of flat per-biome colours. *Takes effect after a game restart.* |
| `vertigoGuard` | Vertigo guard | `true` | With Vertigo, keep chunks whose top or bottom sections were not synced from overwriting LODs and Xaero's map. *Takes effect after a game restart.* |

**Multiplayer**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `clientGeneration` | Generate on this client | `true` | Multiplayer: generate LODs on this client from the terrain Big Globe syncs, when the server has no Distant Horizons. *Takes effect after a game restart.* |
| `clientGenerationWithDhServer` | Also with a DH server | `false` | Multiplayer: generate locally even when the server runs Distant Horizons, instead of downloading LODs from it. *Takes effect when a world is loaded again.* |

**Advanced**

| Key | In game | Default | Meaning |
|---|---|---|---|
| `maxDetailLevel` | Coarsest detail level | `15` | Coarsest Distant Horizons detail level generated directly (0-15). LOD columns are 2^n blocks wide. (0-15) *Takes effect when a world is loaded again.* |
| `sampleCellCenter` | Sample cell centres | `true` | Sample the centre of each LOD cell instead of its corner. *Takes effect when a world is loaded again.* |
| `fullStructurePrecompute` | Full structure precompute | `false` | Before structures reshape the terrain around them, compute every column value any of Big Globe's structure overriders reads, as Big Globe's chunk generator does (1.3.0). Off computes only what the overriders of the structures nearby read, which saves time next to structures; turn on if LODs there differ from the real terrain. *Takes effect when a world is loaded again.* |
| `updateParentSections` | Update parent sections | `false` | Let generated sections also be merged into their coarser parents (Distant Horizons' default). Such a parent holds only part of its area until all of it is generated, and Distant Horizons draws it in place of finer sections still loading. Off avoids those holes; Generate parent sections makes the parents whole instead. *Takes effect when a world is loaded again.* |
| `generateParentSections` | Generate parent sections | `true` | Let Distant Horizons also ask for the coarser sections it draws in place of finer ones while those load and after you move away, in dimensions SeedGenGlobe generates. Off: those stay empty, so moving shows holes that only close when you come near. Costs about a third more sections. |
| `generatorPriority` | (file only) | `2026` | Distant Horizons world generator override priority. Must differ from other generator mods; higher than Big Globe's own (10). (11-1000000) *Takes effect when a world is loaded again.* |

## Compatibility

- **Vertigo**: Vertigo syncs only the chunk sections near you vertically and reports the rest as air. DH would turn
  such chunks into LODs with mountains cut flat (or no surface at all when you are underground or flying high).
  With `vertigoGuard`, a chunk only reaches DH once Vertigo has synced the part that holds its surface; held-back
  chunks are offered again when more sections arrive. Only active when the server runs Vertigo. The same check keeps
  such chunks off Xaero's World Map and Minimap, in singleplayer too.
- **Xaero's World Map / Minimap**: see the section above.
- **Iris**: LODs carry real block states and biomes, so shader packs get the right DH materials for leaves, water and
  snow. The atlasfix DH build is supported.
- **C2ME**: SeedGenGlobe never uses Big Globe's chunk worker pool that C2ME drives; LOD columns are computed on
  SeedGenGlobe's own threads. The Balanced CPU load leaves two cores for the game, chunk loading included; choose Low
  if chunks load slowly while LODs generate.
- **ScalableLux**: LOD light comes from Big Globe's column data, so distant terrain queues no light engine work.
- **Noisium, Lithium, Sodium**: no interaction needed. Big Globe does not use the vanilla noise router Noisium
  optimises, SeedGenGlobe touches no game logic Lithium changes, and DH renders LODs itself.
- **Distant Horizons SeedGen**: it turns itself off in Big Globe dimensions, so the two do not compete.
- **Voxy**: not supported.

SeedGenGlobe's generator uses only DH's public API. Multiplayer client generation, the Vertigo guard, Big Globe
colours, `dhChunkLodBuilding` and `dhRequestBoost` need small mixins into DH internals, the Xaero integration small
mixins into Xaero's map writers and the minimap, and the structure guard one into Minecraft's structure manager.
Before applying each DH and Xaero mixin SeedGenGlobe checks that every field, method and call it needs exists in the
installed build, and leaves out (and reports on the status page) any that do not fit, instead of crashing.

## Building

The release jar was built with `build-offline.sh`, which compiles against the real Distant Horizons, Big Globe,
Xaero and Mod Menu jars plus compile-only stubs for Minecraft classes (the build machine had no access to Mojang's or
Fabric's servers), and was then checked by a bytecode link check against Distant Horizons, Big Globe, Xaero's World
Map and Minimap and Fabric API, including every mixin target. `tools/offline-build/README.md` explains how.

For normal development use Gradle with Fabric Loom: put the Distant Horizons and Big Globe jars (and optionally
Vertigo) into `libs/` (see `libs/README.txt`) and run `./gradlew build`. `./gradlew check` also runs the logic
tests. The Gradle files follow Big Globe's own 26.1.2 setup (Loom 1.18-SNAPSHOT, Gradle 9.8) but could not be run
here, so a first run may need small adjustments.

`test-offline.sh` runs the logic tests without Gradle: ColumnProfile against the data point rules DH enforces and
Big Globe's light model (including a 20,000 column fuzz test), the tree shape functions, the ground cover decorators
against Big Globe's chances and DH's 2 x 2 merge, how a column reads on Xaero's map, the section structures are placed
into (sample points, kept writes, heightmaps, relighting), how sections of every detail level are merged into map
regions (against a block-by-block model, with a fuzz test), the LOD map's region bookkeeping, full-detail radius and
Distant Horizons' detail levels, the section scheduler (nearest first, following the player, cancelling, stopping
part-way, the CPU and memory presets) and the settings file.

## License

SeedGenGlobe is MIT licensed (see `LICENSE`). It contains no code from Big Globe, Distant Horizons, Vertigo or
Xaero's mods. Xaero's World Map and Minimap are All Rights Reserved by xaero96; SeedGenGlobe only calls them at
runtime when they are installed.

Big Globe is All Rights Reserved by Builderb0y, and its license does not allow using its Java code in other
projects without permission. SeedGenGlobe is built against Big Globe's classes and calls them at runtime, like any
add-on, so ask Builderb0y before publishing SeedGenGlobe publicly. Never re-host the Big Globe jar.

Distant Horizons is LGPL-3.0. If you distribute a modified DH build such as the atlasfix, include its notes with it.
