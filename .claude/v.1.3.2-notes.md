# SeedGenGlobe 1.3.2 – notes
 
Built 2026-11-02 from the 1.3.1 source (`SeedGenGlobe-1.3.1-src.zip`; the offline build of that source reproduces the released 1.3.1 jar byte for byte), for Minecraft 26.1.2 against Distant Horizons 3.0.3-b (atlasfix), Big Globe 6.3.0, Xaero's World Map 1.47.2 / Minimap 26.6.2, Mod Menu 18.0.2 and Fabric API 0.155.3. Offline build (no Minecraft jar): link check 1813 references resolved (60 inherited), the same 14 unverified as 1.3.0/1.3.1, 0 errors; all offline tests pass (ConfigTest 552, SchedulerTest 637, StructureSizeTest, new LodRegenerationTest 43). Not run in game.
 
## Requests and what changed
| Request | 1.3.2 |
|---|---|
| Config + chat command to regenerate DH/Xaero LODs, SQLite deleted cleanly | `/seedgenglobe regenerate` (operators): first send lists dimensions and size, second within 30 s confirms; `/seedgenglobe regenerate cancel`. General page: "Regenerate LODs..." → Minecraft's ConfirmScreen; once scheduled the button becomes "Keep the LODs (undo)". Singleplayer only for the button. |
| How "clean" works | Confirming writes `<world>/seedgenglobe/regenerate-lods.txt` (dh= paths relative to the world, lodmap=`<terrain>/<dimension>`). Deleted at `SERVER_STOPPED` in a phase ordered after DH's default-phase listener (DH closes its world there), only once each `java.sql.Connection` DH had open on the file (recorded at confirm time from `AbstractDhServerLevel.serverside.fullDataFileHandler.repo`) reports closed and no loaded DH level has the file. Order: `.sqlite`, then `-wal`, `-shm`, `-journal`. Anything open/locked → left in the marker, deleted at the next `SERVER_STARTING` (before any level loads). |
| Xaero LODs | SeedGenGlobe's own map store `<game>/seedgenglobe/lodmap/<terrain>/<dimension>[-variant]` (index included), singleplayer only. Deleted on the LOD map builder thread after its pending writes; stores dropped from `XaeroLodMap.STORES`. Xaero's own files (explored map, waypoints, texture cache) untouched. Folder hash moved to `LodMapFolders` (same formula as 1.3.1). |
| "Enable SeedGenGlobe" toggle right of the categories, green/red | The `enabled` option left the General page; it is the slot after the 7 page buttons (2 rows of 4 at normal width, right of "Advanced"): "Enabled" green / "Disabled" red. Applies on Save & quit, takes effect after a restart (as before). Page defaults no longer resets it. |
 
Also: when disabled, every mixin stays off except `ModMenuCreditsMixin`; a regeneration confirmed before switching off still completes (lifecycle hooks always registered); new requests are refused while off. Status page shows a scheduled regeneration. `hierarchy.txt` gained ConfirmScreen, BooleanConsumer (functional), IntegratedServer.
 
