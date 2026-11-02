# Changelog

## 1.3.0

### Generation order and speed
- Every LOD section now goes through one queue on SeedGenGlobe's own threads, and a thread always takes the waiting
  section nearest to a player, worked out when it takes it, so the order follows the player as they move: holes and
  coarse terrain next to the player are fixed before generation expands outward. Sections Distant Horizons asks for,
  sections a multiplayer client generates and the Xaero LOD map's own sections share the queue.
- Distant Horizons hands SeedGenGlobe as many sections as SeedGenGlobe's queue holds (`dhRequestBoost`), instead of
  its own thread count plus one, and keeps asking while its render loader and file queue are busy, up to the memory
  load's limit (8, 12 or 20 tasks per thread instead of 5). Low-end machines, where Distant Horizons runs few threads,
  were asked for very few sections at a time before, and none at all while Distant Horizons turned finished sections
  into render data.
- New `cpuLoad` setting (Minimal, Low, Balanced, High, Extreme), like Distant Horizons' own: the thread count comes
  from the physical cores. Balanced, the default, leaves two cores free. In 1.2.0 the thread count followed Distant
  Horizons' (15 threads on an 8-core CPU), which made every section slower and the server lag without generating any
  faster.
- New `memoryLoad` setting (Automatic, Low, Medium, High): queue depth, remembered structures, map regions in memory,
  and how far Distant Horizons' queues may grow. Automatic picks from the heap size.
- `yieldToServer`: fewer sections run at once while the server cannot keep up 20 ticks per second, and more again
  once it does; also while the heap is nearly full.
- New `dhChunkLodBuilding` switch: turn off Distant Horizons' own LOD building from loaded chunks in dimensions
  SeedGenGlobe generates, so its threads are left to SeedGenGlobe (player builds then no longer show in LODs).
  Distant Horizons also stops asking for new sections while many such updates wait.
- The log, the overlay and the status page say what holds Distant Horizons back: its render loader, its file queue
  or its LOD updates from loaded chunks, with the limits that apply.
- `dhGeneration` switch: Distant Horizons LODs off, Xaero LOD map only.

### Xaero's World Map and Minimap
- The map's own sections run on the shared threads, nearest first, instead of one at a time at the lowest priority:
  in 1.2.0 that thread was starved by generation, so gaps next to the player stayed black while the map grew far away.
- Full detail near the player (`mapFullDetailRadius`, 32 chunks by default): one map pixel per block, whatever
  Distant Horizons' own detail would be there.
- Coarse LOD tiles and empty tiles next to chunks Xaero mapped itself get one pixel per block, so explored land never
  surrounds a patch of coarse terrain.
- Gaps are filled anywhere within the map radius, far ones first at a coarser level (quick), then refined.
- `xaeroMinimapLodMap`: show the LOD terrain on the minimap or not. Off, the minimap leaves out every 64 x 64 block
  square that holds only LOD tiles, and the World Map keeps them. The LOD map can be switched on and off in game.

### Settings, progress and commands
- In-game settings through Mod Menu, one page per category, every setting explained in its tooltip, and a status
  page with what SeedGenGlobe is doing. Most settings apply at once; the rest say when they do.
- Progress overlay (`progressOverlay`, `overlayPosition`): sections running and waiting, speed, and how far out LODs
  and the map are complete, and what slows generation down right now.
- Chat messages (`chatProgress`): LODs and the map complete out to 1, 2, 4, 8, ... thousand blocks, and done.
- The log says when generation starts, stops and starts again, its summaries count sections started as well as
  finished and split the time per section into terrain, structures, trees, ground cover and writing, and with
  `logSections` every section is logged when it starts and when it finishes.
- Commands are now debugging tools for operators: `/seedgenglobe debug status|verify|benchmark|probe|structures`.
  `/seedgenglobeclient` is gone (the status page has its content).

### Fixes
- Sections no longer keep running after the world closes: they stop within a fraction of a second, and the server
  waits for them before it shuts down. In 1.2.0 they failed with "Registries not available", dumped Big Globe scripts
  into the log and sent error messages to the chat on every quit.
- Generators of an earlier world are let go of when it closes; 1.2.0 kept them, with their level and structure
  caches, in memory until the game was restarted.
- Structures placed into LODs no longer ask the real level about structures (a swamp hut's cat did, which ran
  Big Globe's structure locator for spots thousands of blocks away on Java's shared thread pool).
