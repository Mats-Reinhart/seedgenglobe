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
- Not included: structures, ores and other features, and anything players built. As you explore, DH replaces LODs
  with data from real chunks as usual.

## Requirements

- Minecraft 26.1.x on Fabric (built for 26.1.2), Fabric Loader 0.19.5 or newer (0.20.1 works), Fabric API, Java 25
- Distant Horizons 3.0.x (API 6). Checked against 3.0.3-b; the atlasfix build works the same, since its patch only
  touches DH's Iris texture code
- Big Globe 6.x (checked against 6.3.0)
- Optional: Xaero's World Map 1.47.x and Xaero's Minimap 26.6.x (see below), Vertigo, Iris, Sodium, Lithium, C2ME,
  Noisium, ScalableLux (see Compatibility)

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
option on. SeedGenGlobe logs a reminder and shows one in `/seedgenglobe` while it is off. Singleplayer and client-side
generation are not affected by this option.

## Xaero's World Map and Minimap

Xaero's maps only map chunks the game loads, so they cover your render distance while Distant Horizons shows terrain
far beyond it. With `xaeroLodMap` SeedGenGlobe also writes the **surface of the whole LOD radius** into Xaero's World
Map, and the minimap shows it too (its *Use World Map Mod Chunks* option, on by default, draws World Map data).

- The map is computed from Big Globe's terrain scripts with the same samplers as the LODs, trees and ground cover
  included, on one low-priority background thread. Like LODs it gets coarser with distance: one map pixel per block
  within 768 blocks, then one per 2, 4, ... up to 64 blocks. Coming closer later refines it.
- Each map pixel is what Xaero itself would record for that column (Xaero's own rules for grass, flowers, glass,
  water and ice decide what shows), with **Big Globe's surface biome**, so hovering the map names the biome you would
  find on the ground.
- Real terrain always wins: Xaero maps a loaded chunk over the LOD tile right away, and LOD tiles are only written
  where Xaero has nothing (or an empty chunk, see Vertigo below). Explored areas and anything players built are
  never touched.
- Radius: Distant Horizons' render distance, or `xaeroLodMapRadius` chunks. Regions (512 x 512 blocks) are filled
  nearest first, so the area around you, which is what the minimap shows, comes first. Xaero builds the map textures
  itself and that sets the pace: expect a 2048 chunk radius (about 13,000 regions) to take hours to complete the
  first time. SeedGenGlobe spends at most 2 ms per game tick on it.
- Multiplayer maps (and singleplayer maps with a custom multiworld) are saved by Xaero, so each region is written
  once. Singleplayer maps are rebuilt by Xaero from the world save whenever it loads a region, and Xaero keeps only a
  texture cache of them: SeedGenGlobe hands regions back to Xaero from that cache in later sessions, and puts LOD
  tiles back into regions Xaero rebuilds.
- Disk: SeedGenGlobe keeps its copy of the map in `.minecraft/seedgenglobe/lodmap/` (up to about 100 MB for a 2048
  chunk radius). Xaero's own files for the filled regions grow with the radius and can reach a few GB for a 2048
  chunk radius; lower `xaeroLodMapRadius` if that matters.
- With Vertigo, chunks whose surface has not been synced are no longer mapped as empty black chunks (shown at
  y = -1024 with the plains biome of an unsynced section); Xaero maps them once the surface arrives. Such empty chunks
  already in a saved map get LOD terrain until then.
- Not included: Big Globe's per-position grass and foliage colours. Xaero tints the map per biome, as it does for
  explored chunks.

`/seedgenglobeclient` shows the map's progress.

## Checking that it works

1. Start the world and look for `SeedGenGlobe now generates Distant Horizons LODs for minecraft:overworld` in the log
   (`... on this client` in multiplayer without a DH server).
2. `/seedgenglobe` shows generation statistics (sections, ms per section, columns per second) for every dimension.
3. `/seedgenglobe verify` compares predicted surface heights with the real chunks loaded around you. Expect most
   columns exact or within 2 blocks; trees, structures and player builds account for the rest.
4. `/seedgenglobe benchmark` times generation on one thread at detail 0, 3 and 6, for example
   `/seedgenglobe benchmark 32 6`.

## Commands

| Command | Who | What |
|---|---|---|
| `/seedgenglobe` or `/seedgenglobe status` | everyone | settings, per-dimension statistics, detected mods |
| `/seedgenglobe verify [radiusChunks]` | operators | predicted surface vs. loaded chunks (default radius 8, max 32) |
| `/seedgenglobe benchmark [sections] [detail]` | operators | generation speed, on a background thread |
| `/seedgenglobe probe` | operators | the predicted column at your position |
| `/seedgenglobeclient` | client | what SeedGenGlobe does on this client (multiplayer generation, Vertigo guard, colours, Xaero map) |

## Configuration

