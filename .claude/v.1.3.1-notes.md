# SeedGenGlobe 1.3.1 – notes
 
Built 2026-11-01 from the 1.3.0 source, for Minecraft 26.1.2 against Distant Horizons 3.0.3-b (atlasfix), Big Globe 6.3.0, Xaero's World Map 1.47.2 / Minimap 26.6.2, Mod Menu 18.0.2 and Fabric API 0.155.3. Offline build (no Minecraft jar): link check 1709 references resolved (59 of them inherited by SeedGenGlobe's own subclasses, newly checked), the same 14 unverified as 1.3.0, 0 errors; the mixin plugin's requirement checks pass for every mixin, the new Mod Menu one included; all offline tests pass (ConfigTest 552 checks, SchedulerTest 637, new StructureSizeTest). Not run in game.
 
## Mod Menu requests and what changed
| Request | 1.3.1 |
|---|---|
| Scrollable at very small sizes, like Music & Sound Options | Title and Status at the top, Page defaults / Cancel / Save & quit at the bottom, page buttons and settings in one Minecraft list (`ContainerObjectSelectionList`) between them, with its scrollbar. Two columns from 368 GUI px of list width (the default 427 x 240 window now has two), page buttons laid out for the window so they stay put between pages. |
| Sliders for options with more than 3 values | `OptionSteps`: CPU load, memory load, overlay position, underground mode and every number are sliders (snap to values, arrow keys move one value); 2–3 values stay buttons. Threads slider 0 (automatic) to the logical CPU count; structure detail 0–15. |
| Milestones and progress in chunks (default) or blocks | `progressUnit` ("Distances in", Progress page): overlay, chat, status page and log. Milestones 64, 128, 256 … chunks (same distances as 1,024, 2,048 … blocks). |
| "Give way to the server" → "Server tps priority" | Label only; key stays `yieldToServer`. |
| "Done" → "Save & quit"; Status top right | Done. |
| Credits: just "Credits: / Mats Reinhart" | `authors` = Mats Reinhart. Mod Menu 18 always adds a role heading ("Authors:"), so `ModMenuCreditsMixin` (client, Mod Menu's `DescriptionListWidget.rebuildUI`) turns SeedGenGlobe's names into the heading lines without a colon. Skipped whole on a Mod Menu that differs. |
 
## Performance fixes (from 1.3.0 Tests 1 and 2)
| Finding | 1.3.1 |
|---|---|
| Test 2 #3: below-normal priority starves the threads while the server and DH work | New `threadPriority`: Automatic (default) = normal, the CPU preset's lower priority only while the server is behind (with Server tps priority on, hysteresis 52 / 40 ms); Normal; Lower = 1.3.0. Minimal and Low always low. Priority changes are logged (first 10). |
| Test 1 #4: boost keeps generation fighting the render loader | `dhRequestBoost` raises DH's render/file queue limit only while SeedGenGlobe has fewer DH sections waiting than threads allowed (`SectionScheduler.shortOfWork`). The in-flight boost stays. Overlay "Waiting for DH's …" only shows when the threads actually run short. |
| Test 1 #2: structure terrain as costly as terrain | Precompute only the dependencies of the overriders active in the quad (per bitmask, cached, via `SortedOverriders.extractDependencies`). `fullStructurePrecompute` (Advanced) = 1.3.0. Possible difference: a value only inactive overriders read, that depends on a value an active one changes, is computed after the change instead of before. |
| Test 1 #3: structure lookup dominates coarse sections | Ordinary sets are looked up only up to `detailForSpan(widest start)` once 32 starts were seen (cells at most half the structure's width: 16 blocks → 3, 32 → 4, 64 → 5). Collision checks use the same sets per detail level. Log line per set it applies to; lookup time by set on the status page and in the log when a world closes. |
| Test 2 #5: "server is behind (22 ms per tick)" while recovering | Reason now "speeding up again after the server fell behind (… ms per tick now)". |
| 1.2.0 #6 / Test 1 #9: "Expected ChestBlockEntity …" warnings | CaptureLevel remembers block-entity blocks pieces place in dropped columns and makes the block entity from them. |
 
Also: StatusScreen's bottom buttons fit small windows. LinkCheck now verifies members inherited through own subclasses (and normalises the scanned mods' references the same way). `tools/SettingsTable.java` regenerated the README settings tables (now with the in-game names).
 
## Worth checking in game
1. Config screen in a very small window (as in the screenshot) and at normal size: scrolling, scrollbar, nothing overlapping, the three bottom buttons fit, tooltips on buttons and sliders.
2. Sliders: drag, click, arrow keys after clicking or tabbing to one; values snap; Save & quit applies, Cancel / Escape discard; the "takes effect when…" note (or the Save & quit tooltip in short windows).
3. Mod Menu: SeedGenGlobe's description panel shows "Credits:" then "Mats Reinhart", other mods unchanged.
4. Progress in chunks: overlay, chat milestones, log lines; then `progressUnit` blocks.
5. Speed, same seed as Test 2 (spawn −8101, 5852), Balanced, defaults: first three minutes and total vs 1.1.0 (about 8 min) and 1.3.0 Test 2 (7:03, structures off). Then structures on. Look for: "generator threads at priority …" lines, "… structures are small … leave them out" lines, and at quit the structure summary with "Structure lookup time by set" and "inputs computed for N combinations".
6. Structure terrain A/B: LODs around villages and houses with `fullStructurePrecompute` off vs on should look the same.
7. "Expected class ChestBlockEntity … in SeedGenGlobe structure capture" count (44 in Test 1).
## Known limits
- The settings list and sliders use Minecraft GUI internals (26.1's `extractContent`, `getY`, `addEntry`, `centerListVertically`, the slider's `value`, `updateMessage`, `applyValue`), checked against how Distant Horizons, Mod Menu, Cloth Config and Xaero's mods built for 26.1.2 use them, not against Minecraft itself.
- Small-set cut-off is decided from the first 32 starts of each set; sections generated before that still carry small structures at coarse detail.
- Java thread priorities only take effect on Windows by default (Linux ignores them without `-XX:ThreadPriorityPolicy=1` as root), so `threadPriority` changes nothing on Linux.
- The credits mixin depends on Mod Menu 18's `rebuildUI`; a different Mod Menu shows "Authors:" again.
