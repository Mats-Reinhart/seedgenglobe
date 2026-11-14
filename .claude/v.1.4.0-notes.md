# SeedGenGlobe 1.4.0 – notes

Built 2026-11-04 from the 1.3.3 source (including the uncommitted `xaeroLodMapRadius` automatic cap, `XaeroLodMap.AUTO_MAX_CHUNKS` = 512), for Minecraft 26.1.2 against Distant Horizons 3.0.3-b (atlasfix), Big Globe 6.3.0, Xaero's World Map 1.47.2 / Minimap 26.6.2 and Vertigo 1.2.6. Built with Gradle/Loom offline on this machine (`gradlew --offline build -x test`), same setup as 1.3.3. Checks: CanopyMath 338775, CaptureView 20030, ColumnProfile 110009, Config 561, GroundCover 649, LodCanvas 635, LodMapGeometry 65, LodRegeneration 43, Scheduler 637, StructureSize 15013, XaeroPixel 32, all passed. Jar: `SeedGenGlobe-1.4.0mc26.1.2.jar` (SHA-256 `489648d1…1d54a3505ba`). Not run in game.

## Instruction (2026-11-04)
Link https://github.com/Mats-Reinhart/seedgenglobe as the website and https://github.com/Mats-Reinhart/seedgenglobe/issues as the issue tracker through Mod Menu's mod page buttons, then build everything as 1.4.0.

## What changed
| Change | Where |
|---|---|
| Mod Menu's **Website** button opens the GitHub repository and its **Issues** button opens the GitHub issues page | `fabric.mod.json`: new `contact` block, `homepage` and `issues`. Mod Menu 18.0.2 reads these in `FabricMod.getWebsite()` (`contact.homepage`) and `FabricMod.getIssueTracker()` (`contact.issues`). Before this, both buttons were greyed out. |
| Version 1.4.0 | `gradle.properties` (`mod_version`), `build-offline.sh` (`VERSION`), CHANGELOG, `.claude/CLAUDE.md` |

No `contact.sources` was added. Mod Menu would show it as a "Source" link in the description pane, and the request named only Website and Issues.

## Worth checking in game
1. Mods screen → SeedGenGlobe: Website and Issues buttons are active and open the two GitHub pages (Mod Menu asks to confirm before opening a link).
2. Version shows as `1.4.0+mc26.1.2`.
3. Everything in `v.1.3.3-notes.md` "Worth checking in game", since 1.3.3 was not tested in game either.

## Still open (carried over from 1.3.3)
- Xaero fixes 5–7: writer throughput (on-screen first), empty world region files, the cache-time check.
- The rest of Note 1 for Xaero: keep refilling and registering stored LOD regions while `xaeroLodMap` is off.
- Note 2: wait on a loading screen until LODs have loaded.
