# SeedGenGlobe 1.2.0 – notes
 
Findings about 1.2.0 from testing, to act on in later releases. Source of each finding in brackets.
 
## Stress test 1 (2026-10-31): new world, DH 1048 chunks, no Xaero, 1.2.0 on DH 3.0.3-b [latest.log]
Setup: Ryzen 7 5800X (8C/16T), RTX 3070 Ti, Windows, ZGC, C2ME + ScalableLux + Iris/Photon. Three sessions (two rejoins).
DH threads changed 12 -> 4 -> 16 during the run; generatorThreads=auto followed (11 -> 3 -> 15).
 
| Session | Threads | Sections/min | ms/section | Notes |
|---|---|---|---|---|
| 1 (12:26–12:27) | 11 | 78 | 5235 | |
| 2 (12:28–12:35) | 11 | 84–128 | 3084–6416 | DH render loader backlogged (141–290 queued) |
| 2 | 15 | 116–145 | 5861–7125 | 1040 sections total |
| 3 (12:36–12:43) | 15 | 127 → 31–33 | 7229 → 30322 | DH render queue 0 at times; server 17.5 s and 22 s behind |
 
### Findings
1. **Throughput is CPU-bound and flat (~2 sections/s) whether 11 or 15 threads.** Per-section time scales with
   thread count. More threads only add server lag. ~4 core-seconds per section, far above the "few hundred ms" the
   GeneratorThreads javadoc assumes. Auto thread count (DH threads − 1, capped at logical CPUs − 1 = 15) is too high.
2. **Sections get much slower further out** (session 3 started where sessions 1–2 stopped: 13 s → 30 s per section,
   DH not backlogged). Suspect structure work scaling with section area at coarse detail (large tier to detail 7,
   giant tier at every detail; StructureIndex tiles 512 blocks, MAX_ENTRIES 16384). Needs per-detail / per-phase timing.
3. **Swamp huts call the real StructureManager.** CaptureLevel.getLevel() returns the real ServerLevel; the swamp hut's
   cat runs finalizeSpawn → spawn-condition structure check → Big Globe FlatStructureLocator.getUnfiltered, a parallel
   stream on the ForkJoin common pool (normal priority, 15 workers). This fills Big Globe's real structure cache for
   areas 15–20k blocks out and is a likely path for LOD generation starving the server thread despite our
   below-normal thread priority. Fix: guard real StructureManager queries while a capture is running (mixin, common side).
4. **Sections keep running after the level closes.** DH closes the generator (IDhApiWorldGenerator.close) but in-flight
   sections on our own pool continue; after the server stops, Big Globe's registries are gone → 7 + 6
   "Registries not available" errors, script dumps, and Big Globe chat messages on every quit. Cancelled DH requests
   likewise run to completion. Fix: cooperative cancellation (per quad row, before structure phases), skip cancelled
   futures, close + await on SERVER_STOPPING.
5. **Generators leak across rejoins.** UnloadHandler never logged "unloaded", so SERVER_GENERATORS (keyed by
   IDhApiLevelWrapper) keeps every old generator with its ServerLevel and structure caches. Possible contributor to
   session 3 slowing down. Fix: remove on close(), key by the wrapped ServerLevel, clear on server stop.
6. **53 "Expected class ChestBlockEntity / DelayedGenerationBlockEntity … in SeedGenGlobe structure capture" warnings.**
   CaptureLevel.getBlockEntity returns null. Fix: throwaway block entities for EntityBlock states per section.
   Idea: DelayedGenerationBlockEntity marks deferred Big Globe generation, which LODs currently never get.
7. **Stats line "at a time" exceeds the thread count** (16.6 with 15 threads): busy time is booked when a section
   finishes. Sample `running` instead. Add detail mix and phase split (terrain, overriders, structure lookup,
   structure placement, canopy, ground cover, write) to the minute line.
8. DH 3.0.3-b in use, 3.3.3 available: check DhPipeline's non-API reads (PriorityTaskPicker, ThreadPoolUtil) against 3.3.x.
### Ideas
- Auto threads ≈ physical cores − 2 (6 on a 5800X), not logical − 1.
- Back off concurrency while DH's render loader is over its request limit (extra sections just steal its CPU).
- Revisit giant/large structure tiers at coarse detail once timing data is in.
### Open questions for the user
- RAM allocated to the instance (memory pressure vs CPU for session 3).
- Optional baseline: `/seedgenglobe benchmark 16` with DH generation paused, structures=true vs false.
 
