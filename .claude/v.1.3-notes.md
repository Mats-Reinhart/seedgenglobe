# SeedGenGlobe 1.3.0 – notes
 
Built 2026-11-01 for Minecraft 26.1.2 against Distant Horizons 3.0.3-b (atlasfix), Big Globe 6.3.0, Xaero's World Map 1.47.2 / Minimap 26.6.2, Mod Menu 18.0.2 and Fabric API 0.155.3. Offline build (no Minecraft jar): link check 1600 references resolved, 14 unverified, 0 errors; the mixin plugin's requirement checks pass for every DH and Xaero mixin; offline logic tests pass. Not run in game.
 
## The 1.3 requests and what changed
| Request | 1.3.0 |
|---|---|
| Not called often enough, worst on low-end machines | One queue for all sections on SeedGenGlobe's own threads, nearest player first. `cpuLoad` / `memoryLoad` presets. `dhRequestBoost`: DH may keep as many sections in flight as SeedGenGlobe's queue holds (DH's own cap is its threads + 1), and keeps queueing new ones while its render loader and file queue hold fewer than 8 / 12 / 20 tasks per thread (memory preset) instead of 5. Off while memory is short. `yieldToServer`. |
| Log only shows finished jobs | Line when DH first asks for sections; summaries count started and finished sections and split time per phase; `logSections` logs every start and finish. |
| Switch to disable DH's own LOD building | `dhChunkLodBuilding=false`: DH builds no LODs from loaded chunks in SeedGenGlobe dimensions. That also keeps DH's request gate open (DH stops asking for sections while 20 chunk updates per thread wait). |
| Fix artifacts near the player before expanding | The queue picks the waiting section nearest a player every time a thread takes one, so it follows the player. Map gaps near the player come first. |
| Explored chunks surrounding coarse LOD patches | Within `mapFullDetailRadius` (32 chunks) the map is 1 pixel per block, and so is every coarse or empty tile next to a chunk Xaero mapped. |
| Mod Menu config (minimap, DH, CPU/memory) | Settings screen with 7 pages, tooltips and a status page. `xaeroMinimapLodMap`, `dhGeneration`, `xaeroLodMap`, `cpuLoad`, `memoryLoad` and more. |
| Commands that should be settings | All gone except `/seedgenglobe debug status / verify / benchmark / probe / structures` (operators). |
| Better progress, including the Xaero map | Overlay, chat milestones, status page and per-minute log lines, for LODs and for the Xaero map. |
 
## Worth checking in game
1. Low-end setup (DH CPU preset with 1–2 threads, `memoryLoad` auto or low): the overlay's running/waiting counts should stay above 0 while DH still has missing sections. Compare sections per minute with 1.2.0.
2. Which "Waiting for Distant Horizons' …" reason the overlay shows most: render queue, file queue or chunk updates.
3. Flying fast over unexplored land, with `dhChunkLodBuilding` on and off.
4. Teleporting far: sections already handed to SeedGenGlobe near the old spot finish first. How long until sections near the new spot start?
5. Xaero map around spawn and explored areas: no coarse holes inside 32 chunks, and full detail at explored edges.
6. Minimap with `xaeroMinimapLodMap` off: explored land, plus a LOD fringe up to 64 blocks wide.
7. Quit and rejoin: no "Registries not available" errors.
8. The Mod Menu screen in a small window: one or two columns, tooltips, Done / Cancel, and the "applies when…" note.
## Known limits
- Structure guard: 10 of its 11 StructureManager injection targets could not be checked against 26.1.2 (they are `require = 0`, so any missing one is skipped quietly). The one the swamp hut's cat uses is checked.
- DH never takes back a section once it is in progress. With the boost more sections are in progress, so after a teleport the old ones finish first.
- The minimap switch works per 64 × 64 block square.
- The CPU presets guess physical cores as half the logical CPUs, from 8 logical CPUs up.
## Test 1 (2026-11-01): 1.3.0 vs 1.1.0, new world, DH 1048 chunks (16,768 blocks), no Xaero [2026-11-01-1.log vs latest.log]
Same machine (Ryzen 7 5800X, 8C/16T, Windows), DH 3.0.3-b atlasfix with 16 threads in both. The worlds were different
seeds (spawn −8104, 5851 vs 3939, 6347). 1.3.0 ran Balanced (6 threads, priority 3) until 13:07:36, then High
(12 threads, priority 4), and rejoined mid-job at 13:11:41. User timing: 1.1.0 about 8 min, 1.3.0 about 9:30.
 
| | 1.1.0 | 1.3.0 |
|---|---|---|
| Sections | 3,000–3,499 (at 3,000: d0 491, d1 477, d2 558, d3 667, d4 615, d5 192) | 1,918 (d0 254, d1 256, d2 363, d3 369, d4 479, d5 197) |
| Rate | about 9.1 per second | about 3.2 per second |
| Thread time per section | 0.41 s (about 1,250–1,450 s in all) | about 3.0 s (about 5,700 s in all) |
| Running at once | about 3.8, on DH's pool at priority 5 | 6 then 12, own threads at priority 3 / 4 |
| Structures | none | 17 sets to detail 5, 3 large to 7, obelisks at every detail |
 
### Findings
1. **Structures are 61% of 1.3.0's thread time**: structure terrain 38%, structure lookup 17%, placement 6%; terrain
   33%, ground cover 2% (minute lines, 1,836 sections). 1.1.0 had no structures at all. 1.3.0 had fewer sections to do
   and still took longer.
2. **Structure terrain costs as much as terrain in every minute line** (740/849, 1545/2013, 685/605, 346/346 ms).
   StructureFrame.override calls QuadColumn.preComputeRaw(owner.overriders), and Big Globe's
   SortedOverriders.preComputeRaw precomputes the whole rawColumnValueDependencies array, the union for every raw
   overrider, on every quad near any overriding structure, not only what the overriders in range need. Candidate fix:
   per-quad filtered dependencies via SortedOverriders.extractDependencies, as StructureFrame.place already does for
   its AutoOverride. Needs a timing check in game.
3. **Structure lookup dominates coarse sections**: 1,270 of 2,130 ms per section in the last minute (d4/d5). Matches
   1.2.0 finding 2. Candidates: cache lookups per area at coarse detail, a lower default `structureDetail` (3), or
   leave small sets (houses, campfires) out above detail 3.
4. **Running beside DH instead of taking turns.** Terrain alone took about 0.7 s per section in the two minutes DH's
   render loader was empty and 1.4–1.5 s while it held 130–210 tasks. In 1.1.0, sections ran on DH's own pool at normal
   priority and DH stopped asking while its render loader held 5 per thread (80), so generation and render loading
   alternated. In 1.3.0, dhRequestBoost keeps generation going up to 320 and SeedGenGlobe's threads are below normal
   on Windows, so they lose every contest with DH's 16 normal-priority threads. Candidate: drop the boost while the
   render loader is above DH's own limit (the 1.2.0 idea), boost only while it is short.
5. **High (12 threads) did not raise the rate**: 3.5 per second on 6 threads (13:06–13:07) vs 2.8–3.4 per second on 12
   (13:08–13:11), each section about twice as slow. Detail mix differs between those minutes.
6. **The mid-job rejoin cost about 40 s**: 24 s with nothing running, 11 running and 36 queued sections dropped, and
   cold structure caches (lookup 2,956 ms per section the first minute after, 769 ms the next). 1.1.0's rejoin came
   after its job was done.
7. Ruled out: DH supply (18 or 36 sections waiting in every minute line, render loader never at 320), yieldToServer and
   memory throttling (no "allowed" in any line), Xaero map (not installed, no map phase).
8. Unexplained: 1.1.0 generated roughly twice as many d0–d3 sections; d5 counts match.
9. Still open from 1.2.0 finding 6: 44 "Expected class ChestBlockEntity … in SeedGenGlobe structure capture" warnings.
   Rejoin was clean: no "Registries not available" errors.
### Next test
Same seed for both versions, 1.3.0 with `structures` off, then on with `structureDetail` 3, Balanced, no mid-job
rejoin. That isolates the structure cost from the rest.
 
## Test 2 (2026-11-01): 1.3.0, structures off, same seed as the 1.1.0 run [1793565831221_latest.log]
Spawn −8101, 5852 (1.1.0 run: −8104, 5851), so this is the same world as Test 1's 1.1.0 side. Balanced (6 threads,
priority 3) throughout, no rejoin, teleport to y 2000 after 22 s (1.1.0: 27 s). User timing: 7:03.
 