## Worth checking in game
1. Config screen: the Enabled/Disabled button after Advanced, colour, tooltip; toggling shows the "after restarting" note; in a narrow window the page buttons + switch wrap in pairs.
2. Singleplayer: Regenerate LODs... → confirm → chat message, button turns into "Keep the LODs (undo)"; status page line.
3. Save and Quit, check the log for "LOD regeneration done as the world closed: N Distant Horizons database files deleted, M Xaero LOD map folders cleared" and that `DistantHorizons.sqlite*` are gone from each dimension's data folder (the exact paths are the `dh=` lines of the marker file before quitting). Reopen: DH regenerates from scratch; Xaero LOD map refills.
4. Windows lock case: if the log says a database "is still open" or "cannot delete", it should be deleted on the next world load instead (log line "as the world loaded").
5. Command: `/seedgenglobe regenerate` once (message), wait >30 s (asks again), twice quickly (scheduled), `cancel`.
6. With `enabled=false` after restart: Mod Menu still shows "Credits: Mats Reinhart"; `/seedgenglobe regenerate` refuses.
## Known limits
- Deletion happens when the world closes, not instantly: DH holds the SQLite file open while the world runs.
- Button only in singleplayer; on a server the command, and deletion when the server stops.
- `/seedgenglobe` needs gamemaster permission (cheats on in singleplayer); without cheats use the button.
- Xaero texture-cache images of regions the new LODs never reach (e.g. after lowering DH's render distance) can keep old LOD pixels until Xaero rebuilds them.
- Multiplayer clients' own DH caches and LOD maps are not touched.

## In-game test 2026-11-03: holes while moving, slowdown after a while, gaps in Xaero's map

Analysis only, nothing changed in `src/` or the jar yet. Sources: `latest.log` (21:12–22:55, one game process, four world loads), `DistantHorizons.toml` and `seedgenglobe.properties` of the SIT Modpack profile, a copy of DH's overworld database, SeedGenGlobe's LOD map store and index, Xaero's cache and the world's region files on disk, and the bytecode of the DH jar actually installed (`DistantHorizons-3.0.3-b-26.1.2-fabric-neoforge-atlasfix.jar`).

### Short version
1. **Holes while moving.** SeedGenGlobe writes DH sections without their coarser parents (`updateParentSections=false`, since 1.1.0). DH 3.0.3 draws a section's parent in place of all four children whenever one child is still loading, and a parent with no data counts as drawable: it draws nothing. Moving keeps children loading, so empty parents get drawn: holes up to 8 km wide. DH's Distant Generation is off now, so the levels DH newly wants after you move are never made, and those holes stay until you come close.
2. **Slowdown.** Every earlier world that posted a SeedGenGlobe chat message stays in memory: its integrated server, client level, Big Globe terrain and structure cache. The log is four world loads in one game process and ends with DH's "Insufficient memory detected" and a 9.3 s stall. On top of that, the Xaero LOD writer never goes idle in singleplayer, and DH draws a 4,048-chunk radius.
3. **Xaero gaps.** "Xaero LOD map is complete" means SeedGenGlobe *computed* it, not that it is in Xaero's map: 1,200 of 3,487 regions are filled into Xaero's map. Both screenshots lie inside a block of 1,084 empty world region files, which Xaero treats as saved terrain and rebuilds as empty whenever it loads them.

### Timeline from the log
| Load | Time | DH Distant Generation | Xaero LOD map | Notable |
|---|---|---|---|---|
| 1 | 21:14:26–21:16:51 | off | on, radius 256 chunks | `/seedgenglobe regenerate` confirmed; DH databases deleted at the next load |
| 2 | 21:17:13–22:01:00 | on only 21:24:04–21:35:49 | on, radius 1,024 chunks | 3,273 sections, "complete out to 4,048 chunks" 21:35:30; DH render loader 97–423 sections queued; SeedGenGlobe's memory throttle "6 allowed: the heap is getting full" at 21:24:10 |
| 3 | 22:32:14–22:42:02 | off | on | the Xaero writer fills nothing after 22:34:33; server 4.0 s behind 22:41:17; DH "Insufficient memory detected" 22:41:35; 9.3 s behind 22:41:41 |
| 4 | 22:43:17–22:55:43 | off | off | DH asks for 0 sections |

Heap: Modrinth gives the instance 18,112 MB (instance override in `ModrinthApp/app.db`). The machine-wide `_JAVA_OPTIONS=-Xmx1024M` would override that, but the game's output has no "Picked up _JAVA_OPTIONS" line, so the launcher seems to strip it. It is still worth removing: every other Java program on this PC gets 1 GB.

### 1. LOD holes that appear while moving
What DH 3.0.3-b does (bytecode of the installed jar):
1. `LodQuadTree.recursivelyUpdateRenderSectionNode` walks from the root nodes down to the wanted detail level every tick and loads a render section for every node on the way, ancestors included, so a coarser stand-in is always at hand.
2. `onDetailLevelTooLow`: if fewer than 4 children `canRender()`, the parent is drawn and *all* its children are switched off (`recursivelyDisableChildNodes`).
3. `LodRenderSection.canRender()` only checks that a buffer was uploaded. `FullDataSourceProviderV2.get` returns `FullDataSourceV2.createEmpty(pos)` when the database has no row. That becomes zero vertex buffers, and `LodBufferContainer.tryMakeAndUploadBuffersAsync` completes at once with `buffersUploaded = true`. So a section with no data is "drawable" and draws nothing.
4. DH queues generation only for nodes it wants at their own level (`QuadTreeTickNodeHolder.getWorldGenNodesNearToFar` = `nodesToEnableDeleteChildrenList`), never for stand-ins.

What SeedGenGlobe adds: `GlobeWorldGenerator` returns sections at the requested level only, and `SectionFlags.keepToItself` sets `applyToParent = false`, so no parent is ever built from them. The 1.1.0 reasoning in `SectionFlags` ("SeedGenGlobe generates every detail level directly, so parents never need to be assembled from children") does not hold. SeedGenGlobe generates the levels DH *wants*, never the stand-ins DH *loads*. A missing parent is worse than the partial parent 1.1.0 avoided: four empty quarters instead of three.

Evidence, DH's overworld database (copy as of 23:11, 4,552 sections; `ApplyToParent` = 0 on 4,548 of them):
| Detail | Sections | Its parent exists |
|---|---|---|
| 0 | 1,263 | 100 % (real chunks; DH propagates those) |
| 1 | 601 | 85 % |
| 2 | 442 | 51 % |
| 3 | 400 | 23 % |
| 4 | 450 | 16 % |
| 5 | 518 | 1.7 % |
| 6 | 871 | 0.6 % |
| 7–9 | 7 | partial parents from chunk updates |

How this matches what you see:
- **Standing still:** every wanted section has finished loading, the children win, and no stand-ins are drawn.
- **Moving, even slowly:** detail rings shift and new nodes go into DH's render loader. Until a child has loaded, its empty parent is drawn over all four children. That makes a hole twice the child's width: a detail-6 child gives an 8,192-block hole. The render loader was 97–423 sections deep in load 2 (DH's own limit is 60; `dhRequestBoost` lets it ask up to 240), so this can last a while.
- **Moving away from an area:** DH now wants the coarser level there, which does not exist. With Distant Generation on, SeedGenGlobe would make it. With it off (`enableDistantGeneration = false` in `DistantHorizons.toml`; it was never on in loads 1, 3, 4 or the 23:00 run), nothing makes it. Coming back close makes DH want the finer level that does exist, or loads real chunks. That is why the holes "can only be fixed by moving close to them".
- **Moving towards an area:** the finer level there was never generated either, and the same applies.

### 2. Performance falls off after a while
**a. Earlier worlds stay in memory (SeedGenGlobe bug).** `ChatProgress.STATE` (`client/ChatProgress.java:27`) gets an entry for every `GenProgress` with any event (also STARTED) and never drops it. `GenProgress.group` is the `GlobeWorldGenerator` (DH LODs) or the `LodMapBuilder.Target` (Xaero map); `GenProgress.close()` only leaves `LIVE`. So every such world keeps:
- `GlobeWorldGenerator.level`: DH's level wrapper, whose `ServerLevelWrapper.level` is the stopped `ServerLevel`, and through it the whole `MinecraftServer` (all levels, registries, datapacks);
- `Target.rules` (`XaeroRules.level`): the old `ClientLevel` and Xaero's `MapWriter`;
- `terrain` (Big Globe's column scripts) and `structures`. `LodStructures.close()` only sets `closing`; the index keeps up to 49,152 tiles with full structure pieces at memory load High (37,304 were cached in load 2).

Loads 1, 2 and 3 all posted milestones, so in load 3 both earlier servers were still reachable. That fits DH's memory warning and the 9.3 s stall 90 minutes into the game process, 10 minutes into load 3, with an 18 GB heap. Until this is fixed, restarting the game between worlds avoids it.
DH also has a small leak of its own, not the cause: its "Closing all [N] database connections" goes 4, 8, 12, 16 because `AbstractDhRepo.closeAllConnections` closes the connections but never empties `ACTIVE_CONNECTION_STRINGS_BY_REPO`.

**b. The Xaero LOD writer never goes idle in singleplayer.** After DH generation stopped at 21:35, the writer kept filling regions for the rest of load 2. Between 21:36 and 22:00 it went from 200 to 1,236 regions, 47 k to 1.09 M tiles, and 400 to 2,249 Xaero loads. Each region costs a Xaero load, up to 2 ms of the client thread per tick, and Xaero rebuilding 64 tile-chunk textures, reading them back and writing its cache. A 1,024-chunk radius has 3,487 regions to go through, so this ran all session.

**c. Load from settings.** DH has `lodChunkRenderDistanceRadius = 4048` (about 65 km), horizontal quality High and SSAO, under Iris/Photon. Load 2's generation reached the full radius 18 minutes after joining (21:35:30); from then on DH has all of it to draw. While generating, 12 SeedGenGlobe threads (CPU load High), 12 DH threads and C2ME's 7 share 16 logical CPUs, and the server fell 2–9 s behind again and again.

### 3. Xaero LOD world map: gaps, and lagging behind
On disk now: store `seedgenglobe/lodmap/f1ee51e4511290b8/minecraft_overworld-s5`, Xaero cache `xaero/world-map/New World/null/cache_1`, world region files `saves/New World/dimensions/minecraft/overworld/region`.
- 3,487 regions have LOD data. The map's index (`index-b1e89c0b.bin`) records **1,200** of them as filled into Xaero's map (34 %); **2,272** have never been filled since the 21:16 regeneration reset the index. The last fill was at 22:34:33. Xaero's 3,583 cached region textures are mostly from before the regeneration, which is why much of the far map still shows.
- **1,084 of the 1,228 world region files are empty** 8 KB headers without chunks, made 24–26 Oct (nothing has added any since). They fill the block x −10..23, z 0..37, which covers both screenshots (centres about region 8,18 and 10,22). The only regions there with real chunks are the area around your waypoints, which is exactly the part of the screenshots that is drawn.

Why the gaps:
- **X1.** "Xaero LOD map is complete out to 1,024 chunks" (chat and overlay at 21:30, 21:32, 21:34, 21:35) means SeedGenGlobe computed the map into its store. Putting it into Xaero's map is a second, much slower step that the message does not count. The per-minute log line does count it: "3345 regions with LOD data, 404 of them filled into this map" at 21:44, and 1,156 at 22:32.
- **X2.** Xaero rebuilds a singleplayer map from the world save whenever it loads a region (see `XaeroLodWriter`'s class comment); LOD tiles survive only in Xaero's texture cache. The empty region files make Xaero treat the whole block as saved terrain. Every region it loads there, around you and wherever the world map loads full-detail regions, comes up empty, and its cache is rewritten empty until SeedGenGlobe refills it. Far regions Xaero has not loaded this session still show their old cache.
- **X3.** The refill is slow: about 43 regions per minute in load 2, with at most 3 Xaero loads in flight, 10 regions held, 2 ms per client tick, and a 15 s wait on new data. Xaero's loader also serves your own movement, so the writer falls further behind while you travel. The 2,272 unfilled regions need about an hour in one place. In load 3 it filled nothing after 22:34:33 although it was on; the world map was opened at 22:34:39, and the log does not show the cause.
- **X4.** Since load 4, `xaeroLodMap=false`. Nothing refills regions Xaero rebuilds, and regions outside the block that are shown only from cache are no longer registered with Xaero each session, so the gaps only grow. The option's text ("LOD tiles already on the map stay until Xaero maps those chunks") is true for saved (multiplayer) maps, not for singleplayer ones.
- **X5** (from reading the code, not confirmed on disk). `visit()`/`stillInMap()` trust a region whose Xaero cache file is newer than its fill. If Xaero rebuilt and re-cached the region after the fill (it was loaded and dropped before the sweep got to it), the scan counts it as done and does not refill it.

### Proposed fixes for 1.3.3
Done in 1.3.3: 1, 2 and 3, plus 8 and the wording part of 4 (see `v.1.3.3-notes.md`). Not done yet: 5, 6, 7.

| # | Problem | Change | Where |
|---|---|---|---|
| 1 | Empty stand-in parents | Make the parents DH loads exist: when a generated section's parent has no database row, queue the parent at lower priority (about a third more sections; coarse ones are slower, 866 ms at detail 6 against 360 ms at detail 0 in load 2), and/or add stand-ins that have no row to DH's generation list | `GlobeWorldGenerator`; mixin on `LodQuadTree.onDetailLevelTooLow` or `QuadTreeTickNodeHolder` |
| 2 | An empty stand-in hides loaded children | Do not count a section DH created empty (no row) as drawable *as a stand-in*, so DH keeps the children it has and only the unloaded child stays blank | mixin on `LodRenderSection.canRender` / `FullDataSourceProviderV2.get` |
| 3 | Leak across world loads | Remove `ChatProgress.STATE` entries when a `GenProgress` closes, and on disconnect; `GenProgress.close()` clears `group`; `LodStructures.close()` empties its index | `ChatProgress`, `GenProgress`, `LodStructures` |
| 4 | "Map complete" is misleading | Report regions filled into Xaero's map next to computed ones ("computed 3,487, in Xaero's map 1,200"), and say "complete" only when both are | `XaeroLodWriter`, `ChatProgress`, overlay |
| 5 | Writer too slow and never idle | Fill what is on screen first (minimap, open world map), then the rest in the background with a per-minute cap; allow more loads in flight while FPS and TPS are fine; pause while the server is behind | `XaeroLodWriter` |
| 6 | Empty region files blank regions | Treat region files without chunks as unexplored, or at least show on the status page how many such files there are | `XaeroLodWriter.knownToXaero`, `StatusReport` |
| 7 | X5 | Check a loaded region's tiles instead of trusting the cache time alone | `XaeroLodWriter.visit` / `stillInMap` |
| 8 | Option text | Say that in singleplayer, LOD tiles disappear as Xaero rebuilds regions while the option is off | `GlobeConfig` (`xaeroLodMap`) |

### Things to try now with the 1.3.2 jar
1. Turn DH's Distant Generation back on (DH settings, or `enableDistantGeneration = true`). DH then asks SeedGenGlobe for the levels it wants after you move, and those holes fill within seconds instead of never.
2. To get the stand-ins as well: set `updateParentSections=true` (Advanced page), then run `/seedgenglobe regenerate`, because the setting only applies to newly generated sections. Expect the 1.1.0 partial-parent holes while LODs build; unlike now, they end once an area is done.
3. Restart the game instead of leaving and rejoining worlds (2a).
4. Lower DH's render distance to 2,048 or 1,024 chunks and see whether the slowdown goes away (2c).
5. Xaero: keep `xaeroLodMap` on and let it catch up. With the world closed and a backup made, the 1,084 empty region files can be deleted; they hold no chunks.

Note: users don't want to keep generating map- or DH LODs after the initial generation. So while playing and moving around, the mod should be optimized for distant generation in DH turned off as well as new xaero's map chunks turned off.
Note: users want the world to be properly loaded in when the "loading world" is complete, but rendering in DH LODs takes up a lot of clock time and CPU load. So, it may be best to delay the user spawning into the world until (most) generated(!) LODs have loaded in. This won't actually affect performance, but will give the illusion that the world runs smoother than before.