- Structure pieces get block entities while they are placed, so chests and Big Globe's delayed generation markers no
  longer log "Expected class ... but got null instead".
- "At a time" in the log no longer exceeds the number of threads.

## 1.2.0

### Structures
- New: Big Globe's structures in generated LODs (`structures`, `structureDetail`): obelisks, mega trees, villages,
  houses, lakes and the rest, both the terrain they shape and the blocks they place, from Big Globe's own structure
  placement, so they stand where they will in real chunks. Every structure up to detail 5 (32 block LOD cells), lakes
  and mega trees two levels further, obelisks at every distance. Servers and singleplayer only (they need the seed).
- New: `/seedgenglobe structures [radiusChunks]` lists the structures LODs get around you.
- LODs Distant Horizons saved earlier are not regenerated by it; delete its `DistantHorizons.sqlite` to get
  structures into areas that were generated before.

### Generation speed
- Sections now run on SeedGenGlobe's own threads (`generatorThreads`, automatic by default). On Distant Horizons'
  shared pool, generation only got a thread when DH's file and render work had nothing queued, and it stopped while
  the camera moved faster than 20 blocks/s, so on large render distances it ran a few sections at a time and stood
  still while flying. `generatorThreads=-1` keeps the old behaviour.
- The log now gets a summary every minute while sections are generated (`statsLogSeconds`, replacing
  `statsInterval`, which logged every 500 sections and made generation look like it ran once every few minutes),
  with the rate, how many ran at once and what Distant Horizons' own queues held; and one line when Distant Horizons
  stops asking for sections, with its queues at that moment. `/seedgenglobe` shows the same.

### Xaero's World Map and Minimap
- The LOD map is now drawn from the sections generated for Distant Horizons, as they are generated, instead of being
  computed a second time on one low-priority thread that fell hours behind on large render distances. It shows
  the LODs' own terrain, trees, ground cover and structures at their detail levels, and grows with them: regions in
  Xaero's memory (minimap, open world map) get new data within seconds.
- Where Distant Horizons generates nothing (LODs from earlier sessions or a DH server), the map thread computes the
  map itself, in Distant Horizons' detail levels, nearest first, only where the map has nothing as fine, and only
  behind the area DH is generating.
- Map tiles carry the detail of their coarsest cell, so finer data replaces them later.
- The map starts a new folder under `seedgenglobe/lodmap/` (new format); 1.1.0's folders can be deleted.

### Other
- More offline tests: the section structures are placed into, and how sections of every detail level are merged into
  map regions (against a block-by-block model, with a fuzz test).

## 1.1.0

### Xaero's World Map and Minimap
- New: the surface of the whole Distant Horizons radius on Xaero's World Map and Minimap (`xaeroLodMap`,
  `xaeroLodMapRadius`). Computed from Big Globe's terrain scripts on a background thread, one map pixel per block
  near you and coarser with distance like LODs, filled nearest first. Real chunks always replace it as soon as
  Xaero maps them. Multiplayer maps keep it in Xaero's saves; singleplayer maps get it back from Xaero's texture
  cache in later sessions.
- LOD map pixels record Big Globe's surface biome, so the map's biome readout names the biome on the ground.
- With Vertigo, Xaero no longer maps chunks whose surface was not synced yet as black, empty chunks at y = -1024
  with the plains biome (the wrong biome readout in explored areas). Such chunks already in a saved map are covered
  by LOD terrain until Xaero maps them properly.

### Distant Horizons LODs
- Fixed: black holes in the LODs while Distant Horizons is still building them. Generated sections no longer write
  partial data into their coarser parent sections, which made DH hide finer sections that were already there
  (`updateParentSections` restores DH's default).
- New: ground cover on generated LODs (`groundCover`): short grass, ferns, dead bushes, flower patches (laid out like
  Big Globe's flower controller), cherry petals, leaf litter, bushes, sticks and rocks with Big Globe's chances.
  Distant Horizons colours a block by the plants on it, so generated LODs now match LODs of explored chunks instead
  of showing a visible square around the spawn chunks.

### Other
- The mixin plugin now checks Xaero's classes before patching them, and the offline link check verifies every
  `@WrapOperation` target and handler.
- More offline tests: ground cover chances and DH's 2 x 2 merge, how a column reads on Xaero's map, and the LOD map's
  region bookkeeping.

## 1.0.0
- First release: seed-based Distant Horizons generator for Big Globe, multiplayer client generation, the Vertigo
  guard, Big Globe colours for LODs, approximate tree canopies.