`config/seedgenglobe.properties`, written with comments on first start. Changes apply when a world or server loads.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. |
| `maxDetailLevel` | `15` | Coarsest DH detail level generated directly (0-15). Lower it only to compare against DH's own downsampling. |
| `undergroundMode` | `fill` | `none`, `fill`, `carve`, `decorate`, or `bigglobe` (use Big Globe's own DH underground setting). `fill` is fastest and looks the same from above; `carve` and `decorate` cost more and matter only with cave LODs. |
| `sampleCellCenter` | `true` | Sample the centre of each LOD cell instead of its corner, so coarse LODs are not shifted by half a cell. |
| `trees` | `true` | Approximate tree canopies. |
| `groundCover` | `true` | Big Globe's grass, flowers, leaf litter, bushes, sticks and rocks on generated LODs. |
| `updateParentSections` | `false` | Let each generated section also rewrite its coarser parent sections (Distant Horizons' default). Off, DH builds parents itself, which avoids black holes in the LODs while they are still being built. |
| `clientGeneration` | `true` | Multiplayer: generate on the client when the server has no DH. |
| `clientGenerationWithDhServer` | `false` | Multiplayer: generate locally even when the server runs DH (needs synced terrain layers, see above). |
| `clientThreads` | `0` | Threads for client generation; 0 = DH's thread count, minus one with C2ME or ScalableLux. |
| `vertigoGuard` | `true` | With Vertigo, keep chunks whose top or bottom was not synced from flattening LODs. |
| `bigGlobeColors` | `true` | Big Globe's grass, foliage and water colours for LODs. |
| `xaeroLodMap` | `true` | With Xaero's World Map: map the surface of the whole LOD radius (see above). |
| `xaeroLodMapRadius` | `0` | Radius of that map in chunks, 0 = Distant Horizons' render distance (max 4096). |
| `generatorPriority` | `2026` | DH generator override priority. Big Globe's own DH generator uses 10; change this only if another mod uses the same number. |
| `statsInterval` | `500` | Log a throughput summary every N sections (0 = never). |

## Compatibility

- **Vertigo**: Vertigo syncs only the chunk sections near you vertically and reports the rest as air. DH would turn
  such chunks into LODs with mountains cut flat (or no surface at all when you are underground or flying high).
  With `vertigoGuard`, a chunk only reaches DH once Vertigo has synced the part that holds its surface; held-back
  chunks are offered again when more sections arrive. Only active when the server runs Vertigo. The same check keeps
  such chunks off Xaero's World Map and Minimap, in singleplayer too.
- **Xaero's World Map / Minimap**: see the section above.
- **Iris**: LODs carry real block states and biomes, so shader packs get the right DH materials for leaves, water and
  snow. The atlasfix DH build is supported.
- **C2ME**: SeedGenGlobe never uses Big Globe's chunk worker pool that C2ME drives; LOD columns are computed on DH's
  world generation threads only.
- **ScalableLux**: LOD light comes from Big Globe's column data, so distant terrain queues no light engine work.
  Client generation leaves one thread for chunk loading and lighting when C2ME or ScalableLux are installed.
- **Noisium, Lithium, Sodium**: no interaction needed. Big Globe does not use the vanilla noise router Noisium
  optimises, SeedGenGlobe touches no game logic Lithium changes, and DH renders LODs itself.
- **Distant Horizons SeedGen**: it turns itself off in Big Globe dimensions, so the two do not compete.
- **Voxy**: not supported.

SeedGenGlobe's generator uses only DH's public API. Multiplayer client generation, the Vertigo guard and Big Globe
colours need small mixins into DH internals, and the Xaero integration small mixins into Xaero's map writers;
before applying each one SeedGenGlobe checks that every field, method and call it needs exists in the installed
build, and leaves out (and reports in the status commands) any that do not fit, instead of crashing.

## Building

The release jar was built with `build-offline.sh`, which compiles against the real Distant Horizons and Big Globe
jars plus compile-only stubs for Minecraft classes (the build machine had no access to Mojang's or Fabric's
servers), and was then checked by a bytecode link check against Distant Horizons, Big Globe, Xaero's World Map and
Minimap and Fabric API, including every mixin target. `tools/offline-build/README.md` explains how.

For normal development use Gradle with Fabric Loom: put the Distant Horizons and Big Globe jars (and optionally
Vertigo) into `libs/` (see `libs/README.txt`) and run `./gradlew build`. `./gradlew check` also runs the logic
tests. The Gradle files follow Big Globe's own 26.1.2 setup (Loom 1.18-SNAPSHOT, Gradle 9.8) but could not be run
here, so a first run may need small adjustments.

`test-offline.sh` runs the logic tests without Gradle: ColumnProfile against the data point rules DH enforces and
Big Globe's light model (including a 20,000 column fuzz test), the tree shape functions, the ground cover decorators
against Big Globe's chances and DH's 2 x 2 merge, how a column reads on Xaero's map, and the LOD map's region
bookkeeping.

## License

SeedGenGlobe is MIT licensed (see `LICENSE`). It contains no code from Big Globe, Distant Horizons, Vertigo or
Xaero's mods. Xaero's World Map and Minimap are All Rights Reserved by xaero96; SeedGenGlobe only calls them at
runtime when they are installed.

Big Globe is All Rights Reserved by Builderb0y, and its license does not allow using its Java code in other
projects without permission. SeedGenGlobe is built against Big Globe's classes and calls them at runtime, like any
add-on, so ask Builderb0y before publishing SeedGenGlobe publicly. Never re-host the Big Globe jar.

Distant Horizons is LGPL-3.0. If you distribute a modified DH build such as the atlasfix, include its notes with it.
