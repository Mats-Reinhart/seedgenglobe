# Changelog

## 1.5.0

### Big Globe 6.4.0
- Checked against Big Globe 6.4.0 (both builds, for Minecraft 26.1.2 and 26.2): every class, field and method
  SeedGenGlobe uses is there, and the scripts it mirrors (ground cover decorators, world traits, the structure
  overriders below) are unchanged. 1.4.0 already runs on it; nothing had to change. Built against 6.4.0 now.

### Minecraft 26.2
- The same jar now also loads on Minecraft 26.2 (`minecraft` `>=26.1 <26.3`). 26.2 moved three things SeedGenGlobe's
  screens use: opening a screen (`Minecraft.setScreen` is now `Minecraft.gui.setScreen`), the open screen
  (`Minecraft.screen` is now `Minecraft.gui.screen()`) and whether the HUD is hidden with F1 (`Options.hideGui` is now
  `Minecraft.gui.hud.isHidden()`). SeedGenGlobe looks them up by name once, so the settings screen, the status page,
  the LOD regeneration prompt and the progress overlay work on both versions.
- Everything else links against Minecraft 26.2, Fabric API 0.161, Mod Menu 20.0.3, Big Globe 6.4.0 for 26.2,
  Xaero's World Map and Minimap for 26.2 and Vertigo 1.2.7 for 26.2.
- **Distant Horizons 3.0.3 does not exist for Minecraft 26.2**: its first build for 26.2 is 3.1.0-b. With Distant
  Horizons 3.3.3, three of SeedGenGlobe's mixins into its internals no longer fit and are left out (and listed on the
  status page): the queue integration of multiplayer client generation, the gate behind `dhChunkLodBuilding` and the
  Vertigo guard, and Big Globe colours. The generator itself uses only Distant Horizons' public API. Distant Horizons
  3.3.3 also refuses to load next to Vertigo. Only 3.0.3 is supported and checked.
- The multiplayer queue mixin now checks the exact signature of the method it hooks before it is applied, like the
  other two, so a Distant Horizons build where it differs leaves it out instead of failing to apply it.

### Faster structure terrain
- Near villages, houses, campfires, lakes, wells and windmills, Big Globe raises its cave noise in the 16 blocks under
  the surface so no cave opens beneath them. Before an overrider may change cave noise, Big Globe computes it for the
  whole column, and in a 1.4.0 test that was a third of all generation time (1.2 ms for every four columns near a
  structure). LODs that are not carved (the default Fill underground) never read cave noise: Big Globe's cave carver
  only runs where its hints say carve. These overriders are now left out of LODs that are not carved, but only when
  they are exactly the scripts SeedGenGlobe was checked against, and only when the cave carver is the only terrain
  layer that reads cave noise. New **Skip cave-only overriders** (`skipCaveOverriders`, Advanced, on); Full structure
  precompute turns it off. The status page says how many quads of columns did without them.
- Ocean monuments: before Minecraft samples a possible monument's surroundings (225 biome columns for every
  candidate chunk, most of them on land), SeedGenGlobe checks whether the centre column of the chunk, where Big
  Globe checks the monument's biome in the end, has a biome the monument allows at any height. This is the early biome
  check Minecraft 26.3 added before structures work out their heights and pieces, brought to 26.1 and 26.2. The same
  monuments, found faster. New **Early monument biome check** (`earlyBiomeCheck`, Advanced, on).
- Each sampler reuses its four block segment lists for every pair of columns instead of making new ones, as Minecraft
  26.3 pools its terrain buffers.

### Other
- The C2ME line of the detected mods (log and status page) no longer says LOD columns run on Big Globe's chunk worker
  pool: they run on SeedGenGlobe's own threads.
- The settings summary in the log lists the structure settings that differ from their defaults next to
  `structures=`.
- Link check tools: `tools/offline-build/PortCheck.java` checks a built jar against the real jars of a Minecraft
  version (Minecraft included) and the mixin plugin's requirements; `LinkCheck` no longer counts a member missing from
  a Distant Horizons or Big Globe class as found on `java.lang.Object`.

