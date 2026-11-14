# SeedGenGlobe 1.3.3 – notes

Built 2026-11-03 from the 1.3.2 source, for Minecraft 26.1.2 against Distant Horizons 3.0.3-b (atlasfix), Big Globe 6.3.0, Xaero's World Map 1.47.2 / Minimap 26.6.2 and Vertigo 1.2.6. This time with Gradle/Loom offline on this machine: Loom's cache has the real Minecraft 26.1.2 jar, Fabric API and Mod Menu, and the mod jars were copied from the SIT Modpack profile into `libs/` (gitignored). `gradlew --offline build -x test` passes: CanopyMath 338775, CaptureView 20030, ColumnProfile 110009, Config 561, GroundCover 649, LodCanvas 635, LodMapGeometry 65, LodRegeneration 43, Scheduler 637, StructureSize 15013, XaeroPixel 32. Gradle's JUnit `test` task fails with "no tests discovered" because the checks are `JavaExec` tasks, hence `-x test`. The stub build and LinkCheck in `tools/offline-build` need the old build machine's `WORK` folder and were not run. Instead, every requirement string of the new and changed mixins was checked against the installed DH jar's class files, the way `GlobeMixinPlugin` reads them: all found. Jar: `SeedGenGlobe-1.3.3mc26.1.2.jar` (SHA-256 `a6ff10bb…70cf6b514`). Not run in game.

Based on the 2026-11-03 analysis in `v.1.3.2-notes.md` (fixes 1–3 there, plus 8 and the wording part of 4), and on the two notes at the end of that file.