| | 1.1.0 | 1.3.0, structures off |
|---|---|---|
| Sections | 3,000–3,499 (d0 491, d1 477, d2 558, d3 667, d4 615, d5 192 at 3,000) | 1,880 (d0 249, d1 233, d2 414, d3 392, d4 403, d5 189) |
| Rate, first minutes | about 10 per second (500 in about 50 s) | 0.9–2.0 per second (295 in 164 s) |
| Rate, overall | about 9.7 per second | about 4.5 per second (1.8, then 6.2 after 21:39) |
| Terrain per section | 0.56 s whole section (first 500) | 2.5–3.3 s first 3 minutes, 0.7–1.2 s later |
 
### Findings
1. **Structures roughly double the cost of a section**: 1.35 s per section here vs about 3.0 s in Test 1 (other seed).
   With them off, 1.3.0 finishes before 1.1.0 (7:03 vs about 8).
2. **1.3.0 wins only because it generates fewer sections.** Same world and radius: 1.1.0 needed 60–85% more sections,
   about twice as many at d0 and d1. Cause unknown; possibly 1.1.0 generated sections DH asked for again.
3. **1.3.0 generates at about half 1.1.0's rate, and a fifth of it in the first three minutes.** Those minutes are
   terrain only, and the same terrain took 0.7 s per section later, so the threads were waiting for CPU rather than
   doing more work. Meanwhile the server generated real chunks (two "Can't keep up" warnings) and DH turned 170–390
   loaded chunks into LODs, all at normal priority, while SeedGenGlobe ran at priority 3 (below normal on Windows).
   1.1.0 ran on DH's pool at DH's threadPriority default 5. Hypothesis, not proven: the logs have no CPU time.