## 1.4.0

### Mod Menu
- SeedGenGlobe's page in Mod Menu now has working **Website** and **Issues** buttons: Website opens
  https://github.com/Mats-Reinhart/seedgenglobe and Issues opens https://github.com/Mats-Reinhart/seedgenglobe/issues.

Also contains every 1.3.3 change below.

## 1.3.3

### Holes in the LODs while moving
- Fixed: large holes in the LODs that opened while moving and only closed when you came near. Distant Horizons draws
  a coarser section in place of four finer ones while any of them is still loading, and after you move away it wants
  the coarser level there. SeedGenGlobe generated only the sections Distant Horizons asked for at their own level,
  never those coarser ones, and a section with no data counts as drawable, so it drew nothing over the finer
  sections that had loaded. Two changes:
  - An empty coarser section no longer hides finer sections that loaded; only the one still loading is blank, for a
    moment.
  - New **Generate parent sections** (`generateParentSections`, Advanced, on): Distant Horizons also asks SeedGenGlobe
    for those coarser sections, which are then generated directly like any other. About a third more sections; ones
    already in the database are not generated again. Moving away from an area then finds its coarser level ready,
    also once Distant Generation is switched off.
  - While Distant Generation is off, a finer section Distant Horizons wants but nobody can generate any more (walking
    towards terrain that was generated from farther away) no longer shows as a hole: the coarser terrain around it
    stays.
- Fixed: switching Distant Horizons' Distant Generation off and on again in the same session left the dimension
  without generation until the world was opened again. Distant Horizons closes its generator when generation is
  switched off and hands the same one back when it is switched on; SeedGenGlobe's generator now only drops the
  sections it was asked for, and closes for good when the level or the server goes.

### Memory
- Fixed: every world opened in a game session stayed in memory until the game was closed: its integrated server,
  client level, Big Globe terrain and structure cache. The chat milestone bookkeeping kept them, and so did Distant
  Horizons' own registry of world generators, which never lets go of a registered generator. SeedGenGlobe now forgets
  closed producers and takes its generator back out of that registry when the level goes. Leaving a world also
  empties the structure cache.

### Xaero's World Map
- The chat says "Xaero LOD map computed out to ..." instead of "no gaps" / "complete": the map is computed first, and
  filling Xaero's map from it (region by region) takes longer.
- The automatic LOD map radius (`xaeroLodMapRadius=0`) follows Distant Horizons' render distance only up to 512
  chunks. Filling Xaero's map is far slower than computing it, so a large Distant Horizons radius kept it filling for
  hours. A radius set by number is used as before.
- Generated sections coarser than detail level 6 are no longer fed to the map, which never uses them.
- The `xaeroLodMap` text now says that a singleplayer map loses LOD tiles wherever Xaero rebuilds a region from the
  world save while the option is off.

## 1.3.2

### Regenerate LODs
- New **Regenerate LODs...** button on the General page of the settings (singleplayer, with the world open) and
  `/seedgenglobe regenerate` (operators). They delete Distant Horizons' LOD database of every dimension of the world
  (`DistantHorizons.sqlite` and its `-wal`, `-shm` and `-journal` files) and, in singleplayer, SeedGenGlobe's copy of
  the Xaero LOD map of those dimensions, so everything is generated again. The world, Xaero's map of explored chunks
  and waypoints stay.
- The button asks in Minecraft's confirm prompt; the command says what will be deleted (dimensions and size) and
  confirms when sent again within 30 seconds.
- Distant Horizons keeps its databases open while the world runs, so the files are deleted cleanly when the world
  closes: after Distant Horizons' own shutdown, and only once every connection it had open on them reports closed.
  Anything still open or locked then is deleted when the world loads next, before Distant Horizons opens it. Until
  then the button (Keep the LODs) or `/seedgenglobe regenerate cancel` takes it back. What is scheduled is kept in
  `seedgenglobe/regenerate-lods.txt` in the world folder; only Distant Horizons databases inside the world folder and
  SeedGenGlobe's own map folders are ever deleted, whatever that file says.