## What changed
| Finding (1.3.2 test) | 1.3.3 |
|---|---|
| DH draws an empty parent in place of four children while one of them loads (holes while moving) | `LodStandInMixin` wraps `canRender()` in `LodQuadTree.onDetailLevelTooLow`: a stand-in whose buffers are empty (`LodBufferContainer.vboBufferCount() == 0`) is not used, so the loaded children stay. Only in dimensions SeedGenGlobe generates (`DhBridge.generates`, which also works while DH's generation queue is closed). |
| Parents never generated: DH asks only for wanted nodes, `applyToParent=false` builds none (0.6 % of detail-6 sections had a parent) | `LodParentRequestMixin` wraps `QuadTreeTickNodeHolder.getWorldGenNodesNearToFar` and appends each node's ancestors from the tree (`QuadTree.tryGetNode`, `DhSectionPos.getParentPos`, deduplicated, only those not queued yet). DH's own `getPositionsToRetrieve` then decides which are missing or only `DOWN_SAMPLED`. Singleplayer/server (`DhProviderHook`) and multiplayer client generation (`DhClientLevelHook` on `DhClientLevelMixin`), never towards DH's own generator or a DH server. New option `generateParentSections` (Advanced, on, live). |
| Note 1: play is usually with DH Distant Generation off. Walking towards terrain generated from farther away wants finer levels that will never be generated | Same mixin, `onDesiredDetailLevel`: while the provider has no generation queue (`DhProviderHook.seedgenglobe$canGenerateNow()`), an empty wanted section is not drawn, so DH keeps the coarser section around it. With generation on, DH's rule stays (drawn empty, requested, filled). |
| Distant Generation off then on in one session: DH closes SeedGenGlobe's generator and hands the same closed one to its next queue | `GlobeWorldGenerator.close()` (DH's) now only drops that queue's sections: a queue counter makes running ones stop, and it logs "Distant Horizons closed its generation queue (N waiting sections dropped)". The old behaviour is `shutdown()`, used for level unload, server stop, reload and client generation. |
| Every world opened in a game session stayed in memory (ended in DH's "Insufficient memory detected") | `ChatProgress.STATE` drops closed producers and is cleared on disconnect. `GenProgress.close()` marks itself closed and drops `group`. `LodStructures.close()` empties the structure index. New `WorldGeneratorInjectorMixin` lets `DhBridge.unregister` take the generator back out of DH's static `WorldGeneratorInjector` map (DH never clears it, and its key holds the `ServerLevel`). |
| "Xaero LOD map is complete" while only a third of it was in Xaero's map | Chat: "Xaero LOD map computed out to …", and when done "…; filling Xaero's map from it can take a while longer". |
| Coarse parent sections would reach the map | `MapFeed` ignores sections coarser than `LodMapBuilder.MAX_DETAIL` (6). The map never uses them, and they would make Xaero's map be filled twice. |
| `xaeroLodMap` text wrong for singleplayer | Says that a singleplayer map loses LOD tiles wherever Xaero rebuilds a region while the option is off. |

## Defaults for new users (reviewed 2026-11-04)
Every default was checked against the 1.3.2 test. Changed:
| Option | 1.3.2 | 1.3.3 | Why |
|---|---|---|---|
| `xaeroLodMapRadius` = 0 (automatic) | DH's render distance, any size | DH's render distance up to 512 chunks (`XaeroLodMap.AUTO_MAX_CHUNKS`) | The writer fills about 43 regions a minute. A 1,024-chunk radius (3,487 regions) was 34 % filled after an hour; DH at 4,048 chunks would be about 50,000 regions. 512 chunks is about 800 regions, roughly 20 minutes. A number set by hand is used as before (up to 4,096). |
| `generateParentSections` | (new) | on | The hole fix. |

Kept, with the reason:
- `cpuLoad` Balanced: 6 threads on 16 logical CPUs. The test's overload came from High (12) next to DH's own 12 threads.
- `memoryLoad` Automatic: High from 8 GB of heap up. The test's memory trouble was the leak, now fixed, not the presets.
- `updateParentSections` off: it would bring back the partial parents.
- `dhRequestBoost` on: since 1.3.1 it only raises DH's limits while SeedGenGlobe runs short of work, and with the stand-in fix a long render queue no longer shows as holes.
- `xaeroLodMap` and `xaeroMinimapLodMap` on: the first generation should make the map; per Note 1, players switch it off afterwards.
- `mapFullDetailRadius` 32, `structures` up to detail 5, `trees`, `groundCover`, `threadPriority` auto, `yieldToServer` on, and the progress options: nothing in the test pointed at them.

Not SeedGenGlobe's to set: the test profile's DH settings (render distance 4,048 chunks, High quality, SSAO, 12 threads) were chosen by hand in `DistantHorizons.toml`. DH's own default render distance is 256 chunks (range 32–4,096, read from `Config$Client$Advanced$Graphics$Quality`), so with DH's defaults the automatic map radius stays 256 chunks and the cap changes nothing.

Also: `SectionFlags`' comment no longer claims parents are never needed. `updateParentSections`' text explains the partial-parent holes and points to the new option. README rows, CHANGELOG, `gradle.properties` and `build-offline.sh` are now 1.3.3.

DH facts this relies on (bytecode of the installed atlasfix jar):
- `FullDataSourceProviderV2.get` returns `createEmpty` for a missing row; an empty builder still gets `buffersUploaded = true`.
- The tree walk loads every ancestor.
- Generation requests come only from `nodesToEnableDeleteChildrenList`.
- `GeneratedFullDataSourceProvider.getPositionsToRetrieve` requests the coarse position itself (`min(lowestDataDetail + 6, detail)`) and treats `DOWN_SAMPLED` as not generated. `RemoteFullDataSourceProvider` inherits it.
- API sections get `applyToChildren = detail > 6`, but `runChildUpdates` only runs with `upsampleLowerDetailLodsToFillHoles` on (off here). Even then it only fills empty child columns, so generating parents never overwrites finer data.

## Worth checking in game
1. Mod Menu status page, or the log at startup: no "leaving out LodStandInMixin / LodParentRequestMixin / WorldGeneratorInjectorMixin".
2. With DH Distant Generation on (regenerate, or a new world): the world log line ends with `parentSections=true`. The closing summary shows per-detail times beyond d6 (parents), and the total is about a third more sections than 1.3.2's 3,273. After "LODs are complete", fly around: no parent-sized holes, at most a child-sized blank for a moment.
3. Switch Distant Generation off in DH's settings: the log says "Distant Horizons closed its generation queue …", not "… closed: N sections". Fly away and back, and towards far terrain: coarser terrain instead of holes.
4. Switch it on again in the same session: generation resumes ("generating again"), sections count up.
5. Open and leave the world 3–4 times in one game run: no "Insufficient memory detected", no growing stalls. DH's own "Closing all [N] database connections" still grows; that is DH's repository list, small.
6. A database from 1.3.2 has almost no parents. With generation on they are generated as DH loads them (no regenerate needed); with it off they stay missing until one run with generation on, or `/seedgenglobe regenerate`.
7. Chat wording of the Xaero map milestones.

## Known limits
- The stand-in rules only apply in dimensions SeedGenGlobe generates. Where no coarser section has data either (never generated at all), it stays empty as before.
- A parent holding part of its area (made from real chunk updates, or with `updateParentSections=true`) still draws something, so it is still used as a stand-in. Generation replaces it (it counts as `DOWN_SAMPLED`), but only while generation is on.
- Parent requests compete with wanted sections by distance. The few coarse ancestors around the player go first.
- With Distant Generation off, walking towards terrain shows the coarser level, not the finer one. Finer data only comes from real chunks or from generation.
- Not done yet: Xaero fixes 5–7 (writer throughput and on-screen first, empty world region files, the cache-time check), and the rest of Note 1 for Xaero (keep refilling and registering stored LOD regions while `xaeroLodMap` is off, without computing new ones). Note 2 (wait on a loading screen until LODs have loaded) has not been started.