4. DH held back twice (393 chunk LOD updates, then a render loader of 421), but SeedGenGlobe's queue never ran dry
   (18–19 waiting in every line).
5. Cosmetic: the first line said "5 allowed: the server is behind (22 ms per tick)": the throttle was still recovering
   from spawn preparation, but the reason shows the current tick time, which is under the limit.
### Next test
Same seed, structures off, CPU load Extreme with Threads 6: six threads at normal priority. If the first minutes run near
1.1.0's pace, 1.3.1 should run at normal priority (or DH's threadPriority) and drop below normal only while
yieldToServer sees the server behind.
 
## Mod Menu requests for 1.3.1 (2026-11-01, user, with screenshots)
In a very small window the 1.3.0 screen breaks down: the seven page buttons (General, Performance, Xaero's maps,
Progress, LOD terrain, Multiplayer, Advanced) fill the whole height, no option is visible, and the bottom row
(Page defaults, Cancel, …) runs off both edges. In a normal small window it looks mostly fine.
 
1. **Scrollable at very small sizes**, like vanilla's Music & Sound Options: title fixed at the top, buttons fixed at
   the bottom, and everything between them in one list that scrolls, with a scrollbar on the right.
2. **Sliders for any option with more than 3 values.** Cycle buttons only for options with 2 or 3 values.
3. **Milestones and progress in chunks or blocks**: a new setting. Chunks is the default, because DH counts its
   render distance in chunks; blocks is the 1.3.0 behaviour.
4. **Rename "Give way to the server" to "Server tps priority"**, same tooltip.
5. **Rename "Done" to "Save & quit"**, and move the "Status…" button to the top right of the screen.
6. **Credits**: Mod Menu should show only "Credits: Mats Reinhart", not separate Credits and Authors entries.