- The status page says when a regeneration is scheduled.

### Master switch
- The master switch (`enabled`) moved from the General page to a button right after the page buttons: **Enabled** in
  green or **Disabled** in red, visible on every page. As before it governs everything SeedGenGlobe does and takes
  effect after a game restart; Page defaults no longer touches it. A regeneration confirmed before switching off still
  completes, and Mod Menu's short credits line stays.

## 1.3.1

### Settings screen (Mod Menu)
- Laid out like Minecraft's own option screens: the title stays at the top, Page defaults, Cancel and Save & quit
  stay at the bottom, and the page buttons and settings between them are one list that scrolls with a scrollbar when
  the window is too small. In very small windows 1.3.0's page buttons filled the screen and the bottom row ran off
  both edges.
- Settings with more than three values are sliders (CPU load, memory load, overlay position, underground mode and
  every number); the others stay buttons. The arrow keys move a slider one value at a time.
- "Done" is now "Save & quit", and the Status button sits in the top right corner.
- Two columns of settings from Minecraft's default window size on (they narrow to fit), and the page buttons keep
  their layout when the page changes.
- "Give way to the server" is now called "Server tps priority" (same setting, `yieldToServer`).
- New `progressUnit` ("Distances in"): the overlay, chat milestones, status page and log count how far LODs and the
  map are complete in chunks, like Distant Horizons' render distance (the default), or in blocks as before.
  Milestones are the same distances: 64, 128, 256, ... chunks.
- Mod Menu shows the credits as "Credits: Mats Reinhart". Mod Menu always puts a role heading ("Authors:") under
  "Credits:"; a small client mixin leaves it out for SeedGenGlobe only, and is skipped on a Mod Menu that works
  differently.

### Performance (from the 1.3.0 tests against 1.1.0)
- The generator threads run at normal priority, and drop to the CPU preset's lower priority only while the server is
  behind (new `threadPriority`: Automatic, Normal, Lower). In 1.3.0 they always ran below normal; on Windows they then
  lost every contest for the CPU with the server and Distant Horizons, the most likely reason the first minutes of a
  new world ran at a fifth of 1.1.0's pace. Minimal and Low CPU load still run at low priority.
- `dhRequestBoost` only lets Distant Horizons' render loader and file queue grow past its own limit (5 tasks per
  thread) while SeedGenGlobe's threads are about to run out of sections. In 1.3.0 the boost kept generation going
  while the render loader held up to 320 tasks, and terrain took twice as long per section; now they take turns as in
  1.1.0. More
  sections in flight than Distant Horizons' thread count plus one stays.
- Structure terrain: before the overriders near a structure reshape the terrain, only the column values those
  overriders read are computed, instead of everything any of Big Globe's raw overriders reads (cave and chasm values
  included, which the Fill underground mode otherwise never computes). In 1.3.0 this took as long as the terrain
  itself. `fullStructurePrecompute` (Advanced) brings back the exact 1.3.0 behaviour.
- Structure lookup: small structure sets are left out of coarse LODs. Once 32 starts of a set were found, the set is
  only looked up at detail levels whose cells are at most half as wide as its largest start, so houses, wells and
  campfires stop at about detail 3 instead of `structureDetail` (5). Structure lookup was more than half the time of a
  coarse section in 1.3.0. The log says which sets this applies to.
- The status page and the log (when a world closes) break structure lookup time down by structure set.

### Fixes
- The "slower" reason said "the server is behind (22 ms per tick)" while generation was only speeding up again after
  the server had been behind; it now says so.
- Big Globe's "Expected ChestBlockEntity / DelayedGenerationBlockEntity ... in SeedGenGlobe structure capture"
  warnings: blocks with a block entity that pieces place in columns a coarse LOD drops now still get their block
  entity, so the scripts that fill them find it.
- The status page's buttons fit small windows too.

### Build
- The offline link check also verifies members SeedGenGlobe's own classes inherit from Minecraft (the settings list's
  rows, the sliders), against how other mods' subclasses use them.

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
